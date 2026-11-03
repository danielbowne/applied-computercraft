---
name: release
description: Cut a version of the mod (bump mod_version, write the CHANGELOG section, PR, tag the merge commit, watch release.yml, verify the assets). Use when the owner asks for a release or a release PR is being prepared.
---

# Release

A release is a normal PR followed by a tag. Versions are semver in `mod_version`; stay on 0.x until the Lua API is considered stable.

On `main` (Minecraft 26.1.2) versions are plain: `0.2.0`, tag `v0.2.0`. On a Minecraft-version branch (`mc-26.2` and so on) the Minecraft version is carried as semver build metadata so tags and Release titles never collide with `main`: `0.2.0+mc26.2`, tag `v0.2.0+mc26.2`, changelog heading `## 0.2.0+mc26.2`. A new version branch also needs `minecraft_version`, `minecraft_version_range`, the dependency floors and, once PR #2's CurseForge step is merged, its `game-versions` and file name edited on that branch.

## Steps

1. **Branch from fresh origin/main** (or the version branch): `release/<version>`.
2. **Bump** `mod_version` in `gradle.properties`.
3. **Add a `## <version>` section to `CHANGELOG.md`**, newest first, in the style of the existing entries. `release.yml` pulls exactly this section for the GitHub Release notes and fails if it is empty or missing.
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
   The tag must equal `v` + `mod_version`, or `release.yml` fails its first check.
8. **Watch the workflow:** `gh run watch` on the Release run for the tag.
9. **Verify:**
   - the GitHub Release exists, has the jar attached, and its notes are the changelog section (`gh release view v<version>`)
   - once PR #2's CurseForge step is merged and the `CURSEFORGE_PROJECT_ID` repo variable is set, the new file appears on the CurseForge project with the right game version and the environment still as the README promises (server only needed, clients optional); before that, upload the jar there by hand
10. **Clean up** the release branch per the `ship-flow` cleanup step (check `MERGED` and the head SHA, then `git branch -D`).

## When the workflow fails

A published tag or release is never reused.

- If `release.yml` fails after the tag is pushed and before anything was published (no GitHub Release, no CurseForge file), you may delete the tag (`git push origin :refs/tags/v<version>` and `git tag -d v<version>`) and push it again on the same commit once the cause is fixed. Do this only with the owner's say-so.
- If anything was published, leave it, bump the patch version, and release again.
