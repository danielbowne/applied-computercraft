#!/usr/bin/env bash
# PreToolUse hook (matcher: Bash). Blocks `gh pr merge --squash` unless an explicit body is
# given, because GitHub's default squash body is a list of every commit message and this repo
# wants a written one. Exit 2 blocks the call and shows stderr to Claude; exit 0 allows.
#
# Quoted strings and heredoc bodies are masked first, so text that merely mentions the command
# (a commit message, a PR body) is not a match. Degraded mode (no jq, no python3): any text that
# looks like `gh ... pr merge` in the raw event is blocked.
set -u
. "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

read_event

block() {
    echo "BLOCKED: gh pr merge --squash needs an explicit body. Add --body-file <file> (or --body / -F / -b) along with --subject, e.g." >&2
    echo "  gh pr merge <n> --squash --admin --subject \"<subject> (#<n>)\" --body-file <file>" >&2
    echo "$1" >&2
    exit 2
}

if [ "$degraded" -eq 1 ]; then
    if printf '%s' "$raw_input" | grep -Eq 'gh[^"]*[[:space:]]pr[[:space:]]+merge'; then
        block "(hook could not parse the event: neither jq nor python3 is usable, so every gh pr merge is blocked)"
    fi
    exit 0
fi

[ -z "$cmd" ] && exit 0

while IFS= read -r seg; do
    args=$(gh_args "$seg") || continue
    [[ " $args " =~ [[:space:]]pr[[:space:]]+merge[[:space:]] ]] || continue

    squash=0
    body=0
    set -f
    for tok in $args; do
        case $tok in
            --squash) squash=1 ;;
            --body | --body=* | --body-file | --body-file=*) body=1 ;;
            --*) ;;
            -*)
                # bundled short flags, e.g. -sd or -F
                [[ $tok =~ ^-[A-Za-z]+$ ]] || continue
                [[ $tok == *s* ]] && squash=1
                [[ $tok == *b* || $tok == *F* ]] && body=1
                ;;
        esac
    done
    set +f

    if [ "$squash" -eq 1 ] && [ "$body" -eq 0 ]; then
        block "(command segment: gh $args)"
    fi
done < <(command_segments)

exit 0
