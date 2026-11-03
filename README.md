<p align="center"><img src="docs/logo.png" width="160" alt="Applied ComputerCraft logo"></p>

# Applied ComputerCraft

A small NeoForge mod for Minecraft 26.1.2 that lets CC:Tweaked computers read and drive an Applied Energistics 2 network.

There are no new blocks. It adds a CC peripheral to every AE2 block that hosts a grid node (controller, interface, drive, energy acceptor, cable). Put a computer or wired modem against one and use `peripheral.find("me_bridge")`:

```lua
local me = peripheral.find("me_bridge")
print(me.getItem({name = "minecraft:diamond"}).count)
```

![A CC:Tweaked computer reading an AE2 network: connected, 1871 item types, 50038 iron ingots](docs/screenshot-lua.png)

It only needs to be installed where the game logic runs: on a dedicated server, or in the client for singleplayer. It adds nothing to registries or the network protocol, so players can join a server that has it without installing it themselves.

## Requirements

- Minecraft 26.1.2, NeoForge 26.1.2.94 or newer
- Applied Energistics 2 26.1.10-beta or newer
- CC:Tweaked 1.118.0 or newer

The AE block the computer touches must be powered and active.

## Filters

Most methods take a filter: a string id, or a table with any of these fields.

| Field | Meaning |
|---|---|
| `name = "minecraft:iron_ingot"` | exact id |
| `name = "#c:ingots"` | item or fluid tag |
| `displayName = "iron"` | case-insensitive substring of the display name |
| `count = 64` (or `amount`) | how many to move or craft. Defaults to 64 for items and 1000 mB for fluids |

Data components are not matched. A name matches every variant of that id, and the first one found is used.

## Methods

Methods that fail return `nil` (or `0` / `false`) plus a status string, such as `NOT_CONNECTED`, `NOT_FOUND`, `NOT_CRAFTABLE` or `INVENTORY_NOT_FOUND`.

**Status**
- `isConnected()`: true when the bridge can use the network.
- `isOnline()`

**Listing**
- `getItems([filter])` / `listItems`, `getFluids([filter])` / `listFluids`
- `getItem(filter)`, `getFluid(filter)`: one entry, or the craftable entry with count 0 if none is stored.
- `getCraftableItems([filter])`, `getCraftableFluids([filter])`

Each entry is a table with `name`, `displayName`, `count`, `amount`, `isCraftable` and `type` (`"item"` or `"fluid"`). Items also have `maxStackSize` and `hasComponents`.

**Crafting**
- `isCraftable(filter)`, `isItemCraftable(filter)`, `isFluidCraftable(filter)`
- `isCrafting(filter)`
- `craftItem(filter [, cpuName])` and `craftFluid(filter [, cpuName])` return a job id.
  - When AE2 has worked out the crafting plan, the computer gets one event, `me_crafting`, with `id, ok, name, amount, message`.
  - `message` is one of: `STARTED`, `CANCELLED`, `NOT_CONNECTED`, `MISSING_INGREDIENTS: 4xminecraft:iron_ingot ...`, an AE2 error such as `CPU_BUSY` or `NO_CPU_FOUND`, or `ERROR: ...`.
  - `STARTED` means the job was handed to a crafting CPU, not that the items are finished. Use `isCrafting` to see when it is done.
  - CC drops events that arrive while a script is waiting on a peripheral call, so a script that fires several `craftItem` calls in a row can miss some `me_crafting` events. To be safe, check with `isCrafting` or `getCraftingTasks`.
  - Each computer can have 8 requests calculating at once by default. Past that the call returns `nil, "TOO_MANY_JOBS"`.
- `getCraftingCPUs()`, `getCraftingTasks()`
- `cancelCraftingTasks(filter)`: cancels running jobs for that resource, and this computer's requests that are still calculating. A filter is required. Pass `{all = true}` to cancel every job on the network.

**Energy**
- `getStoredEnergy()`, `getEnergyCapacity()`, `getEnergyUsage()`, `getAverageEnergyInput()`, `getEnergyInfo()`. These also work when the network is out of power.

**Moving items and fluids**
- `exportItem(filter, target)`, `exportFluid(filter, target)`, `importItem(filter, target)`, `importFluid(filter, target)` return the amount moved.
- `target` is either:
  - a direction from the AE block: `"up"` or `"top"`, `"down"` or `"bottom"`, `"north"`, `"south"`, `"east"`, `"west"`
  - the name of a peripheral the computer can see, such as `"left"` or `"minecraft:chest_0"`
- For import, `{}` means anything.
- If a storage bus on the same network watches the target inventory, the items just go around in a loop. The amount returned counts them anyway, even though nothing really moved.

## Coming from Advanced Peripherals

The Lua API is modelled on Advanced Peripherals' ME Bridge, and method names and argument order match where the two overlap. Porting a script is usually a small edit, but it isn't a drop-in replacement:

- The crafting event is `me_crafting` (AP uses `ae_crafting`). `craftItem` returns a number, not a job object.
- There are no `fingerprint` or `components` filters, and entries have no `tags`, `fingerprint` or `components` fields.
- Not implemented:
  - the storage capacity methods (`getTotalItemStorage` and friends)
  - `getCells`, `getDrives`, `getPatterns`, `getCraftingTask(id)`
  - chemicals
- Export and import targets don't accept `front`, `back`, `left` or `right` as directions. AE2 blocks have no facing, so use a compass direction or a peripheral name instead.

This mod shares no code with Advanced Peripherals and isn't affiliated with it.

## Server config

`config/appliedcc-server.toml` in the server folder:

| Option | Default | |
|---|---|---|
| `enabled` | true | master switch |
| `allowExport`, `allowImport`, `allowCrafting`, `allowCancel` | true | turn single features off |
| `allowOnCables` | true | set false so only full AE2 blocks work, not cables and parts |
| `maxPendingCraftsPerComputer` | 8 | |

AE2 has no access control of its own. Anyone who can place a computer next to a block of your network can read it and, with the defaults, move items in and out. On public servers, think about turning off `allowOnCables` and the features you don't want, or rely on claim protection to keep strangers away from your blocks.

## Building

`./gradlew build` with JDK 25. The jar ends up in `build/libs/`.

Pushing a tag such as `v0.1.0` that matches `mod_version` in `gradle.properties` builds the jar. It then publishes a GitHub Release, using that version's section of `CHANGELOG.md` as the notes. When the `CURSEFORGE_PROJECT_ID` repository variable is set, a second job uploads the same jar to CurseForge with the `CURSEFORGE_TOKEN` secret (it fails if the secret is empty). If only that job fails, re-run it from the Actions page; the GitHub Release is not touched.

## License

MIT, see [LICENSE](LICENSE).
