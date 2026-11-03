---
name: release
description: Cut a version of the mod (bump mod_version, write the CHANGELOG section, PR, tag the merge commit, watch release.yml, verify the GitHub Release and CurseForge file). Use when the owner asks for a release or a release PR is being prepared.
---

# Release

A release is a normal PR followed by a tag. Versions are semver in `mod_version`; stay on 0.x until the Lua API is considered stable.

**Only cut a release for a change that ends up in the jar:** `src/`, resources, the `neoforge.mods.toml` template, or a dependency or build-version change. CI, docs, README and `.claude/` changes merge to `main` with no version bump and no tag.

On `main` (Minecraft 26.1.2) versions are plain: `0.2.0`, tag `v0.2.0`. On a Minecraft-version branch (`mc-26.2` and so on) the Minecraft version is carried as semver build metadata so tags and Release titles never collide with `main`: `0.2.0+mc26.2`, tag `v0.2.0+mc26.2`, changelog heading `## 0.2.0+mc26.2`. A new version branch also needs `minecraft_version`, `minecraft_version_range` and the dependency floors edited on that branch, and in the `curseforge` job of `release.yml` the `game-versions` and the CurseForge display `name:` line.

## What release.yml does

Pushing tag `v<version>` runs two jobs:

- `release`: builds, checks the tag equals `v` + `mod_version`, extracts the `## <version>` section of `CHANGELOG.md` (fails if missing or empty), creates the GitHub Release with the jar (skipped if the release already exists, so re-runs are safe), and uploads the jar and notes as an artifact.
- `curseforge`: needs `release`, runs only when the `CURSEFORGE_PROJECT_ID` repo variable is set (it is), checks the `CURSEFORGE_TOKEN` secret is non-empty, and uploads the jar with mc-publish (NeoForge, game version 26.1.2, required dependencies AE2 and CC: Tweaked).

## Steps

1. **Branch from fresh origin/main** (or the version branch): `release/<version>`.
2. **Bump** `mod_version` in `gradle.properties`.
3. **Add a `## <version>` section to `CHANGELOG.md`**, newest first, in the style of the existing entries.
4. **Gates:** `./gradlew build`. Confirm the jar name carries the new version.
5. **PR** per the `ship-flow` and `pr-description` skills. Title `chore: release <version>`. The pre-push review runs as for any push.
6. **The owner merges** (squash, with subject and body file). Never merge without their explicit yes.
7. **Tag the PR's merge commit**, not whatever the branch tip is now:
   ```
   git fetch origin
   sha=$(gh pr view <n> --json mergeCommit -q .mergeCommit.oid)
   git tag -a v<version> -m "Applied ComputerCraft <version>" "$sha"
   git push origin v<version>
   ```
8. **Watch the workflow.** `gh run watch` needs a run id:
   ```
   id=$(gh run list --workflow release.yml -L1 --json databaseId -q '.[0].databaseId')
   gh run watch "$id"
   ```
9. **Verify:**
   - the GitHub Release exists, has the jar attached, and its notes are the changelog section (`gh release view v<version>`)
   - the `curseforge` job succeeded and the new file is on the CurseForge project with game version 26.1.2. CurseForge shows the environment as Client + Server (it cannot show "client optional"), so the description must say clients do not need the mod.
   - CC: Tweaked no longer publishes to CurseForge (its CurseForge page points to Modrinth). The CurseForge description must tell players to get CC: Tweaked for 26.1.2 from Modrinth; the dependency stays `required`.
10. **Clean up** the release branch per the `ship-flow` cleanup step.

## When the workflow fails

A published GitHub Release is never deleted or reused.

- Only the `curseforge` job failed: fix the cause (token, project id, CurseForge outage) and re-run that job from the Actions page. No new version.
- The `release` job failed before the GitHub Release exists: fix the cause. If it was in the repo, the tag can be deleted (`git push origin :refs/tags/v<version>` and `git tag -d v<version>`) and pushed again on the same commit, only with the owner's say-so; if the fix needs a new commit, delete the tag and tag the new merge commit.
- The GitHub Release is published but the jar or code is wrong: leave the release, bump the patch version, and release again.
