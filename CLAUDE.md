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
.github/workflows/      build.yml (every push and PR), release-please.yml (push to main: release PR, then the release), release.yml (jar for a tag), curseforge.yml (CurseForge upload for a tag)
release-please-config.json, .release-please-manifest.json   release-please settings and the last released version
```

## Build

`./gradlew build` with JDK 25. The jar lands in `build/libs/` and its name carries the Minecraft version (`appliedcc-neoforge-26.1.2-<mod_version>.jar`).

The dependency versions in `gradle.properties` (`neo_version`, `ae2_version`, `cc_version`) are the oldest each dependency is compiled against. `neoforge.mods.toml` declares them as minimums (`[x,)`). Do not bump one just to track the latest; raise a floor only when the code needs an API from the newer version, and say so in the PR body (and type the PR `fix:` or `feat:` so it releases).

## Versioning and release

Releases are automatic: release-please reads the conventional commits on `main`, picks the next version, keeps a release PR open, and the owner merges that PR to release. Nobody edits `mod_version` in `gradle.properties` or `CHANGELOG.md` by hand any more; release-please writes both (`mod_version` sits between `x-release-please-start-version` and `x-release-please-end` comment lines, which it uses to find it; leave those lines alone). The only time `mod_version` is edited by hand is when creating a Minecraft-version branch (below).

- **What reaches release-please.** Squash merges keep only the PR title (as the subject) and the merge body (`--body-file`); the commit subjects inside the PR, builder commits included, never reach `main`. So the PR title must be a conventional-commit subject: it becomes the changelog line.
- **The commit type decides the version.** While the version is 0.x: `feat` bumps minor, `fix` and `perf` bump patch, a breaking change bumps minor. Breaking means `feat!:`/`fix!:` (any type with `!`, including `chore!:`, still releases) and/or a `BREAKING CHANGE:` footer. `revert:` bumps patch, but only a subject typed `revert: ...`; GitHub's Revert button writes `Revert "..."`, which does not release. `docs`, `ci`, `chore`, `test`, `refactor` and `build` are hidden from the changelog and never open a release PR on their own. That matches "release only for a change that ends up in the jar": a change to `src/`, resources, the `neoforge.mods.toml` template, or a dependency or build-version change that reaches the jar must be typed `feat`, `fix` or `perf` (for example `fix: raise the AE2 floor to 26.1.13`). Stay on 0.x until the Lua API is considered stable.
- **Footers.** A `BREAKING CHANGE:` or `Release-As: x.y.z` footer must be the last paragraph of the merge `--body-file`, after one blank line. No other paragraph of that body may start with `feat:`, `fix:`, `perf:` or `revert:`: release-please would read it as an extra commit.
- **The release PR.** release-please keeps one open (branch `release-please--branches--main`, title `chore: release X.Y.Z`) and updates it as `main` moves. It does this with a GitHub App token, because events made by `GITHUB_TOKEN` start no workflows and the PR would never get its required `build` check; with the App token the PR runs `build.yml` like any PR. `always-update: true` makes release-please re-push the PR on every push to `main`, even a docs-only one, which dismisses stale approvals and re-runs `build`. The App approves nothing: the owner still merges the release PR with `gh pr merge <n> --squash --admin --subject "chore: release X.Y.Z (#<n>)" --body-file <file>` once `build` is green (see the `release` skill). The repo's delete-branch-on-merge setting removes the branch afterwards.
- **What the merge does.** release-please creates the tag `vX.Y.Z` and the GitHub Release (notes = the new CHANGELOG section), and `release-please.yml` then runs:
  - `jar` (calls `release.yml`): `build` job (`contents: read`: checks the tag equals `v` + `mod_version`, builds, uploads the jar) then `publish` job (`contents: write`: attaches the jar to the existing Release, no checkout, no Gradle).
  - `release-notes`: only if the `ANTHROPIC_API_KEY` secret exists (an environment secret of the `release` environment), rewrites the Release body with Claude (model from the `RELEASE_NOTES_MODEL` variable, default `claude-opus-5-5`). It keeps release-please's notes and never fails the release if anything goes wrong, or if the rewrite drops a link or adds a URL, image, HTML tag or `@mention`.
  - `curseforge` (calls `curseforge.yml`): needs `jar`, runs after `release-notes` whether or not it ran, and only when the `CURSEFORGE_PROJECT_ID` repo variable is set (it is). It takes the jar from the Release asset and the changelog from the Release body, checks that the `CURSEFORGE_TOKEN` secret is non-empty, and uploads with mc-publish.
  - `release.yml` and `curseforge.yml` have no tag or release trigger, so the tag the App creates starts nothing by itself.
- **One-time App setup (owner).** Create a GitHub App with Contents, Pull requests and Issues set to read and write, webhook off, installed only on this repository. Store its Client ID (App settings page, field "Client ID", starts with `Iv`) in the repository variable `RELEASE_APP_CLIENT_ID`. Create an environment named `release` (Settings > Environments) whose deployment branches are limited to `main`, and put the two release secrets in it as environment secrets: `RELEASE_APP_PRIVATE_KEY` (a generated private key of the App) and, optionally, `ANTHROPIC_API_KEY`. The two jobs that read them (`release-please` and `release-notes`) declare `environment: release`, so a run from any other branch cannot read them. `CURSEFORGE_TOKEN` stays a plain repository secret (a manual `curseforge.yml` run needs it) and `CURSEFORGE_PROJECT_ID` and `RELEASE_NOTES_MODEL` stay repository variables. A job with an environment shows up as a deployment on the commit or PR; that is expected and harmless. Do not enable "Allow GitHub Actions to create and approve pull requests" in Settings > Actions > General: it would also let workflows approve PRs and satisfy the required review. These are repo settings; Claude does not change them.
- **Recovery.** Once a GitHub Release is published it is never deleted or reused.
  - A failed `curseforge` job: re-run only that failed job (a successful run uploads a duplicate file if repeated).
  - A failed `jar` or `release-notes` job: re-run the failed job from the `Release Please` run. `jar` re-attaches the jar with `--clobber`.
  - release-please created the Release but its own job failed afterwards, so `jar`, `release-notes` and `curseforge` never ran: run `release.yml` (workflow_dispatch, input `tag`), then `curseforge.yml` (workflow_dispatch, input `tag`).
  - A code problem in a published release: fix it with a `fix:` commit and release the next patch.
- New CHANGELOG sections use release-please's style (`## [x.y.z](compare-url) (date)`, `### Features`, `### Bug Fixes`) above the older hand-written `## 0.1.0` and `## 0.2.0` sections. "May affect existing scripts" warnings now come from `!` or a `BREAKING CHANGE:` footer.
- Steps are in `.claude/skills/release/SKILL.md`.
- CurseForge shows the environment as Client + Server; it cannot show "client optional", so the description has to say clients do not need the mod.
- CC: Tweaked no longer publishes to CurseForge (its CurseForge page points to Modrinth). The CurseForge description must tell players to get CC: Tweaked for 26.1.2 from Modrinth. The dependency on it stays `required`.

