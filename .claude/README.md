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
| `skills/release/` | How releases work (release-please): the release PR, merging it, verifying, recovery |
| `commands/ship-it.md` | `/ship-it`: walks the current branch through the flow |
| `workflows/pre-push-review.js` | The Workflow script behind the pre-push review |
| `hooks/` | `guard-squash-merge.sh`, `block-ai-attribution.sh`, and the shared `_lib.sh` / `_patterns.sh` |

`.claude/worktrees/`, `settings.local.json`, other `*.local.*` files, `.env*` and `*.log` under `.claude/` are git-ignored.

## The flow

1. Branch from fresh `origin/main` as `<type>/<slug>` (never commit to `main`).
2. A builder (or you) commits the change and the gates pass; builders stop at the commit.
3. Pre-push review runs on `origin/main...HEAD`; findings are fixed by default.
4. Push, then open a PR with a body written per the `pr-description` skill.
5. The owner merges, only after `build` is green on an up-to-date branch with conversations resolved (`--admin` bypasses both checks): `gh pr merge <n> --squash --admin --subject "<subject> (#<n>)" --body-file <file>`. Then clean up (check the PR shows `MERGED` and the head SHA matches, remove the worktree, prune, then `git branch -D`) and cut a release if it was a release PR.

Details: `skills/ship-flow/SKILL.md`.

## Gates

| Where | What runs | Catches |
|---|---|---|
| On commit (git hooks, opt-in) | `.githooks/pre-commit` | commits on `main` (version branches such as `mc-26.2` are allowed by design) |
| On commit (git hooks, opt-in) | `.githooks/commit-msg` | AI attribution anywhere in the commit message, above the `git commit -v` scissors line |
| On Claude `gh` commands | `settings.json` `permissions.ask` | every `gh pr merge` needs a confirmation |
| On Claude `gh` commands | `hooks/guard-squash-merge.sh` | a squash merge without `--body-file` / `--body` |
| On Claude `gh` commands | `hooks/block-ai-attribution.sh` | AI attribution in `gh` write commands (PR, issue, release, gist, repo description, `gh api` writes) |
| In CI | `.github/workflows/build.yml` | `./gradlew build` on every push and PR |
| In CI | `.github/workflows/build.yml` (workflow_dispatch) | release PRs are made with the workflow token, which starts no workflows; `release-please.yml` dispatches `build` on the release branch so the required check exists on the PR head |
| In CI | `.github/workflows/release-please.yml`, job `release-please` | opens and updates the release PR from conventional commits; on merge creates the tag and GitHub Release (needs "Allow GitHub Actions to create and approve pull requests" enabled by the owner) |
| In CI | `.github/workflows/release.yml` (called as job `jar`; also run by hand with a tag) | tag equals `mod_version`, build, jar attached to the existing Release (`--clobber`, so re-runs are safe) |
| In CI | `.github/workflows/release-please.yml`, job `release-notes` | optional: rewrites the Release body with Claude when the `ANTHROPIC_API_KEY` secret exists (model from the `RELEASE_NOTES_MODEL` variable); never fails the release; keeps the original notes if a link is dropped |
| In CI | `.github/workflows/release-please.yml`, job `curseforge` | uploads the jar to CurseForge when `CURSEFORGE_PROJECT_ID` is set, using the final Release body; checks the token is non-empty; runs even if `release-notes` was skipped; can be re-run on its own |

### Limits worth knowing

- The git hooks only run after `git config core.hooksPath .githooks`; nothing forces that.
- The Claude hooks only see commands Claude runs, not commands you type. They only read the command line, so the contents of a file passed with `--body-file` are not scanned for attribution.
- Hook matchers match the tool name only, so the scripts filter the command text themselves. They join backslash-newline continuations, drop shell comments, and mask quoted strings and heredoc bodies (a `$(...)` inside double quotes is read as a command again), so text that merely mentions a command is not mistaken for one. They recognise gh behind shell keywords (`then`, `do`, `!`, `{`), env assignments, wrappers (`env`, `sudo`, `timeout`, `nice`, `nohup`, `eval`), `-R`/`--repo` between a group and its subcommand, and a quoted `"gh"`, and a path or backslash (`/usr/bin/gh`, `\gh`).
- Out of scope, and not caught: gh inside `bash -c "..."` or `eval "..."` (the string is opaque), backtick substitution inside double quotes, shell variables or functions that expand to gh, and gh aliases.
- Without `jq` and `python3` the hooks run degraded: they warn on stderr, fail closed for `gh pr merge`, and scan the raw event for attribution.
- The hooks are written for bash 3.2 and newer (macOS default) and POSIX sh for the git hooks.
- `.gitattributes` forces LF on `*.sh` and `.githooks/*`; a CRLF hook would break every commit once `core.hooksPath` is set.

## Install the git hooks

Git does not run hooks from a tracked folder by default. Once per clone:

```
git config core.hooksPath .githooks
```

## Reviewing PRs that touch this folder

`CLAUDE.md`, hooks, skills and commands in `.claude/` and `.githooks/` steer or run on your machine as soon as you check the branch out and start Claude or commit. Review them before you do. `gh pr diff <n>` takes no path filter, so list the files with `gh pr diff <n> --name-only`, then read just those paths without checking the branch out: `git fetch origin pull/<n>/head && git diff origin/main...FETCH_HEAD -- .claude .githooks CLAUDE.md`. `.github/CODEOWNERS` asks the owner to review any change to `.claude/`, `.githooks/`, `.github/`, `.gitattributes` and `CLAUDE.md`.

## What stays personal

Anything specific to one person's machine lives in `~/.claude` (or `.claude/settings.local.json`, which is ignored), not here: personal memory and preferences, global hooks, credentials and cloud logins, editor and terminal setup, and per-machine paths. Nothing in this folder should contain absolute home paths, machine names, addresses, account ids or email addresses.
