---
name: pre-push-review
description: Five-lane adversarial review of the outgoing diff, run before every push of code (docs-only pushes too, unless the owner waives it). Use before any git push in this repo.
---

# Pre-push review

Run this before every push, including docs-only pushes, unless the owner waives it for that push. The diff under review is `origin/<base>...HEAD` (base is `main` unless the work targets another Minecraft-version branch).

## Lanes

Five independent lanes, one agent each, tailored to this mod:

1. **lua-correctness**: Lua methods and CC generic-method rules, null or destroyed-node handling, filter parsing.
2. **item-fluid-safety**: duplication or loss in export and import, transaction misuse, exceptions between extract and insert.
3. **security-multiplayer**: access to other players' networks, config switches respected, unbounded work (crafts, large lists).
4. **build-packaging-ci**: gradle, `neoforge.mods.toml` (sides, version ranges), workflows, release and tag rules.
5. **api-contract-docs**: README and CHANGELOG match the code, Advanced Peripherals compatibility claims are accurate.

Each lane returns only: claim, evidence, caveat, source link, `file:line`, confidence.

## Merge

- Remove duplicates.
- Flag conflicts between lanes. Do not hide disagreement or weak evidence.
- Rank the five findings that most change the answer.
- Keep low-confidence findings, labeled as such, instead of dropping them.

## Act on the result

- Fix findings by default.
- Ask the owner only about product decisions, or about findings you believe are wrong (say why).
- A fix commit made after the review does not need a second full swarm unless it changes the shape of the diff (new files, new behaviour, a different approach). Re-run the affected lane in that case.
- Push only when the findings are resolved or the owner has decided.

## How to run

With the Workflow tool:

```
Workflow({ name: "pre-push-review", args: { base: "main" } })
```

Optional args: `repo` (path of the checkout, default `.`), `lanes` (an array of lane names to narrow the run, or `{ name, focus }` objects for custom lanes).

If the Workflow tool is unavailable, run it by hand: start five background agents, one per lane, using the lane prompts in `.claude/workflows/pre-push-review.js`, then merge their answers yourself as described above.