## Multiple Minecraft versions

One branch per Minecraft version: `main` is 26.1.2, later ones are `mc-26.2` and so on. Fixes land on the oldest supported branch and are carried forward to the newer ones.

Jar names carry the Minecraft version, but tags and GitHub Release titles come from `mod_version`, so two branches releasing the same number would collide. The rule that avoids it: version branches carry the Minecraft version in `mod_version` as semver build metadata, for example `0.2.0+mc26.2`, tagged `v0.2.0+mc26.2`. `main` keeps plain versions (`0.2.0`). The release-please setup covers `main` only (`release-please.yml` runs on pushes to `main`). When you create a version branch, edit on that branch: `minecraft_version`, `minecraft_version_range`, the dependency floors, `mod_version`, and in `curseforge.yml` the `game-versions` and the CurseForge display `name:` line, and decide then how releases are cut there (it needs its own release-please config and workflow trigger for that branch, and the `+mcXX` build metadata does not come from conventional commits). Check on the first release from a new branch that the loader accepts a `+` in the mod version.

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
- Every Lua-visible change (new method, changed return, new filter field, new event) is documented in `README.md` (Methods, Filters, Coming from Advanced Peripherals as relevant) in the same PR. `CHANGELOG.md` is written by release-please from the PR title (the squash subject), so make the title a clear, user-facing line, and put details in the merge body (the release-notes job reads it). A change that may break existing scripts is breaking: `feat!:`/`fix!:` in the title and/or a `BREAKING CHANGE:` footer (last paragraph of the merge body) saying what changes.
- Failure returns `nil` / `0` / `false` plus a status string such as `NOT_CONNECTED`, `NOT_FOUND`, `INVENTORY_NOT_FOUND`. Throw `LuaException` only for bad arguments and for features the server config has turned off.

## Git and PR rules

- Never commit to `main`. Branches are `<type>/<slug>` where type is one of `feat`, `fix`, `chore`, `docs`, `refactor`, `ci`, `build`, `test`, `release`, `perf`.
- Commit messages are conventional commits. The PR title must be one too: a squash merge keeps only the title (as the subject) and the merge body, and release-please reads those to pick the version and write the changelog line. Commit subjects inside a PR do not reach `main`.
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
