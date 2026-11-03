# AI-attribution patterns, shared by the Claude Code hooks (block-ai-attribution.sh) and the
# git commit-msg hook (.githooks/commit-msg). POSIX sh, meant to be sourced with `.`.
#
# AI_ATTRIBUTION_RE is one extended regex, used with `grep -Ei`, so it is matched per line and
# case-insensitively. It is deliberately narrow: a person who happens to be called Claude
# ("Co-Authored-By: Claude Dupont <...>") must pass, so an AI tool name only counts when it is
# followed by nothing but tool-ish words (Code, Agent, Opus, a version, a parenthetical) and then
# the end of the name or line.

_ai_tool='(claude|anthropic|copilot|codex|chatgpt|openai|gemini|cursor)'
_ai_suffix='([[:space:]]+(code|agent|bot|ai|opus|sonnet|haiku|fable|[0-9][0-9.]*|v[0-9][0-9.]*|\([^)]*\)))*'

# Co-Authored-By trailer whose name is an AI tool
_p1='co-authored-by:[[:space:]]*'"$_ai_tool""$_ai_suffix"'[[:space:]]*(<|$)'
# Co-Authored-By trailer with an AI vendor email
_p2='co-authored-by:.*<[^>]*@(anthropic|openai)\.com>'
# Co-Authored-By trailer with an AI bot noreply address (Copilot[bot], 12345+Copilot@users.noreply...)
_p3='co-authored-by:.*<[^>]*'"$_ai_tool"'[^>@]*\[bot\]@users\.noreply\.github\.com>'
_p4='co-authored-by:.*<[0-9]+\+'"$_ai_tool"'@users\.noreply\.github\.com>'
# Claude-Session: trailer or line
_p5='claude-session:'
# claude.ai session link
_p6='claude\.ai/(code/)?session'
# "Generated with / Written by / Created with <AI tool>" line
_p7='(generated|written|created)[[:space:]]+(with|by)[[:space:]]+[][*_]*'"$_ai_tool""$_ai_suffix"'[])*_]*([[:space:]]*[[:punct:]]|[[:space:]]*$)'
# vendor noreply address
_p8='noreply@anthropic\.com'

AI_ATTRIBUTION_RE="$_p1|$_p2|$_p3|$_p4|$_p5|$_p6|$_p7|$_p8"

# ai_find_attribution: reads text on stdin, prints the first offending line, returns 0 when found.
ai_find_attribution() {
    _hit=$(grep -Ei -m1 -e "$AI_ATTRIBUTION_RE")
    if [ -n "$_hit" ]; then
        printf '%s\n' "$_hit"
        return 0
    fi
    return 1
}
