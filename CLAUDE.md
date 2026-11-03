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
.github/workflows/      build.yml (every push and PR), release.yml (tag push)
```

## Build

`./gradlew build` with JDK 25. The jar lands in `build/libs/` and its name carries the Minecraft version (`appliedcc-neoforge-26.1.2-<mod_version>.jar`).

The dependency versions in `gradle.properties` (`neo_version`, `ae2_version`, `cc_version`) are the oldest each dependency is compiled against. `neoforge.mods.toml` declares them as minimums (`[x,)`). Do not bump one just to track the latest; raise a floor only when the code needs an API from the newer version, and say so in the PR and CHANGELOG.

## Versioning and release

- Semver in `mod_version` (`gradle.properties`). Stay on 0.x until the Lua API is considered stable.
- Every release needs a `## X.Y.Z` section in `CHANGELOG.md`.
- Pushing tag `vX.Y.Z` (it must equal `v` + `mod_version`) runs `release.yml`: it builds and publishes a GitHub Release using that changelog section as the notes.
- CurseForge upload is not in `release.yml` on `main` yet. It is the open PR #2 (`ci/curseforge-publish`). Once that CurseForge step is merged, it uploads when the `CURSEFORGE_PROJECT_ID` repo variable is set (token in the `CURSEFORGE_TOKEN` secret). Until then, a CurseForge file is uploaded by hand.
- Steps are in `.claude/skills/release/SKILL.md`.

## Multiple Minecraft versions

One branch per Minecraft version: `main` is 26.1.2, later ones are `mc-26.2` and so on. Fixes land on the oldest supported branch and are carried forward to the newer ones.

Jar names carry the Minecraft version, but tags and GitHub Release titles come from `mod_version`, so two branches releasing the same number would collide. The rule that avoids it: version branches carry the Minecraft version in `mod_version` as semver build metadata, for example `0.2.0+mc26.2`, tagged `v0.2.0+mc26.2` (and a `## 0.2.0+mc26.2` changelog heading). `main` keeps plain versions (`0.2.0`). When you create a version branch, edit on that branch: `minecraft_version`, `minecraft_version_range`, the dependency floors, `mod_version`, and, once PR #2's CurseForge step exists, its `game-versions` and file name in `release.yml`. Check on the first release from a new branch that the loader accepts a `+` in the mod version.

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
- Every Lua-visible change (new method, changed return, new filter field, new event) is documented in `README.md` (Methods, Filters, Coming from Advanced Peripherals as relevant) and `CHANGELOG.md` in the same PR.
- Failure returns `nil` / `0` / `false` plus a status string such as `NOT_CONNECTED`, `NOT_FOUND`, `INVENTORY_NOT_FOUND`. Throw `LuaException` only for bad arguments and for features the server config has turned off.

## Git and PR rules

- Never commit to `main`. Branches are `<type>/<slug>` where type is one of `feat`, `fix`, `chore`, `docs`, `refactor`, `ci`, `build`, `test`, `release`, `perf`.
- Commit messages are conventional commits.
- Squash merges only, with an explicit subject and body.
- Branch protection on `main` requires a PR, one approving review, a passing `build` check, and an up-to-date branch; only squash merges are allowed. `gh pr merge --admin` bypasses those checks. It is owner-only and is used only after `build` is green on a branch that is up to date with `main` (merge or rebase `main` in and re-run CI if it is behind).
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
