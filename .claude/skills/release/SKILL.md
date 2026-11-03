---
name: release
description: Cut a version of the mod. Releases are driven by release-please; this covers how commit titles decide the version, checking and merging the release PR, watching the release workflow, verifying the GitHub Release and CurseForge file, and recovery. Use when the owner asks for a release or a release PR is open.
---

# Release

Releases come from release-please. Nobody bumps `mod_version` or writes `CHANGELOG.md` by hand (the one exception is creating a Minecraft-version branch, see `CLAUDE.md`): both are generated from the conventional commits on `main`. A release is cut by merging the release PR that release-please keeps open. Merging it is the release; never tag by hand.

## What decides the version

Squash merges keep only the PR title (the subject) and the merge body (`--body-file`). The commit subjects inside a PR, builder commits included, never reach `main`. So the PR title must be a conventional-commit subject, and it becomes the changelog line. While the version is 0.x:

| Subject on `main` | Effect |
|---|---|
| `feat: ...` | minor bump (0.2.0 to 0.3.0) |
| `fix: ...`, `perf: ...` | patch bump (0.2.0 to 0.2.1) |
| breaking: `feat!:`/`fix!:` (any type with `!`, including `chore!:`) and/or a `BREAKING CHANGE:` footer | minor bump while 0.x; it releases even for a type that is otherwise hidden |
| `revert: ...` | patch bump. Only a subject typed `revert: ...`; GitHub's Revert button writes `Revert "..."`, which does not release |
| `docs`, `ci`, `chore`, `test`, `refactor`, `build` | no release; hidden from the changelog |

Only a change that ends up in the jar should release: `src/`, resources, the `neoforge.mods.toml` template, or a dependency or build-version change. If such a change would normally carry a hidden type (a dependency floor raise, a build change that reaches the jar), type the PR `fix:` or `feat:` so it releases. Stay on 0.x until the Lua API is considered stable.

Footers: a `BREAKING CHANGE:` or `Release-As: x.y.z` footer must be the last paragraph of the merge `--body-file`, after one blank line. No other paragraph of that body may start with `feat:`, `fix:`, `perf:` or `revert:`, because release-please splits such paragraphs into extra commits.

On `main` (Minecraft 26.1.2) versions are plain (`0.3.0`, tag `v0.3.0`). Minecraft-version branches (`mc-26.2` and so on) are not covered by this automation yet; see "Multiple Minecraft versions" in `CLAUDE.md`.

New CHANGELOG entries use release-please's style (`## [x.y.z](compare-url) (date)`, `### Features`, `### Bug Fixes`) and sit above the older hand-written `## 0.2.0` and `## 0.1.0` sections. The "may affect existing scripts" warnings of the old format now come from `!` or a `BREAKING CHANGE:` footer.

## How it works

- `release-please.yml` runs on every push to `main`. It opens or updates one release PR (branch `release-please--branches--main`, title `chore: release X.Y.Z`) that bumps `mod_version` in `gradle.properties` and prepends a section to `CHANGELOG.md`. With only hidden types on `main`, no PR is opened.
- It acts as a GitHub App (token from `actions/create-github-app-token`), not as `GITHUB_TOKEN`, because events made by `GITHUB_TOKEN` start no workflows. So the release PR runs `build.yml` like any PR and gets the required `build` check on its head. The App only opens and updates the PR and creates the tag and Release; it approves nothing, so the owner still merges with `--admin`.
- `always-update: true` makes release-please re-push the release PR on every push to `main`, even for a docs-only change. That dismisses stale approvals and re-runs `build`, which is why the PR is always up to date with `main`.
- Merging the release PR makes release-please create the tag `vX.Y.Z` and the GitHub Release (notes = the new changelog section). The same run of `release-please.yml` then runs:
  - `jar` (calls `release.yml`): a read-only `build` job checks the tag equals `v` + `mod_version`, builds, and uploads the jar; a `publish` job with write access (no checkout, no Gradle) attaches it to the Release.
  - `release-notes`: if the `ANTHROPIC_API_KEY` secret exists, rewrites the Release body (plain-language "What's new" first, then Added / Fixed / Changed). The model comes from the `RELEASE_NOTES_MODEL` variable (default `claude-opus-5-5`). With no secret, on any failure, or if the rewrite drops a link or adds a URL, image, HTML tag or `@mention`, the release-please notes stay and the release is unaffected.
  - `curseforge` (calls `curseforge.yml`): runs when the jar was attached and the `CURSEFORGE_PROJECT_ID` variable is set (it is), even if `release-notes` was skipped or failed. It downloads the jar from the Release asset, uses the Release body as the changelog, checks `CURSEFORGE_TOKEN` is non-empty, and uploads with mc-publish (NeoForge, game version 26.1.2, required dependencies AE2 and CC: Tweaked).
