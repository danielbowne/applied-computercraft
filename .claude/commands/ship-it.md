---
description: Walk the current branch through review, push, PR, CI, and (with the owner's yes) squash merge and cleanup
---

Walk the current branch through the `ship-flow` skill (`.claude/skills/ship-flow/SKILL.md`). Stop and report at the first failing step.

1. **Preconditions.** Get the branch with `git branch --show-current`. Refuse if it is `main`. Refuse if `git status --short` shows uncommitted changes (tell the owner to commit or stash first).
2. **Pre-push review.** Run the `pre-push-review` skill on `origin/main...HEAD`. Skip it only if it already ran on this exact diff in this session (same HEAD) or the owner waived it for this push. Fix findings by default, ask only about product decisions or findings you believe are wrong, and commit the fixes.
3. **Push.** `git push -u origin <branch>`, then verify with `git ls-remote --heads origin <branch>` that the remote head matches `git rev-parse HEAD`.
4. **PR.** Look for an existing one with `gh pr list --head <branch> --json number,url,state`. If none, create it: title is the conventional-commit subject, under 70 characters; body written per the `pr-description` skill and passed with `--body-file`. If one exists, update its body if the change warrants it.
5. **CI.** Wait with `gh pr checks <n> --watch`. If a check fails, show the failing log excerpt and stop.
6. **Ask the owner** with AskUserQuestion: merge now, or leave the PR open. Do not merge without a yes.
7. **On yes, merge:** `gh pr merge <n> --squash --admin --subject "<subject> (#<n>)" --body-file <file>`. The body file is a short written summary of the change (the PR body is fine if it reads as a commit body).
8. **Clean up.** `git fetch origin --prune`, switch off the branch (`git switch main` for reading only), `git branch -D <branch>` (squash merges look unmerged), and remove any worktree for it. If it was a release PR, point the owner at the `release` skill for tagging.
9. **Report:** branch, PR number and URL, review outcome, CI result, merge state, and what was cleaned up.
