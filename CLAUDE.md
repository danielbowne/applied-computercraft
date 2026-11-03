# Applied ComputerCraft

Project rules for anyone working here, human or Claude. Read this before changing code.

## What this is

A small NeoForge mod for Minecraft 26.1.2 (Java 25, ModDevGradle) that adds a CC:Tweaked generic peripheral, `me_bridge`, to every AE2 block that hosts a grid node. There are no blocks, items or registry entries of its own.

Targets: Minecraft 26.1.2, NeoForge 26.1.2.94+, AE2 26.1.10-beta+, CC:Tweaked 1.118.0+.

```
src/main/java/io/github/danielbowne/appliedcc/
  AppliedCC.java        mod entry point: registers config, the generic source, tick and stop hooks
  Config.java           server config (config/appliedcc-server.toml) and the require() gate
  CraftJobs.java        per-computer pending crafting requests and the me_crafting event
  MeBridgeMethods.java  every Lua-visible method, filter parsing, export and import
src/main/templates/META-INF/neoforge.mods.toml   mod metadata, filled from gradle.properties
.github/workflows/      build.yml (every push and PR), release-please.yml (push to main: release PR, then the release), release.yml (jar for a tag)
release-please-config.json, .release-please-manifest.json   release-please settings and the last released version
```

## Build

`./gradlew build` with JDK 25. The jar lands in `build/libs/` and its name carries the Minecraft version (`appliedcc-neoforge-26.1.2-<mod_version>.jar`).

The dependency versions in `gradle.properties` (`neo_version`, `ae2_version`, `cc_version`) are the oldest each dependency is compiled against. `neoforge.mods.toml` declares them as minimums (`[x,)`). Do not bump one just to track the latest; raise a floor only when the code needs an API from the newer version, and say so in the PR and the commit message (use a `fix:` or `feat:` type so it releases).

## Versioning and release

Releases are automatic: release-please reads the conventional commits on `main`, picks the next version, keeps a release PR open, and the owner merges that PR to release. Nobody edits `mod_version` in `gradle.properties` or `CHANGELOG.md` by hand any more; release-please writes both (`mod_version` sits between `x-release-please-start-version` and `x-release-please-end` comment lines, which it uses to find it; leave those lines alone).

- The commit type decides the version. Squash merges use the PR title as the commit subject, so the PR title must be a conventional commit. While the version is 0.x: `feat` bumps minor, `fix` and `perf` bump patch, a breaking change (`feat!:` or a `BREAKING CHANGE:` footer) also bumps minor. `docs`, `ci`, `chore`, `test`, `refactor` and `build` are hidden from the changelog and never open a release PR on their own. That matches "release only for a change that ends up in the jar": a change to `src/`, resources, the `neoforge.mods.toml` template, or a dependency or build-version change that reaches the jar must be committed as `feat`, `fix` or `perf` (for example `fix: raise the AE2 floor to 26.1.13`). Stay on 0.x until the Lua API is considered stable.
- `Release-As: x.y.z` in a commit body forces a version when needed.
- release-please keeps one release PR open (branch `release-please--branches--main`, title `chore: release X.Y.Z`) and updates it as `main` moves. A PR made with the workflow token does not start other workflows, so `release-please.yml` dispatches `build.yml` on that branch; the dispatched `build` run is the required check on the PR.
- The owner merges the release PR once its `build` is green, with `gh pr merge <n> --squash --admin --subject "chore: release X.Y.Z (#<n>)" --body-file <file>` (see the `release` skill). The merge makes release-please create the tag `vX.Y.Z` and the GitHub Release (notes = the new CHANGELOG section), and `release-please.yml` then runs:
  - `jar` (calls `release.yml`): checks the tag equals `v` + `mod_version`, builds, and attaches the jar to the existing Release.
  - `release-notes`: only if the `ANTHROPIC_API_KEY` secret exists, rewrites the Release body with Claude (model from the `RELEASE_NOTES_MODEL` variable, default `claude-opus-5-5`). Any failure keeps release-please's notes and never fails the release.
  - `curseforge`: needs `jar`, runs after `release-notes` whether or not it ran, and only when the `CURSEFORGE_PROJECT_ID` repo variable is set (it is). It checks that the `CURSEFORGE_TOKEN` secret is non-empty and uploads the jar with mc-publish, using the final Release body as the changelog.
- Repo setting the owner must enable once: Settings > Actions > General > "Allow GitHub Actions to create and approve pull requests". Without it release-please cannot open the release PR.
- Recovery: re-run the failed jobs of the `Release Please` run from the Actions page (a failed `curseforge` alone is safe to re-run). To rebuild and re-attach the jar of an existing tag, run `release.yml` from the Actions page (workflow_dispatch) with the tag. Once a GitHub Release is published it is never deleted or reused; for a code problem, fix it with a `fix:` commit and release the next patch.
- The existing `## 0.1.0` and `## 0.2.0` CHANGELOG sections were hand-written; release-please adds its own `## [x.y.z](compare-url) (date)` sections above them.
- Steps are in `.claude/skills/release/SKILL.md`.
- CurseForge shows the environment as Client + Server; it cannot show "client optional", so the description has to say clients do not need the mod.
- CC: Tweaked no longer publishes to CurseForge (its CurseForge page points to Modrinth). The CurseForge description must tell players to get CC: Tweaked for 26.1.2 from Modrinth. The dependency on it stays `required`.