- `release.yml` and `curseforge.yml` have no tag or release trigger, so the tag the App creates starts nothing by itself.

## One-time setup (owner only)

Create a GitHub App with Contents, Pull requests and Issues set to read and write, webhook off, installed only on this repository. Store its ID in the repository variable `RELEASE_APP_ID` and a generated private key in the repository secret `RELEASE_APP_PRIVATE_KEY`. Do not enable "Allow GitHub Actions to create and approve pull requests" in Settings > Actions > General: it would also let workflows approve PRs and satisfy the required review. These are repo settings; Claude does not change them.

## Steps

1. **Check the release PR.** `gh pr list --head release-please--branches--main`. Read the version and the changelog section. If the version or an entry is wrong, fix the cause on `main` (a better PR title next time, a `Release-As:` footer in a merge body), not by editing the release PR.
2. **Wait for `build`.** `gh pr checks <n>`: `build` must be green on the PR's head commit. If a push to `main` just updated the PR, wait for the new run. A manual run is `gh workflow run build.yml --ref release-please--branches--main`.
3. **The owner merges** (squash, admin, with subject and body file). Never merge without their explicit yes:
   ```
   gh pr merge <n> --squash --admin --subject "chore: release <version> (#<n>)" --body-file <file>
   ```
4. **Watch the workflow.** `gh run watch` needs a run id:
   ```
   id=$(gh run list --workflow release-please.yml -L1 --json databaseId -q '.[0].databaseId')
   gh run watch "$id"
   ```
5. **Verify:**
   - the tag `v<version>` and the GitHub Release exist, the jar is attached, and the notes read well (`gh release view v<version>`)
   - the `curseforge` job succeeded and the new file is on the CurseForge project with game version 26.1.2. CurseForge shows the environment as Client + Server (it cannot show "client optional"), so the description must say clients do not need the mod.
   - CC: Tweaked no longer publishes to CurseForge (its CurseForge page points to Modrinth). The CurseForge description must tell players to get CC: Tweaked for 26.1.2 from Modrinth; the dependency stays `required`.
6. **Pull `main`** and carry on. The release branch is deleted by the repo's delete-branch-on-merge setting; there is nothing to clean up.

## When the workflow fails

A published GitHub Release is never deleted or reused.

- **`curseforge` failed:** fix the cause (token, project id, CurseForge outage) and re-run only that failed job. Never re-run a successful `curseforge`: it uploads a duplicate file.
- **`jar` or `release-notes` failed:** re-run the failed job from the `Release Please` run. `jar` re-attaches the jar with `--clobber`, so it is safe to repeat.
- **release-please created the Release but its job failed afterwards** (so `jar`, `release-notes` and `curseforge` never ran): run `release.yml` from the Actions page (workflow_dispatch, input `tag` = `v<version>`), then `curseforge.yml` the same way. Both read the existing Release; `release.yml` creates one from the CHANGELOG section only if none exists.
- **`release-please` itself failed** (for example, the App token step): check the App variable and secret above, then re-run. After a failure between merging and tagging, re-running the workflow on `main` makes release-please create the missing tag and Release.
- **The GitHub Release is published but the jar or code is wrong:** leave the release, fix it with a `fix:` PR, and release the next patch through the new release PR.
