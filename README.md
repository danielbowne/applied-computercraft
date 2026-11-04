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
| `keyType = "appflux:flux"` | exact AE2 key type id, as reported in the `keyType` field of an entry |
| `type = "item"` / `"fluid"` / a key type id | same idea as `keyType`, with `"item"` and `"fluid"` as short names. `type = "ae2:i"` also matches items, and `"ae2:f"` fluids. Unknown values match nothing |
| `count = 64` (or `amount`) | how many to move or craft. Defaults to 64 for items and 1000 mB for fluids |

`keyType` and `type` only narrow a search. Methods that move, craft or cancel (and `getItem`, `getFluid`, `isCrafting` and the `isCraftable` family) still need a `name`, a tag or a `displayName`, and `cancelCraftingTasks` needs one of those or `{all = true}`. Only `getResources`, `getResource` and `getPatterns` accept a `keyType` or `type` on its own.

Data components are not matched. A name matches every variant of that id, and the first one found is used.

## Methods

Methods that fail return `nil` (or `0` / `false`) plus a status string, such as `NOT_CONNECTED`, `NOT_FOUND`, `NOT_CRAFTABLE` or `INVENTORY_NOT_FOUND`.

**Status**
- `isConnected()`: true when the bridge can use the network.
- `isOnline()`

**Listing**
- `getItems([filter])` / `listItems`, `getFluids([filter])` / `listFluids`
- `getResources([filter])` / `listResources`: every stored key of every AE2 key type, not just items and fluids. This is how you see add-on key types such as Applied Flux's FE. It walks the network inventory once, like `getItems`.
- `getItem(filter)`, `getFluid(filter)`, `getResource(filter)`: one entry, or the craftable entry with count 0 if none is stored. `getResource` accepts any key type.
- `getCraftableItems([filter])`, `getCraftableFluids([filter])`

Each entry is a table with `name`, `displayName`, `count`, `amount`, `isCraftable`, `type`, `keyType`, `amountPerUnit` and, when the key type has one, `unit`. Items also have `maxStackSize` and `hasComponents`.
- `type` is `"item"`, `"fluid"`, or the key type id for anything else.
- `keyType` is the AE2 key type id, such as `"ae2:i"` (items) or `"ae2:f"` (fluids).
- `unit` is the key type's unit symbol (`"B"` for fluids). Items have none, so the field is absent.
- `amountPerUnit` is how many base units make one `unit`: 1000 for fluids, 1 for items.
- `count` and `amount` are raw base units (mB for fluids). Divide by `amountPerUnit` to get `unit`s, for example buckets.
- `keyType`, `unit` and `amountPerUnit` also appear on `getItems`, `getFluids` and the other methods that return entries.
- Lua numbers are doubles, so counts above 2^53 (about 9e15) lose precision.

Reading Applied Flux's FE, which it stores in the network as its own key type. The key type is `appflux:flux` and the key itself is `appflux:fe` (confirmed on a live network):

```lua
local fe = me.getResource({keyType = "appflux:flux"})
if fe then print(fe.displayName, fe.count) end
```

Applied Replicatics' matter shows up the same way, as `keyType` `apprep:m`. For any other add-on, call `me.getResources()` and look at the `keyType` field of its entries.

**Crafting**
- `isCraftable(filter)`, `isItemCraftable(filter)`, `isFluidCraftable(filter)`
- `isCrafting(filter)`
- `craftItem(filter [, cpuName])` and `craftFluid(filter [, cpuName])` return a job id.
  - When AE2 has worked out the crafting plan, the computer gets one event, `me_crafting`, with `id, ok, name, amount, message`.
  - `message` is one of: `STARTED`, `CANCELLED`, `NOT_CONNECTED`, `MISSING_INGREDIENTS: 4xminecraft:iron_ingot ...`, an AE2 error such as `CPU_BUSY` or `NO_CPU_FOUND`, or `ERROR: ...`.
  - `STARTED` means the job was handed to a crafting CPU, not that the items are finished. Use `isCrafting` to see when it is done.
  - CC drops events that arrive while a script is waiting on a peripheral call, so a script that fires several `craftItem` calls in a row can miss some `me_crafting` events. To be safe, check with `isCrafting` or `getCraftingTasks`.
  - Each computer can have 8 requests calculating at once by default. Past that the call returns `nil, "TOO_MANY_JOBS"`.
