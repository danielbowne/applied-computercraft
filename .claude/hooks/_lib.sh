# Shared helpers for the PreToolUse hooks. Source it; do not execute it.
#
# Claude Code hook matchers match the tool name only ("Bash"), never the command text, so each
# hook receives every Bash call and has to decide for itself whether the command is relevant.
# Text inside quotes or heredocs must never be mistaken for a command, hence the helpers below.

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

# strip_heredocs: stdin -> stdout, with heredoc bodies (and their terminator lines) removed.
strip_heredocs() {
    awk '
    BEGIN { n = 0; q = 0 }
    {
        if (q < n) {
            line = $0
            sub(/^[ \t]+/, "", line)
            sub(/[ \t\r]+$/, "", line)
            if (line == delims[q + 1]) q++
            next
        }
        print
        n = 0; q = 0
        s = $0
        while (match(s, /<<-?[ \t]*[\\"\047]*[A-Za-z_][A-Za-z0-9_]*/)) {
            m = substr(s, RSTART, RLENGTH)
            prev = (RSTART > 1) ? substr(s, RSTART - 1, 1) : ""
            s = substr(s, RSTART + RLENGTH)
            if (prev == "<") continue   # here-string (<<<), not a heredoc
            sub(/^<<-?[ \t]*[\\"\047]*/, "", m)
            delims[++n] = m
        }
    }'
}

# mask_quotes: replace everything inside '...' and "..." with underscores (newlines too, so a
# quoted multi-line string stays on one line), keeping the quote characters. Backslash escapes
# are honoured outside single quotes. Argument in, result on stdout.
mask_quotes() {
    local s=$1 out="" c i n=${#1} mode=""
    for ((i = 0; i < n; i++)); do
        c=${s:i:1}
        case $mode in
            s)
                if [[ $c == "'" ]]; then mode=""; out+="'"; else out+="_"; fi
                ;;
            d)
                if [[ $c == '\' ]]; then
                    out+="__"; ((i++))
                elif [[ $c == '"' ]]; then
                    mode=""; out+='"'
                else
                    out+="_"
                fi
                ;;
            *)
                if [[ $c == '\' ]]; then
                    out+="$c${s:i+1:1}"; ((i++))
                elif [[ $c == "'" ]]; then
                    mode=s; out+="'"
                elif [[ $c == '"' ]]; then
                    mode=d; out+='"'
                else
                    out+="$c"
                fi
                ;;
        esac
    done
    printf '%s' "$out"
}

# command_segments: reads $cmd, removes heredoc bodies, masks quotes, and prints one simple
# command per line (split on ; & | ( ) backtick and newline).
command_segments() {
    local stripped masked
    stripped=$(printf '%s\n' "$cmd" | strip_heredocs)
    masked=$(mask_quotes "$stripped")
    printf '%s\n' "$masked" | tr ';&|()`' '\n\n\n\n\n\n'
}

# gh_args <segment>: if the segment is a gh invocation (optionally behind env assignments or
# wrappers like sudo/env/rtk), print everything after `gh` and return 0. Otherwise return 1.
gh_args() {
    local re='^[[:space:]]*(([A-Za-z_][A-Za-z0-9_]*=[^[:space:]]*|env|sudo|command|time|nohup|exec|rtk|xargs)[[:space:]]+)*gh([[:space:]]+(.*))?$'
    if [[ $1 =~ $re ]]; then
        printf '%s' "${BASH_REMATCH[4]}"
        return 0
    fi
    return 1
}
