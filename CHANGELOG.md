# Changelog

## 0.2.0

### Added

- `getResources([filter])` (alias `listResources`) and `getResource(filter)`: list and look up every AE2 key type in the network, not only items and fluids. This makes add-on key types such as Applied Flux's FE visible.
- Filter fields `keyType` (exact AE2 key type id) and `type` (`"item"`, `"fluid"` or a key type id). They only narrow a search; a keyType or type on its own is accepted only by `getResources` and `getResource`.
- Entries carry `keyType` (the AE2 key type id), `amountPerUnit` (base units per `unit`, for example 1000 for fluids) and, when the key type has one, `unit`. Counts stay in raw base units.

### Changed (may affect existing scripts)

- Crafting CPUs without a name are reported as `CPU #n` (1-based position in the network's CPU list) instead of `Unnamed`. The position can change after a restart or when CPUs are built or removed, so name CPUs in AE2 to target one reliably. `craftItem` and `craftFluid` accept the `CPU #n` label as `cpuName`; `"Unnamed"` still works and means the first unnamed CPU (deprecated).
- The filter `type` field is now applied. 0.1.0 ignored it.
- Entries returned by `getItems`, `getFluids` and the other listing methods gain the new `keyType`, `unit` and `amountPerUnit` fields.

## 0.1.0

First release, for Minecraft 26.1.2.

- `me_bridge` peripheral on every AE2 block with a grid node, no new blocks
- Item and fluid listing and lookup, by id, tag or display name
- Crafting requests with a `me_crafting` event, CPU and task listing, cancelling
- Energy readouts
- Export and import of items and fluids by direction or peripheral name
- Server config to turn the mod or single features off, keep it off cables, and cap pending crafts per computer
