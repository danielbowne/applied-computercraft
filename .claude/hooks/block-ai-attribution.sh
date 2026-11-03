#!/usr/bin/env bash
# PreToolUse hook (matcher: Bash). Blocks gh commands that write to GitHub (PR, issue, release
# create/edit/comment/review/merge, and gh api writes) when the command text contains AI
# attribution: a Co-Authored-By trailer naming an AI tool, a Claude-Session: line, a
# claude.ai session link, a "Generated/Written/Created with <AI tool>" line, or a vendor
# noreply address. Patterns live in _patterns.sh (shared with .githooks/commit-msg).
#
# What is scanned: the full command text, heredoc bodies included (that is where PR bodies
# usually are). What is NOT scanned: the contents of a file passed with --body-file / -F, because
# the hook only sees the command line. Read-only gh commands are never blocked.
set -u
. "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

read_event

# is_gh_write <args after gh>: true when the gh invocation writes to GitHub.
is_gh_write() {
    local args=$1 tok prev="" m
    local padded=" $args "
    local re_sub='[[:space:]](pr[[:space:]]+(create|edit|comment|review|merge)|issue[[:space:]]+(create|edit|comment)|release[[:space:]]+(create|edit))[[:space:]]'
    if [[ $padded =~ $re_sub ]]; then return 0; fi
    if [[ $padded =~ [[:space:]]api[[:space:]] ]]; then
        set -f
        for tok in $args; do
            case $tok in
                -X[A-Za-z]* | --method=*)
                    m=${tok#-X}; m=${m#--method=}
                    if [[ ${m^^} =~ ^(POST|PATCH|PUT)$ ]]; then set +f; return 0; fi
                    ;;
                -f | -F | --field | --raw-field | --input | -f* | -F* | --field=* | --raw-field=* | --input=*)
                    set +f; return 0 ;;
            esac
            case $prev in
                -X | --method)
                    if [[ ${tok^^} =~ ^(POST|PATCH|PUT)$ ]]; then set +f; return 0; fi
                    ;;
            esac
            prev=$tok
        done
        set +f
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
