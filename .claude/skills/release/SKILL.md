---
name: release
description: Cut a version of the mod (bump mod_version, write the CHANGELOG section, PR, tag the merge commit, watch release.yml, verify the assets). Use when the owner asks for a release or a release PR is being prepared.
---

# Release

A release is a normal PR followed by a tag. Versions are semver in `mod_version`; stay on 0.x until the Lua API is considered stable. On multi-version setups, do this on the branch for the Minecraft version being released (`main` for 26.1.2).

## Steps

1. **Branch from fresh origin/main** (or the Minecraft-version branch): `release/<X.Y.Z>`.
2. **Bump** `mod_version` in `gradle.properties` to `X.Y.Z`.
3. **Add a `## X.Y.Z` section to `CHANGELOG.md`**, newest first, in the style of the existing entries. `release.yml` pulls exactly this section for the GitHub Release notes and fails if it is empty or missing.
4. **Gates:** `./gradlew build`. Confirm the jar name carries the new version.
5. **PR** per the `ship-flow` and `pr-description` skills. Title `chore: release X.Y.Z`. The pre-push review runs as for any push.
6. **The owner merges** (squash, with subject and body file). Never merge without their explicit yes.
7. **Tag the merge commit** once the branch is merged and your checkout is on the updated base:
   ```
   git fetch origin
   git tag -a vX.Y.Z -m "Applied ComputerCraft X.Y.Z" origin/main
   git push origin vX.Y.Z
   ```
   The tag must equal `mod_version`, or `release.yml` fails its first check. Use the Minecraft-version branch ref instead of `origin/main` when releasing from one.
8. **Watch the workflow:** `gh run watch` on the Release run for the tag.
9. **Verify:**
   - the GitHub Release exists, has the jar attached, and its notes are the changelog section (`gh release view vX.Y.Z`)
   - if the `CURSEFORGE_PROJECT_ID` repo variable is set, the new file appears on the CurseForge project with the right game version and the client/server environment still as the README promises (server only needed, clients optional)
10. **Clean up** the release branch locally (`git branch -D`, squash merges look unmerged).

If the workflow fails after the tag is pushed, fix forward: do not move or reuse a published tag. Delete the tag and release only with the owner's say-so, then retag.
