#!/usr/bin/env bash
# PreToolUse hook (matcher: Bash). Blocks gh commands that write to GitHub (PR, issue, release,
# gist, repo, label and project writes, and gh api writes) when the command text contains AI
# attribution: a Co-Authored-By trailer naming an AI tool, a Claude-Session: line, a
# claude.ai/code/session link, a "Generated/Written/Created with <AI tool>" line, or a vendor
# noreply address. Patterns live in _patterns.sh (shared with .githooks/commit-msg).
#
# What is scanned: the full command text, heredoc bodies included (that is where PR bodies
# usually are). What is NOT scanned: the contents of a file passed with --body-file / -F, because
# the hook only sees the command line. Read-only gh commands are never blocked; `gh api graphql`
# counts as a write only when the query contains a mutation.
# Known limits: gh inside bash -c "..." or eval "...", backtick substitution inside double quotes, variables that expand to gh, and gh aliases.
# Runs on bash 3.2 and newer.
set -u
. "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

read_event

# is_gh_write <args after gh>: true when the gh invocation writes to GitHub.
is_gh_write() {
    local args=$1 tok prev="" m method="" has_field=0 method_uc
    local padded=" $args "
    local rf=$GH_REPOFLAG
    local re_sub='[[:space:]](pr'"$rf"'[[:space:]]+(create|edit|comment|review|merge|close|reopen)|issue'"$rf"'[[:space:]]+(create|edit|comment|close|reopen)|release'"$rf"'[[:space:]]+(create|edit)|gist'"$rf"'[[:space:]]+(create|edit)|repo'"$rf"'[[:space:]]+(edit|create)|label'"$rf"'[[:space:]]+(create|edit)|project'"$rf"'[[:space:]]+(item-create|item-edit))[[:space:]]'
    if [[ $padded =~ $re_sub ]]; then return 0; fi
    if [[ $padded =~ [[:space:]]api[[:space:]] ]]; then
        if [[ $padded =~ [[:space:]]graphql[[:space:]] ]]; then
            # a query is a read; only a mutation writes
            [[ $cmd =~ (^|[^[:alnum:]_])mutation([^[:alnum:]_]|$) ]] && return 0
            return 1
        fi
        set -f
        for tok in $args; do
            m=""
            case $tok in
                -X?*) m=${tok#-X} ;;
                --method=*) m=${tok#--method=} ;;
            esac
            [ -n "$m" ] && method=$m
            case $prev in
                -X | --method) method=$tok ;;
            esac
            case $tok in
                -f | -F | --field | --raw-field | --input | -f?* | -F?* | --field=* | --raw-field=* | --input=*) has_field=1 ;;
            esac
            prev=$tok
        done
        set +f
        method_uc=$(printf '%s' "$method" | tr '[:lower:]' '[:upper:]')
        case $method_uc in
            POST | PATCH | PUT | DELETE) return 0 ;;
            GET | HEAD) return 1 ;;
        esac
        [ "$has_field" -eq 1 ] && return 0
    fi
    return 1
}

report() {
    {
        echo "BLOCKED: AI attribution in a gh write command. This repo does not allow it in commits, PRs, issues, comments or release notes."
        echo "Offending line: $1"
        echo "Remove it and run the command again."
    } >&2
    exit 2
}

if [ "$degraded" -eq 1 ]; then
    # cannot tell whether this is a gh write; scan the raw event only if it mentions gh at all
    if printf '%s' "$raw_input" | grep -Eq '(^|[^[:alnum:]_])gh[[:space:]]'; then
        if hit=$(printf '%s' "$raw_input" | ai_find_attribution); then report "$hit"; fi
    fi
    exit 0
fi

[ -z "$cmd" ] && exit 0
# fast path: nothing to check unless the command line mentions gh at all
case $cmd in *gh*) ;; *) exit 0 ;; esac

write=0
while IFS= read -r seg; do
    args=$(gh_args "$seg") || continue
    if is_gh_write "$args"; then write=1; break; fi
done < <(command_segments)

[ "$write" -eq 1 ] || exit 0

if hit=$(printf '%s\n' "$cmd" | ai_find_attribution); then
    report "$hit"
fi
exit 0
