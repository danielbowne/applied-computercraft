# .claude/

Shared Claude Code setup for this repo. Everything here is committed so every contributor, and every Claude session, works the same way. The project rules themselves are in the root `CLAUDE.md`.

## What lives here

| Path | What it is |
|---|---|
| `settings.json` | Project settings: `gh pr merge` always asks, and two PreToolUse hooks on Bash |
| `agents/builder.md` | The `builder` agent: implements a self-contained brief, runs the gates, commits, reports |
| `skills/ship-flow/` | The end-to-end order from branch to merge and cleanup |
| `skills/pre-push-review/` | The five-lane review that runs before every push |
| `skills/pr-description/` | How PR bodies are written, plus the repo-specific checks |
| `skills/release/` | Cutting a version and tagging it |
| `commands/ship-it.md` | `/ship-it`: walks the current branch through the flow |
| `workflows/pre-push-review.js` | The Workflow script behind the pre-push review |
| `hooks/` | `guard-squash-merge.sh`, `block-ai-attribution.sh`, and the shared `_lib.sh` / `_patterns.sh` |

`.claude/worktrees/` and `settings.local.json` are git-ignored.

## The flow

1. Branch from fresh `origin/main` as `<type>/<slug>` (never commit to `main`).
2. A builder (or you) commits the change and the gates pass; builders stop at the commit.
3. Pre-push review runs on `origin/main...HEAD`; findings are fixed by default.
4. Push, then open a PR with a body written per the `pr-description` skill.
5. The owner merges: `gh pr merge <n> --squash --admin --subject "<subject> (#<n>)" --body-file <file>`. Then clean up (`git branch -D`, prune, remove the worktree) and cut a release if it was a release PR.

Details: `skills/ship-flow/SKILL.md`.

## Gates

| Where | What runs | Catches |
|---|---|---|
| On commit (git hooks) | `.githooks/pre-commit` | commits on `main` |
| On commit (git hooks) | `.githooks/commit-msg` | AI attribution in the commit message |
| On Claude `gh` commands | `settings.json` `permissions.ask` | every `gh pr merge` needs a confirmation |
| On Claude `gh` commands | `hooks/guard-squash-merge.sh` | a squash merge without `--body-file` / `--body` |
| On Claude `gh` commands | `hooks/block-ai-attribution.sh` | AI attribution in `gh` write commands (PR, issue, release, `gh api` writes) |
| In CI | `.github/workflows/build.yml` | `./gradlew build` on every push and PR |
| In CI | `.github/workflows/release.yml` | tag equals `mod_version`, CHANGELOG section exists, build, GitHub Release, CurseForge |

Limits worth knowing: the attribution hook only reads the command line, so the contents of a `--body-file` are not scanned. Hook matchers match the tool name only, so the scripts filter the command text themselves, masking quoted strings and heredoc bodies so that text which merely mentions a command is not mistaken for one. Without `jq` or `python3` the hooks run degraded and fail closed for `gh pr merge`.

## Install the git hooks

Git does not run hooks from a tracked folder by default. Once per clone:

```
git config core.hooksPath .githooks
```

## What stays personal

Anything specific to one person's machine lives in `~/.claude` (or `.claude/settings.local.json`, which is ignored), not here: personal memory and preferences, global hooks, credentials and cloud logins, editor and terminal setup, and per-machine paths. Nothing in this folder should contain absolute home paths, machine names, addresses, account ids or email addresses.
