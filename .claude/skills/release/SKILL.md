---
name: release
description: Cut a version of the mod. Releases are driven by release-please; this covers checking the release PR, merging it, watching the release workflow, verifying the GitHub Release and CurseForge file, and recovery. Use when the owner asks for a release or a release PR is open.
---

# Release

Releases come from release-please. Nobody bumps `mod_version` or writes `CHANGELOG.md` by hand: both are generated from the conventional commits on `main`. A release is cut by merging the release PR that release-please keeps open.

## What decides the version

The commit type (on `main`, squash merges use the PR title as the subject), while the version is 0.x:

| Commit | Effect |
|---|---|
| `feat` | minor bump (0.2.0 to 0.3.0) |
| `fix`, `perf` | patch bump (0.2.0 to 0.2.1) |
| breaking (`feat!:`, `fix!:`, or a `BREAKING CHANGE:` footer) | minor bump while 0.x |
| `revert` | patch bump |
| `docs`, `ci`, `chore`, `test`, `refactor`, `build` | no release; hidden from the changelog |

Only a change that ends up in the jar should release: `src/`, resources, the `neoforge.mods.toml` template, or a dependency or build-version change. If such a change would normally carry a hidden type (a dependency floor raise, a build change that reaches the jar), commit it as `fix:` or `feat:` so it releases. `Release-As: x.y.z` in a commit body forces a specific version. Stay on 0.x until the Lua API is considered stable.

On `main` (Minecraft 26.1.2) versions are plain (`0.3.0`, tag `v0.3.0`). Minecraft-version branches (`mc-26.2` and so on) are not covered by this automation yet; see "Multiple Minecraft versions" in `CLAUDE.md`.

## How it works

- `release-please.yml` runs on every push to `main`. It opens or updates one release PR (branch `release-please--branches--main`, title `chore: release X.Y.Z`) that bumps `mod_version` in `gradle.properties` and prepends a section to `CHANGELOG.md`. With only hidden commit types on `main`, no PR is opened.
- A PR made with the workflow token does not trigger other workflows, so the workflow dispatches `build.yml` on the release branch every time it updates the PR. That `build` run is the required check on the PR head.
- Merging the release PR makes release-please create the tag `vX.Y.Z` and the GitHub Release (notes = the new changelog section). The same run of `release-please.yml` then runs:
  - `jar` (calls `release.yml`): checks the tag equals `v` + `mod_version`, builds, attaches the jar to the Release.
  - `release-notes`: if the `ANTHROPIC_API_KEY` secret exists, rewrites the Release body (plain-language "What's new" first, then Added / Fixed / Changed). The model comes from the `RELEASE_NOTES_MODEL` variable (default `claude-opus-5-5`). With no secret, or on any failure, the release-please notes stay and the release is unaffected.
  - `curseforge`: runs when the jar exists and the `CURSEFORGE_PROJECT_ID` variable is set (it is). Checks `CURSEFORGE_TOKEN` is non-empty and uploads the jar with mc-publish (NeoForge, game version 26.1.2, required dependencies AE2 and CC: Tweaked), using the final Release body as the changelog. It runs even when `release-notes` was skipped.

One-time repo setting, owner only: Settings > Actions > General > "Allow GitHub Actions to create and approve pull requests". Without it release-please cannot open the PR. The secrets and variables above are repo settings too; Claude does not change them.

## Steps

1. **Check the release PR.** `gh pr list --head release-please--branches--main`. Read the version and the changelog section; if the version or an entry is wrong, fix the cause on `main` (a better commit, a `Release-As:` footer), not by editing the release PR.
2. **Wait for `build`.** `gh pr checks <n>`: the dispatched `build` run must be green on the PR's head commit. If the check is missing, dispatch it: `gh workflow run build.yml --ref release-please--branches--main`.
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
6. **Pull `main`** and carry on; there is no release branch to clean up (release-please deletes its own).

## When the workflow fails

A published GitHub Release is never deleted or reused.

- Re-run the failed jobs of the `Release Please` run from the Actions page. `curseforge` on its own is safe to re-run; so is `jar` (the jar is re-attached with `--clobber`).
- To rebuild and re-attach the jar of an existing tag outside that run, run `release.yml` from the Actions page with the tag (`v<version>`). It creates the Release with the changelog section as notes if none exists.
- `release-please` itself failed (for example, it could not open the PR): check the repo setting above, then re-run. After a failure between merging and tagging, re-running the workflow on `main` makes release-please create the missing tag and Release.
- The GitHub Release is published but the jar or code is wrong: leave the release, fix it with a `fix:` commit, and release the next patch through the new release PR.
