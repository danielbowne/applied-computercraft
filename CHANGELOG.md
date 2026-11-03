# Changelog

## 0.2.0

- New `getResources([filter])` (alias `listResources`) and `getResource(filter)`: list and look up every AE2 key type in the network, not only items and fluids. This makes add-on key types such as Applied Flux's FE visible.
- New filter fields `keyType` (exact AE2 key type id) and `type` (`"item"`, `"fluid"` or a key type id).
- Entries now carry `keyType` (the AE2 key type id) and, when the key type has one, `unit`. This also applies to the entries returned by `getItems`, `getFluids` and the other listing methods. For key types other than items and fluids, `type` is now the key type id.
- Crafting CPUs without a name are reported as `CPU #n` (1-based position in the network's CPU list, as in AE2's screen) instead of `Unnamed`. The `cpuName` argument of `craftItem` and `craftFluid` accepts that label.

## 0.1.0

First release, for Minecraft 26.1.2.

- `me_bridge` peripheral on every AE2 block with a grid node, no new blocks
- Item and fluid listing and lookup, by id, tag or display name
- Crafting requests with a `me_crafting` event, CPU and task listing, cancelling
- Energy readouts
- Export and import of items and fluids by direction or peripheral name
- Server config to turn the mod or single features off, keep it off cables, and cap pending crafts per computer
