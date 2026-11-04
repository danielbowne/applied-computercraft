---
name: pr-description
description: How to write a PR body for this repo (result first, mechanism before fix, riders labeled, testing stated, merge notes last). Use whenever you create or edit a pull request.
---

# PR description

Scale it to the change: a one-line fix gets one paragraph, not the full skeleton.

## Shape

1. **Result first.** The first sentence says what the PR does and what it rests on. Put `Closes #N` up top when true. Measurements go in a small before/after table, not buried in prose.
2. **Mechanism before fix.** One paragraph on why the problem happens (confirmed, not guessed), then the fix, and why this shape of fix. Name the alternatives considered and why they lost, briefly.
3. **Riders labeled as riders.** Anything that hitches along beyond the headline gets its own short section saying exactly what it is and whether it changes behaviour.
4. **Testing states what ran and what deliberately did not.** Say what was exercised on a real server (list, export and import round trip, craft and cancel) and what was not. Absence of work is stated, not implied.
5. **Merge and deploy notes last**, when the merge itself has consequences (whether the title releases, a branch to carry the fix onto). Write them for the person merging.

## Voice

Plain sentences, no hype adjectives, no "This PR..." opener repeated per section. Caveats stated plainly. Regular hyphens, never em dashes. Never mention AI, Claude or agents. Call out the one judgment call a reviewer should scrutinize instead of making them find it.

## Repo-specific items

Check each and mention the ones that apply:

- **Lua API change:** README (Methods, Filters, Coming from Advanced Peripherals) updated in this PR; the title is the changelog line (release-please), and a change that may break scripts is a breaking commit.
- **Dependency floor change** (`neo_version`, `ae2_version`, `cc_version`): say why the code needs the newer version, since the floors are published as minimums in `neoforge.mods.toml`. A change to `minecraft_version_range` is a different kind of change: it is a bounded range (`[26.1.2,26.2)`), not a minimum, so say which Minecraft versions it now allows.
- **New config option:** name, default, and the README config table row.
- **Anything that changes the client-optional promise** (a registry entry, a network payload, a mods.toml side change): this needs README, mods.toml and CurseForge environment changes; call it out at the top.

## Skeleton

```
<One sentence: what changes and the evidence. Closes #N.>

<Mechanism: why the problem happens.>

<Fix: what the change does, and why this shape.>

## Riders
<Only if any. What each is and whether it changes behaviour.>

## Testing
<What ran: ./gradlew build, server checks. What deliberately did not, and why.>

## Merge notes
<Only if any: branches to carry the fix to. Say whether the title's type releases (feat, fix, perf, breaking) or not.>
```

Long bodies go through a file: `gh pr create --title "<subject>" --body-file <file>`.
