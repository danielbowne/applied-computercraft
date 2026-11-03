# AI-attribution patterns, shared by the Claude Code hooks (block-ai-attribution.sh) and the
# git commit-msg hook (.githooks/commit-msg). POSIX sh, meant to be sourced with `.`.
#
# Two grep -E expressions, matched per line:
#   AI_ATTRIBUTION_RE     case-insensitive: trailers, session lines and links, vendor addresses
#   AI_ATTRIBUTION_CS_RE  case-sensitive: the "Generated/Written/Created with|by <tool>" line,
#                         where ordinary lowercase words (cursor, gemini, codex) are not tools
#
# Both are deliberately narrow. A person called Claude ("Co-Authored-By: Claude Dupont <...>")
# must pass, so a tool name only counts when what follows it is nothing but tool-ish words (Code,
# Agent, Opus, a version, a parenthetical, [bot]) and then a real end: "<", end of line, a quote,
# or a literal \n (the command line shows newlines inside quoted bodies as backslash-n).

_q="[\"'\`]"
_ai_tool='(claude|anthropic|copilot|codex|chatgpt|openai|gemini|cursor)'
_ai_sfx='(([[:space:]]+|-)(code|agent|bot|ai|opus|sonnet|haiku|fable|[0-9][0-9.]*|v[0-9][0-9.]*|\([^)]*\)))*(\[bot\])?'
_name_end='[[:space:]]*(<|$|\\n|\\r|'"$_q"')'

# Co-Authored-By trailer whose name is an AI tool
_p1='co-authored-by:[[:space:]]*'"$_ai_tool""$_ai_sfx""$_name_end"
# Co-Authored-By trailer with an AI vendor email
_p2='co-authored-by:.*<[^>]*@(anthropic|openai)\.com>'
# Co-Authored-By trailer with an AI bot noreply address (Copilot[bot], 12345+Copilot@users.noreply...)
_p3='co-authored-by:.*<[^>]*'"$_ai_tool"'[^>@]*\[bot\]@users\.noreply\.github\.com>'
_p4='co-authored-by:.*<[0-9]+\+'"$_ai_tool"'@users\.noreply\.github\.com>'
# Claude-Session: trailer: at the start of a line, after a literal \n, or right after an opening
# quote, followed by a token. Prose that merely names the trailer mid-sentence does not match.
_p5='(^|\\n|'"$_q"')[[:space:]]*claude-session:[[:space:]]*[^[:space:]]'
# claude.ai session link
_p6='claude\.ai/(code/)?session'
# vendor noreply address
_p8='noreply@anthropic\.com'

AI_ATTRIBUTION_RE="$_p1|$_p2|$_p3|$_p4|$_p5|$_p6|$_p8"

# "Generated with / Written by / Created with <AI tool>" line, case-sensitive on the product
# names. A real end is required (end of line, ., !, a link paren, a quote, literal \n); "-" or ","
# followed by more words is not an end, and a possessive ('s) is not attribution.
_cs_tool='(Claude|claude|Anthropic|Copilot|Codex|ChatGPT|chatgpt|OpenAI|Gemini|Cursor)'
_cs_sfx='(([[:space:]]+|-)([Cc]ode|[Aa]gent|[Bb]ot|AI|[Oo]pus|[Ss]onnet|[Hh]aiku|[Ff]able|[0-9][0-9.]*|v[0-9][0-9.]*|\([^)]*\)))*'
_cs_end='[])*_]*[[:space:]]*($|[.!(]|\\n|"|'"'"'([^sS]|$))'
AI_ATTRIBUTION_CS_RE='([Gg]enerated|[Ww]ritten|[Cc]reated)[[:space:]]+(with|by)[[:space:]]+[][*_(]*'"$_cs_tool""$_cs_sfx""$_cs_end"

# ai_find_attribution: reads text on stdin, prints the first offending line, returns 0 when found.
ai_find_attribution() {
    _in=$(cat)
    _hit=$(printf '%s\n' "$_in" | grep -Ei -m1 -e "$AI_ATTRIBUTION_RE")
    if [ -z "$_hit" ]; then
        _hit=$(printf '%s\n' "$_in" | grep -E -m1 -e "$AI_ATTRIBUTION_CS_RE")
    fi
    if [ -n "$_hit" ]; then
        printf '%s\n' "$_hit"
        return 0
    fi
    return 1
}
