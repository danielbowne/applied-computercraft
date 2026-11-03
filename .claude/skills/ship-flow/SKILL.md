---
name: ship-flow
description: The end-to-end order for getting a change from idea to merged on this repo (branch, build, gates, pre-push review, push, PR, owner merge, cleanup, release). Use whenever you are about to start, push, or merge a change here.
---

# Ship flow

Rules that hold at every step:

- Never commit to `main`.
- Never push before the pre-push review has run (docs-only changes included, unless the owner waives it for that push).
- Never merge without the owner's explicit yes in this session.

## Order

1. **Branch from fresh origin/main.** `git fetch origin && git switch -c <type>/<slug> origin/main`. Types are conventional-commit types (`feat`, `fix`, `chore`, `docs`, `refactor`). For parallel work, use a worktree under `.claude/worktrees/` (`git worktree add .claude/worktrees/<slug> -b <type>/<slug> origin/main`); that folder is git-ignored.
2. **Builder.** Hand a self-contained brief to the `builder` agent (`.claude/agents/builder.md`) or do the work directly. The builder commits on the branch and stops; it does not push.
3. **Gates.** `./gradlew build` with JDK 25. Behaviour changes also get verified on a real NeoForge server with a CC computer next to an AE2 block (see "Testing reality" in `CLAUDE.md`). Lua-visible changes must already carry README and CHANGELOG edits.
4. **Pre-push review and fixes.** Run the `pre-push-review` skill on the outgoing diff. Fix its findings by default; ask the owner only about product decisions or findings you believe are wrong.
5. **Push.** `git push -u origin <branch>`, then confirm with `git ls-remote --heads origin <branch>`.
6. **PR.** Create it with a conventional-commit title under 70 characters and a body written per the `pr-description` skill (long bodies via `--body-file`). Wait for CI (`gh pr checks <n> --watch`).
7. **Wait for the owner.** Present the PR and ask whether to merge. Do not merge on your own.
8. **Owner merges, squash only, with an explicit subject and body file:**
   `gh pr merge <n> --squash --admin --subject "<subject> (#<n>)" --body-file <file>`
   The `permissions.ask` entry and the `guard-squash-merge` hook both exist to catch a squash without a written body.
9. **Clean up.** `git switch main` is fine for reading, never for committing. Then `git fetch origin --prune`, delete the local branch with `git branch -D <branch>` (capital D: a squash merge never looks merged to git), and remove any worktree (`git worktree remove .claude/worktrees/<slug>`).
10. **Release, if this was a release PR.** Follow the `release` skill: tag the merge commit and watch `release.yml`.

`/ship-it` runs steps 4 to 9 for the current branch.