- `getCraftingCPUs()`, `getCraftingTasks()`. A CPU without a name is reported as `CPU #n`, where n is its 1-based position in the network's CPU list. This is the same format AE2's screen uses, but the position is not stable: it can change after a restart or when a CPU is built, removed or goes inactive. Name your CPUs in AE2 to target one reliably.
  - The `cpuName` argument of `craftItem` and `craftFluid` takes a CPU's name or that `CPU #n` label. A CPU that is literally named `CPU #3` is preferred over the unnamed CPU in position 3.
  - `"Unnamed"` is still accepted as `cpuName` and means the first unnamed CPU. It is deprecated and no longer reported anywhere.
- `cancelCraftingTasks(filter)`: cancels running jobs for that resource, and this computer's requests that are still calculating. A filter is required. Pass `{all = true}` to cancel every job on the network.

**Energy**
- `getStoredEnergy()`, `getEnergyCapacity()`, `getEnergyUsage()`, `getAverageEnergyInput()`, `getEnergyInfo()`. These also work when the network is out of power.

**Flux Accessor** (needs the optional Applied Flux mod)

Read and change the IO mode of an Applied Flux Flux Accessor when the bridge is attached to one. The accessor block shows up to a computer as an `me_bridge` like any other AE2 block; a Flux Accessor part on a cable works too.

- `getFluxMode([side])`: `{mode, fast}`.
  - `"input"`: the accessor only takes FE into the network. It neither pushes FE out nor lets neighbouring blocks pull it out.
  - `"output"`: it pushes FE from the network into neighbouring blocks, and neighbours can pull FE out of the network through it.
  - `"both"`: input and output together.
  - `fast` is Applied Flux's fast mode: the accessor then outputs every tick, which can cost server performance. There is no `getFluxFastMode`; read `fast` from this table.
