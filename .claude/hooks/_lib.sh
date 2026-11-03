# Shared helpers for the PreToolUse hooks. Source it; do not execute it.
# Written to run on bash 3.2 (macOS) as well as bash 4+: no case-conversion expansions, no associative arrays.
#
# Claude Code hook matchers match the tool name only ("Bash"), never the command text, so each
# hook receives every Bash call and has to decide for itself whether the command is relevant.
# Text inside quotes or heredocs must never be mistaken for a command, hence sanitize_command.

set -u

hook_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=_patterns.sh
. "$hook_dir/_patterns.sh"

degraded=0
raw_input=""
cmd=""

# read_event: read the PreToolUse event JSON from stdin, set $raw_input and $cmd
# (.tool_input.command). Uses jq, falls back to python3. When neither works, sets degraded=1
# and warns on stderr; callers then fall back to scanning $raw_input and decide how to fail.
read_event() {
    raw_input=$(cat)
    cmd=""
    local rc
    if command -v jq >/dev/null 2>&1; then
        cmd=$(printf '%s' "$raw_input" | jq -r '.tool_input.command // empty' 2>/dev/null)
        rc=$?
        [ "$rc" -eq 0 ] && return 0
    fi
    if command -v python3 >/dev/null 2>&1; then
        cmd=$(printf '%s' "$raw_input" | python3 -c 'import sys, json
d = json.load(sys.stdin)
ti = d.get("tool_input") or {}
sys.stdout.write(str(ti.get("command") or ""))' 2>/dev/null)
        rc=$?
        [ "$rc" -eq 0 ] && return 0
    fi
    degraded=1
    cmd=""
    echo "hook warning: neither jq nor python3 could parse the event; running in degraded mode" >&2
    return 0
}

# sanitize_command: stdin -> stdout, one linear awk pass that
#   - joins backslash-newline continuations into one line,
#   - removes heredoc bodies and their terminator lines (a heredoc only starts at an unquoted
#     << or <<-, never at <<< or inside $(( )) arithmetic),
#   - replaces everything inside '...' and "..." with underscores (newlines too, so a quoted
#     multi-line string stays on one line), keeping the quote characters.
# Quote state persists across lines. Backslash escapes are honoured outside single quotes.
sanitize_command() {
    awk '
    function rep(k) { while (length(U) < k) U = U U; return substr(U, 1, k) }
    BEGIN { U = "________"; sq = "\047"; mode = ""; arith = 0; nq = 0; qi = 0; incont = 0 }
    {
        if (!incont && qi < nq) {              # inside a heredoc body
            line = $0
            sub(/^[ \t]+/, "", line)
            sub(/[ \t\r]+$/, "", line)
            if (line == delims[qi + 1]) qi++
            if (qi >= nq) { nq = 0; qi = 0 }
            next
        }
        s = $0; n = length(s); out = ""; i = 1; cont = 0
        while (i <= n) {
            if (mode == "s") {
                rest = substr(s, i)
                p = index(rest, sq)
                if (p > 0) { out = out rep(p - 1) sq; mode = ""; i += p }
                else { out = out rep(length(rest)); i = n + 1 }
                continue
            }
            if (mode == "d") {
                rest = substr(s, i)
                if (match(rest, /[\\"]/)) {
                    out = out rep(RSTART - 1)
                    k = i + RSTART - 1
                    if (substr(s, k, 1) == "\\") {
                        if (k == n) { cont = 1; i = n + 1 }
                        else { out = out "__"; i = k + 2 }
                    } else { out = out "\""; mode = ""; i = k + 1 }
                } else { out = out rep(length(rest)); i = n + 1 }
                continue
            }
            c = substr(s, i, 1)
            if (c == "\\") {
                if (i == n) { cont = 1; i = n + 1 }
                else { out = out substr(s, i, 2); i += 2 }
                continue
            }
            if (c == sq) { mode = "s"; out = out c; i++; continue }
            if (c == "\"") { mode = "d"; out = out c; i++; continue }
            if (c == "(") {
                if (arith > 0) arith++
                else if (substr(s, i, 2) == "((") { arith = 2; out = out "(("; i += 2; continue }
            } else if (c == ")" && arith > 0) {
                arith--
            } else if (c == "<" && arith == 0 && substr(s, i, 2) == "<<") {
                prev = (i > 1) ? substr(s, i - 1, 1) : ""
                if (prev == "<" || substr(s, i + 2, 1) == "<") { out = out "<<"; i += 2; continue }   # here-string
                j = i + 2
                if (substr(s, j, 1) == "-") j++
                while (substr(s, j, 1) ~ /[ \t]/) j++
                q = substr(s, j, 1)
                d = ""
                if (q == sq || q == "\"") {
                    j++
                    while (j <= n && substr(s, j, 1) != q) { d = d substr(s, j, 1); j++ }
                    j++
                } else {
                    if (q == "\\") j++
                    while (j <= n && substr(s, j, 1) ~ /[A-Za-z0-9_.-]/) { d = d substr(s, j, 1); j++ }
                }
                if (d != "") { delims[++nq] = d; out = out "<<H"; i = j; continue }
                out = out "<<"; i += 2; continue
            }
            out = out c; i++
        }
        incont = cont
        arith = 0
        if (cont) printf "%s", out
        else if (mode != "") printf "%s_", out
        else print out
    }
    END { if (incont) printf "\n" }'
}

# command_segments: reads $cmd, sanitizes it, and prints one simple command per line
# (split on ; & | ( ) backtick and newline).
command_segments() {
    printf '%s\n' "$cmd" | sanitize_command | tr ';&|()`' '\n\n\n\n\n\n'
}

# gh_args <segment>: if the segment runs gh (possibly behind shell keywords such as then/do/!,
# env assignments, wrappers such as env/sudo/timeout/nice/nohup/rtk, a path like /usr/bin/gh or
# a backslash like \gh), print " <args after gh>" and return 0. Otherwise return 1.
gh_args() {
    local tok t skip=0 found=0 args=""
    set -f
    for tok in $1; do
        if [ "$found" -eq 1 ]; then
            args="$args $tok"
            continue
        fi
        if [ "$skip" -eq 1 ]; then
            skip=0
            continue
        fi
        t=${tok#\\}
        case $t in
            then | do | else | elif | if | while | until | '{' | '!' | time | env | sudo | command | exec | nohup | rtk | xargs | nice | timeout | stdbuf | ionice | setsid) ;;
            -u | -g | -C | -U | -D) skip=1 ;;
            -*) ;;
            [A-Za-z_]*=*) ;;
            [0-9]*) ;;
            gh | */gh) found=1 ;;
            *) set +f; return 1 ;;
        esac
    done
    set +f
    [ "$found" -eq 1 ] || return 1
    printf '%s' "$args"
    return 0
}