## Multiple Minecraft versions

One branch per Minecraft version: `main` is 26.1.2, later ones are `mc-26.2` and so on. Fixes land on the oldest supported branch and are carried forward to the newer ones.

Jar names carry the Minecraft version, but tags and GitHub Release titles come from `mod_version`, so two branches releasing the same number would collide. The rule that avoids it: version branches carry the Minecraft version in `mod_version` as semver build metadata, for example `0.2.0+mc26.2`, tagged `v0.2.0+mc26.2`. `main` keeps plain versions (`0.2.0`). The release-please setup covers `main` only (`release-please.yml` runs on pushes to `main`). When you create a version branch, edit on that branch: `minecraft_version`, `minecraft_version_range`, the dependency floors, `mod_version`, and in the `curseforge` job of `release-please.yml` the `game-versions` and the CurseForge display `name:` line, and decide then how releases are cut there (it needs its own release-please config and workflow trigger for that branch, and the `+mcXX` build metadata does not come from conventional commits). Check on the first release from a new branch that the loader accepts a `+` in the mod version.

## Code rules

These were learned the hard way.

- **Every `@LuaFunction` that touches AE2 uses `mainThread = true`.** AE2 grids and block entities are not thread-safe, and CC calls Lua functions from its own computer thread otherwise.
- **Never call AE2 `MEStorage` insert or extract while one of our NeoForge `Transaction`s is open.** AE2's storage bus opens its own root transaction, and NeoForge forbids two root transactions on one thread. Before this was understood it caused item duplication. Move each side in its own step (see the comment above `export` in `MeBridgeMethods`).
- **Every move hands any shortfall back to where it came from, in a `finally`.** If the second half of a move comes up short or throws, the first half must be returned.
- **Log, never silently drop, anything that cannot be returned.** A `LOG.warn` naming the amount and the resource id.
- **The mod adds nothing to registries or network payloads, so clients do not need it.** The README, `neoforge.mods.toml` and the CurseForge listing all promise this. Adding a registry entry or a payload breaks the promise and needs README, mods.toml (sides) and CurseForge environment changes in the same PR.
- **New features that move items or can affect other players get a switch in `Config.java`** (checked through `Config.require`), default on, documented in the README config table. Config lives in `config/appliedcc-server.toml`.

## Lua API rules

- Method names and argument order follow Advanced Peripherals' ME Bridge where the two overlap. Differences are listed under "Coming from Advanced Peripherals" in the README.
- Every Lua-visible change (new method, changed return, new filter field, new event) is documented in `README.md` (Methods, Filters, Coming from Advanced Peripherals as relevant) in the same PR. `CHANGELOG.md` is written by release-please from commit subjects, so make the PR title a clear, user-facing line, and put details in the commit body (the release-notes job reads the bodies). A change that may break existing scripts is a breaking commit: `feat!:` or `fix!:` plus a `BREAKING CHANGE:` footer saying what changes.
- Failure returns `nil` / `0` / `false` plus a status string such as `NOT_CONNECTED`, `NOT_FOUND`, `INVENTORY_NOT_FOUND`. Throw `LuaException` only for bad arguments and for features the server config has turned off.

## Git and PR rules

- Never commit to `main`. Branches are `<type>/<slug>` where type is one of `feat`, `fix`, `chore`, `docs`, `refactor`, `ci`, `build`, `test`, `release`, `perf`.
- Commit messages are conventional commits, and so are PR titles (squash merges use the title as the commit subject, and release-please reads it to pick the version).
- Squash merges only, with an explicit subject and body.
- Branch protection on `main` requires a PR, one approving review, a passing `build` check, an up-to-date branch, resolved conversations and linear history; only squash merges are allowed. `gh pr merge --admin` bypasses all of those, including unresolved conversations. It is owner-only and is used only after `build` is green on a branch that is up to date with `main` (update the branch and re-run CI if it is behind) and after conversations are resolved.
- No AI attribution anywhere: commits, PRs, issues, comments, release notes. No `Co-Authored-By` for tools, no session links, no "generated with" lines. Enforcement is partial: the git hooks in `.githooks/` are opt-in (`git config core.hooksPath .githooks`), the Claude hooks only see commands Claude runs, and `pre-commit` refuses `main` only (version branches such as `mc-26.2` are allowed by design). See `.claude/README.md` for what each gate covers.
- Regular hyphens, never em dashes, in prose.
- PR bodies follow `.claude/skills/pr-description/SKILL.md`.

## Process

The flow is in `.claude/skills/ship-flow/SKILL.md`. Builders (`.claude/agents/builder.md`) commit and stop. The session that pushes runs the pre-push review (`.claude/skills/pre-push-review/SKILL.md`) first. `/ship-it` walks a branch through all of it.

## Testing reality

There are no unit tests. The gate is `./gradlew build` (it compiles against AE2 and CC:Tweaked and fills mods.toml). Behaviour changes must also be verified on a real NeoForge server (`./gradlew runServer`, or a dev server with the jar) with a CC computer next to an AE2 block. The kind of check that counts:

- list: `getItems`, `getItem`, `getFluids` against a network with known contents
- export then import round trip: move a counted amount to a chest and back, and confirm nothing was gained or lost, including with a full target and a storage bus on the same network
- craft then cancel: `craftItem`, watch the `me_crafting` event and `isCrafting`, then `cancelCraftingTasks`

State in the PR what was exercised and what was not.