- `setFluxMode(mode [, side])`: sets the mode to `"input"`, `"output"` or `"both"` (any case) and returns `true`. A bad mode throws an error. A change is marked for saving with the world (Applied Flux's own screen does not do that, so a change made there can be lost if nothing else saves the chunk); setting the mode it already has changes and saves nothing.
- `setFluxFastMode(fast [, side])`: turns fast mode on or off and returns `true`.
- `side` is the absolute world face a part sits on (`"up"`/`"top"`, `"down"`/`"bottom"`, `"north"`, `"south"`, `"east"`, `"west"`), not relative to the computer; `"left"`, `"right"`, `"front"` and `"back"` are rejected with an error. It only matters on a cable that carries more than one Flux Accessor part. With several and no `side` the call throws an error (a missing argument, not a status), so a script on such a cable should pass `side` or wrap the call in `pcall`. A Flux Accessor block ignores `side`.
- Statuses, returned as the second value with `nil` (`getFluxMode`) or `false` (the setters):
  - `NOT_FLUX_ACCESSOR`: the block or cable is not a Flux Accessor (for example a controller or an interface).
  - `APPFLUX_NOT_LOADED`: Applied Flux is not installed.
  - `APPFLUX_INCOMPATIBLE`: Applied Flux is installed but its Flux Accessor API is not the one this mod expects (an Applied Flux version this mod does not support). Details are in the server log.
- Gating: `setFluxMode` and `setFluxFastMode` need `allowFluxControl` and `enabled` (on by default), plus `allowOnCables` when the accessor is a cable part. `getFluxMode` needs only `enabled` (plus `allowOnCables` for a part) and never touches the grid. The calls work while the network is unpowered, but the new mode only has an effect once the accessor is powered and active.

Guarded use, so the same script also runs on a version of this mod without the methods:

```lua
local bridge = peripheral.find("me_bridge")
if bridge.setFluxMode then
  local info, err = bridge.getFluxMode()
  if info then
    print("flux accessor is in " .. info.mode .. " mode")
    bridge.setFluxMode("both")
  else
    print("not a flux accessor: " .. err)
  end
end
```

A reserve guard: stop the network feeding FE to the machines around the accessor when the stored FE runs low, and let it feed them again once it has recovered. The gap between the two thresholds stops it from flipping back and forth around one value. Run it on a computer next to the accessor: the accessor is on the network, so the same bridge reads the FE count (key type `appflux:flux`) and changes the mode. A poll that fails (for example `NOT_CONNECTED`) is skipped rather than counted as 0 FE.

```lua
local LOW, HIGH = 1000000, 1500000   -- FE; HIGH is LOW plus the hysteresis

-- pick the bridge that is a flux accessor, not just any me_bridge
local bridge = peripheral.find("me_bridge", function(_, p)
  return p.getFluxMode ~= nil and p.getFluxMode() ~= nil
end)
if not bridge then error("no me_bridge on a Flux Accessor (needs Applied Flux and a recent Applied ComputerCraft)") end

while true do
  local fe, err = bridge.getResource({keyType = "appflux:flux"})
  if fe then
    local info = bridge.getFluxMode()
    if info then
      if fe.count < LOW and info.mode ~= "input" then
        bridge.setFluxMode("input")     -- keep the reserve: only charge the network
      elseif fe.count > HIGH and info.mode ~= "both" then
        bridge.setFluxMode("both")
      end
    end
  else
    print("skipping this poll: " .. tostring(err))
  end
  sleep(5)
end
```

If Applied Flux's self-charge is on (`[flux_accessor] enable` in `appflux-common.toml`), the `"input"` mode also stops the accessor from charging the network's AE power from the stored FE (Applied Flux only does that while the accessor can output). A reserve guard on a network that is powered that way can therefore brown the network out.

**Network info**

Read-only methods that describe the network itself. They need `allowNetworkInfo` (on by default) and a powered, active network, like the other methods.

- `getDrives()`: one entry per ME Drive and ME Chest (add-on drives such as the Extended Drive too). Fields: `name` (the drive's custom name if it has been renamed, else the block's name), `id` (the block's registry id), `displayName` (the block's name), `position` (`{x, y, z}`, left out when the server sets `exposeDrivePositions = false`), `cellCount` (the number of cell slots, not the number of cells installed), `powered`, `totalBytes` and `usedBytes`, and `cells`, a list of the cells in it. The byte totals of a drive add up its basic cells whatever their key type, so an item cell and a fluid cell are mixed in one number, and they include a drive that is not powered; the storage methods below count powered drives only and split by key type.
- `getCells()`: the same cell entries as one flat list. Each also has the drive's `position` (when allowed) and `powered`.
- A cell entry has `slot` (1-based), `item` (the cell item's id), `state` (`EMPTY`, `NOT_EMPTY`, `TYPES_FULL` or `FULL`; empty slots are not listed) and, when AE2 returns the cell's inventory, `idleDrain` (AE/t). For basic cells (AE2's own item and fluid cells and add-ons built on the same API) it also has `type` (`"item"`, `"fluid"` or the key type id), `keyType` (the key type id), `bytes` and `totalBytes` (the same number), `bytesPerType` and `totalTypes`, and, when the inventory is available, `usedBytes` and `usedTypes`. So `idleDrain`, `usedBytes` and `usedTypes` can be absent. Cells that are not basic cells (creative cells, some add-on cells) have only `slot`, `item`, `state` and `idleDrain`, because AE2 gives no capacity for them. A cell that throws while being read (a broken add-on cell) has just `slot`, `item` and `state` plus `error`, a short message, and is left out of the storage totals; the other cells are not affected.
- `getStorageInfo()`: `items`, `fluids` and `other`, each of `items` and `fluids` being `{total, used, available}` in bytes. `other` has the same table for every other key type that has a cell, keyed by key type id. One scan produces all of it.
- `getTotalItemStorage()`, `getUsedItemStorage()`, `getAvailableItemStorage()`, `getTotalFluidStorage()`, `getUsedFluidStorage()`, `getAvailableFluidStorage()`: the same numbers one at a time, summed over the basic cells of that key type in powered drives and chests. Available is total minus used. The result of a scan is kept for the rest of the game tick, so a script that calls several of these (or `getStorageInfo`) in one go scans once. Use `getStorageInfo` if you want the numbers from one scan guaranteed to agree.
- `getChannelInfo()`: `channelMode` (`"INFINITE"`, `"DEFAULT"`, `"X2"`, `"X3"` or `"X4"`), `usedChannels`, `isBooting` and `controllerState` (`"NO_CONTROLLER"`, `"CONTROLLER_ONLINE"` or `"CONTROLLER_CONFLICT"`). Unlike most methods this also answers while the node is not active, so `isBooting` can be true while the network is starting; it still returns `NOT_CONNECTED` before the grid itself has booted. With two controllers (`CONTROLLER_CONFLICT`) the network is not active, so this is the way to see that state. There is no `maxChannels`: AE2 only has a per-cable and per-device limit, no network-wide one.
- `getDevices()`: `totalNodes`, `totalIdlePower` (AE/t) and `devices`, a list with one entry per kind of device, sorted by idle power, highest first. An entry has `name` (the item id the node shows as, such as `ae2:drive` or `ae2:import_bus`; `"unknown"` for a node that shows as nothing), `displayName`, `count`, `idlePower` (the sum of the nodes' idle draw, AE/t) and `channelsCarried`. `channelsCarried` is the channels passing through these devices, not channels they use: AE2 counts a node's channels as everything routed through it, so the sum over many nodes is much larger than the network's `usedChannels`. Cables are devices too, and a drive's idle power includes its cells.
- `getPatterns([filter])`: the patterns of every craftable whose key matches the filter, or all of them with no filter. The filter matches the pattern's primary output only (the key AE2 lists the pattern under), not its other outputs or inputs; it takes the usual `name`, tag, `displayName`, `type` and `keyType`, and `keyType` and `type` work on their own here. A filter with `input` or `output` fields (Advanced Peripherals' form) is an error. Add `alternatives = false` to the filter table to leave out `possibleInputs`. A pattern with several outputs is listed once. Each pattern has `patternItem` (the encoded pattern item's id), `patternType` (`"crafting"`, `"processing"`, `"smithing"`, `"stonecutting"`, or the pattern item's id for any other kind of pattern), `primaryOutput`, `outputs` and `inputs`. Outputs are entries like the ones from `getItems` (`name`, `displayName`, `count`, `type`...) with `count` per craft. An input has `primaryInput`, `possibleInputs` (the alternatives, the primary one first, at most 16; `alternativesTruncated = true` when there were more), `multiplier` and, when crafting leaves something behind (a bucket, say), `remaining`. Patterns come in the order of their primary output's id. At most 2000 patterns are returned, and fewer when they hold more than 20000 stack entries between them; if the list was cut, the call returns it and then `"TRUNCATED"`, so narrow the filter. On a big network this is a large result and it costs a few thousand lookups, so call it on demand, not in a polling loop.
- `getPatternCapacity()`: how many pattern slots the network has and how many are in use, as `{used, total, free, hosts}`. `hosts` has one entry per kind of holder, like `getDevices`: `name` (the block's registry id, or the item id for a cable-mounted part; `"unknown"` if it is neither), `displayName`, `count` (how many pattern inventories of this kind there are), and `used`, `total` and `free` pattern slots over all of them. Sorted by `total`, largest first, ties by `name`. Holders that count: AE2's Pattern Providers (block and part), add-on pattern providers (AdvancedAE's Advanced Pattern Provider, for one), the Pattern Cores of ExtendedAE's Assembler Matrix (36 slots each), and any other add-on block that is a crafting provider and exposes a `getPatternInventory()`. Machines that merely have recipe slots do not count. Slots are counted whether or not each machine is powered; the network itself must be active, like the other methods. Use it for "free slots", not as a pattern count: `getPatterns` can report more patterns than there are used slots here, because it lists what the crafting service knows, which merges duplicate patterns and includes virtual ones from recursive providers.

  ```lua
  local me = peripheral.find("me_bridge")
  local cap, err = me.getPatternCapacity()
  if not cap then print(err) return end
  print(cap.free .. " of " .. cap.total .. " pattern slots free")
  for _, h in ipairs(cap.hosts) do
    print(h.count .. "x " .. h.displayName .. ": " .. h.free .. " of " .. h.total .. " free")
  end
  ```
- `getPendingRequests()`: the resources the network is currently waiting on providers to produce for a crafting job, as entries with `count` set to the amount still expected back (AE2's `getRequestedAmount`), not the total of the job. This includes byproducts. AE2 can only be asked about one resource at a time, so this checks every output of every pattern; it returns at once when nothing is being crafted, and otherwise costs about as much as `getPatterns` without the entries.
- `getDrives` and `getCells` read every cell once per call, so avoid calling them more often than every few seconds on a network with hundreds of cells.

Used bytes are worked out as AE2 does for basic cells: `usedTypes * bytesPerType + ceil(total amount / amountPerByte)`, where `amountPerByte` is 8 for items and 8000 for fluids. Storage buses (external storage) are not counted.

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
- `getResources` and `getResource` have no Advanced Peripherals counterpart.
- Unnamed crafting CPUs are reported as `CPU #n` here, where AP reports `Unnamed`.
- AP filters accept `type = "chemical"` and error on unknown types. Here `type` is `"item"`, `"fluid"` or a key type id (for example the id of a chemical add-on's key type), and unknown values just match nothing.
- There are no `fingerprint` or `components` filters, and entries have no `tags`, `fingerprint` or `components` fields.
- `getCells` and `getDrives` exist but return different fields. A cell entry has `item` as the item id (AP gives a table). `type` is `"item"`, `"fluid"` or the key type id, where AP's `type` is the key type's string form. It has `bytes` as in AP, plus `slot`, `keyType`, `totalBytes`, `usedTypes`, `state`, `idleDrain` and, from `getCells`, `position` and `powered`; it has no `fuzzyMode`. A drive entry has `name`, `position`, `cells`, `usedBytes` and `totalBytes` like AP's, plus `id`, `displayName`, `cellCount` and `powered`, and no `priority` or `menuIcon`. `position` is `{x, y, z}` and can be hidden by the server (`exposeDrivePositions`). AP's `getDrives` lists only 10-slot drives and only AE2's own basic cells; this one also lists ME Chests and add-on drives.
- The storage totals (`getTotalItemStorage`, `getUsedItemStorage`, `getAvailableItemStorage` and the fluid versions) have AP's names and return bytes. They count only cells in powered drives and chests. `getStorageInfo` is new.
- `getPatterns([filter])` returns a list of patterns. With no filter AP also returns a list, but when filtered AP returns one pattern (the first match for `{input = ..., output = ...}`); here the filter is a normal one matched against the primary output and the result is still a list, and the AP form is an error. Each pattern has `inputs`, `outputs`, `primaryOutput` and `patternType` like AP's, and also `patternItem`. `patternType` is the pattern item's id for kinds other than the four standard ones, where AP says `"unknown"`. In an input, `possibleInputs` here lists every alternative (up to 16) including the first, where AP's list leaves out the primary input. Output and input amounts are in `count`.
- Not implemented:
  - the `External` storage methods (`getTotalExternalItemStorage` and the rest). AE2 does not report the capacity of a storage bus's inventory, and AP's numbers are an estimate from slot sizes
  - the chemical storage methods (`getTotalChemicalStorage` and the rest), `getCraftingTask(id)`
  - chemicals as their own methods (use `getResources` to list them, though `craftItem` and the export and import methods still only handle items and fluids)
- Export and import targets don't accept `front`, `back`, `left` or `right` as directions. AE2 blocks have no facing, so use a compass direction or a peripheral name instead.

This mod shares no code with Advanced Peripherals and isn't affiliated with it.

## Server config

`config/appliedcc-server.toml` in the server folder:

| Option | Default | |
|---|---|---|
| `enabled` | true | master switch |
| `allowExport`, `allowImport`, `allowCrafting`, `allowCancel` | true | turn single features off |
| `allowOnCables` | true | set false so only full AE2 blocks work, not cables and parts |
| `allowFluxControl` | true | turns off `setFluxMode` and `setFluxFastMode`; `getFluxMode` is read-only and stays on |
| `allowNetworkInfo` | true | turns off `getDrives`, `getCells`, `getStorageInfo` and the storage capacity methods, `getChannelInfo`, `getDevices`, `getPatterns`, `getPatternCapacity` and `getPendingRequests` |
| `exposeDrivePositions` | true | set false to leave the `position` out of `getDrives` and `getCells` |
| `maxPendingCraftsPerComputer` | 8 | |

AE2 has no access control of its own. Anyone who can place a computer next to a block of your network can read it and, with the defaults, move items in and out. On public servers, think about turning off `allowOnCables` and the features you don't want, or rely on claim protection to keep strangers away from your blocks.

`allowFluxControl` matters for the same reason as `allowExport`: switching a Flux Accessor to `output` or `both` lets neighbouring blocks pull FE out of the network. A server that turns off `allowExport` probably wants `allowFluxControl` off as well.

The network info methods (`allowNetworkInfo`) are read-only but they show how the network is built: what is in each cell, how many devices of each kind there are and what is being crafted. `getDrives` and `getCells` also return the coordinates of every drive and ME Chest, and they can be read from any block of the network, including one on the far side of a quantum bridge or a P2P tunnel, so someone who can reach any part of your network can learn where your storage is. `exposeDrivePositions` defaults to true on purpose, because Advanced Peripherals returns positions too and scripts ported from it expect them. On a public server, set `exposeDrivePositions = false`, and `allowNetworkInfo = false` if you don't want any of this readable.

## Building

`./gradlew build` with JDK 25. The jar ends up in `build/libs/`.

Releases are automatic. Conventional commits on `main` decide the version (`feat` bumps minor, `fix` and `perf` bump patch, a breaking change bumps minor while the version is 0.x; `docs`, `ci`, `chore`, `test`, `refactor` and `build` never release), and [release-please](https://github.com/googleapis/release-please) keeps a release PR open that bumps `mod_version` in `gradle.properties` and adds the new section to `CHANGELOG.md`. Merging that PR creates the tag and the GitHub Release; the workflow then builds the jar, attaches it to the Release and, when the `CURSEFORGE_PROJECT_ID` repository variable is set, uploads it to CurseForge with the `CURSEFORGE_TOKEN` secret (it fails if the secret is empty). If the `ANTHROPIC_API_KEY` secret is set, the Release notes are also rewritten with a short plain-language summary on top (the model comes from the `RELEASE_NOTES_MODEL` variable); otherwise the generated notes stay as they are. If a job fails, re-run only the failed job from the Actions page; to rebuild the jar of an existing tag, run the `Release` workflow with that tag, and the `CurseForge` workflow with that tag to upload it.

## License

MIT, see [LICENSE](LICENSE).
