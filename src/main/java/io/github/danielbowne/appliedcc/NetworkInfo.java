package io.github.danielbowne.appliedcc;

import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.IChestOrDrive;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.pathing.IPathingService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.IBasicCellItem;
import appeng.api.storage.cells.StorageCell;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Read-only network inspection behind getDrives, getCells, the storage capacity methods,
 * getChannelInfo, getDevices, getPatterns and getPendingRequests. Everything here uses
 * appeng.api only. Callers hold the main thread and have already passed the config gate.
 */
final class NetworkInfo {
    /** getPatterns returns at most this many patterns per call. */
    static final int MAX_PATTERNS = 2000;

    /** AE2's BasicCellInventory clamps the number of types of a cell to 1..63; this mirrors it. */
    private static final int MAX_CELL_TYPES = 63;

    private NetworkInfo() {}

    // ---------------------------------------------------------------- drives and cells

    /**
     * Every drive and ME chest on the grid. IGrid.getMachines(Class) only matches the exact class
     * a machine registered under (it looks the class up in a multimap and then filters by
     * isInstance), so asking for IChestOrDrive finds nothing. Instead walk the machine classes,
     * keep the ones assignable to IChestOrDrive, and read the owner of each node.
     */
    static List<IChestOrDrive> drives(IGrid grid) {
        List<Class<?>> classes = new ArrayList<>();
        for (Class<?> c : grid.getMachineClasses()) {
            if (IChestOrDrive.class.isAssignableFrom(c)) classes.add(c);
        }
        Set<IChestOrDrive> found = new LinkedHashSet<>();
        for (Class<?> c : classes) {
            for (IGridNode n : grid.getMachineNodes(c)) {
                if (n.getOwner() instanceof IChestOrDrive d) found.add(d);
            }
        }
        return new ArrayList<>(found);
    }

    /** One cell as seen from a drive slot. bytes and types are only set for IBasicCellItem cells. */
    private static final class Cell {
        final Map<String, Object> data = new HashMap<>();
        AEKeyType keyType; // null when the cell is not an IBasicCellItem
        long totalBytes;
        long usedBytes;
    }

    private static List<Cell> cells(IChestOrDrive drive) {
        List<Cell> out = new ArrayList<>();
        for (int slot = 0; slot < drive.getCellCount(); slot++) {
            CellState state = drive.getCellStatus(slot);
            Item item = drive.getCellItem(slot);
            if (state == null || state == CellState.ABSENT || item == null || item == Items.AIR) continue;
            out.add(cell(drive, slot, state, item));
        }
        return out;
    }

    private static Cell cell(IChestOrDrive drive, int slot, CellState state, Item item) {
        Cell c = new Cell();
        Map<String, Object> m = c.data;
        m.put("slot", slot + 1);
        m.put("item", BuiltInRegistries.ITEM.getKey(item).toString());
        m.put("state", state.name());

        StorageCell inv = drive.getOriginalCellInventory(slot);
        if (inv != null) m.put("idleDrain", inv.getIdleDrain());

        if (item instanceof IBasicCellItem basic) {
            // AE2's own cells ignore the stack in these getters; a bare stack of the item is what
            // the drive API leaves us (it does not expose the real one).
            ItemStack stack = new ItemStack(item);
            AEKeyType type = basic.getKeyType();
            c.keyType = type;
            m.put("type", keyTypeName(type));
            m.put("keyType", type.getId().toString());
            int bytes = basic.getBytes(stack);
            int bytesPerType = basic.getBytesPerType(stack);
            int totalTypes = Math.max(1, Math.min(MAX_CELL_TYPES, basic.getTotalTypes(stack)));
            m.put("bytes", bytes);
            m.put("totalBytes", bytes);
            m.put("bytesPerType", bytesPerType);
            m.put("totalTypes", totalTypes);
            if (inv == null) m.put("idleDrain", basic.getIdleDrain());
            c.totalBytes = bytes;
            if (inv != null) {
                long usedTypes = 0;
                long amount = 0;
                for (var e : inv.getAvailableStacks()) {
                    if (e.getLongValue() > 0) {
                        usedTypes++;
                        amount += e.getLongValue();
                    }
                }
                // BasicCellInventory.getUsedBytes: types * bytesPerType + ceil(amount / amountPerByte).
                // AE2 writes the ceiling as (amount + unused) / amountPerByte, where unused pads
                // amount up to the next multiple of amountPerByte.
                long perByte = type.getAmountPerByte();
                c.usedBytes = usedTypes * bytesPerType + (amount + perByte - 1) / perByte;
                m.put("usedTypes", usedTypes);
                m.put("usedBytes", c.usedBytes);
            }
        }
        return c;
    }

    private static String keyTypeName(AEKeyType t) {
        if (t == AEKeyType.items()) return "item";
        if (t == AEKeyType.fluids()) return "fluid";
        return t.getId().toString();
    }

    private static Map<String, Object> position(IChestOrDrive drive) {
        if (!Config.EXPOSE_DRIVE_POSITIONS.get() || !(drive instanceof BlockEntity be)) return null;
        Map<String, Object> p = new HashMap<>();
        p.put("x", be.getBlockPos().getX());
        p.put("y", be.getBlockPos().getY());
        p.put("z", be.getBlockPos().getZ());
        return p;
    }

    static List<Object> drivesInfo(IGrid grid) {
        List<Object> out = new ArrayList<>();
        for (IChestOrDrive d : drives(grid)) {
            List<Cell> cells = cells(d);
            long totalBytes = 0;
            long usedBytes = 0;
            List<Object> cellData = new ArrayList<>();
            for (Cell c : cells) {
                if (c.keyType != null) {
                    totalBytes += c.totalBytes;
                    usedBytes += c.usedBytes;
                }
                cellData.add(c.data);
            }
            Map<String, Object> m = new HashMap<>();
            if (d instanceof BlockEntity be) m.put("name", be.getBlockState().getBlock().getName().getString());
            Map<String, Object> pos = position(d);
            if (pos != null) m.put("position", pos);
            m.put("cellCount", d.getCellCount());
            m.put("powered", d.isPowered());
            m.put("totalBytes", totalBytes);
            m.put("usedBytes", usedBytes);
            m.put("cells", cellData);
            out.add(m);
        }
        return out;
    }

    static List<Object> cellsInfo(IGrid grid) {
        List<Object> out = new ArrayList<>();
        for (IChestOrDrive d : drives(grid)) {
            Map<String, Object> pos = position(d);
            for (Cell c : cells(d)) {
                Map<String, Object> m = new HashMap<>(c.data);
                if (pos != null) m.put("position", pos);
                m.put("powered", d.isPowered());
                out.add(m);
            }
        }
        return out;
    }

    /** {total, used} bytes over the cells of one key type in powered drives and chests. */
    static long[] storage(IGrid grid, AEKeyType type) {
        long total = 0;
        long used = 0;
        for (IChestOrDrive d : drives(grid)) {
            if (!d.isPowered()) continue;
            for (Cell c : cells(d)) {
                if (c.keyType != type) continue;
                total += c.totalBytes;
                used += c.usedBytes;
            }
        }
        return new long[] {total, used};
    }

    // ---------------------------------------------------------------- channels

    static Map<String, Object> channelInfo(IGrid grid) {
        IPathingService p = grid.getPathingService();
        Map<String, Object> m = new HashMap<>();
        m.put("channelMode", String.valueOf(p.getChannelMode()));
        m.put("usedChannels", p.getUsedChannels());
        m.put("isBooting", p.isNetworkBooting());
        m.put("controllerState", String.valueOf(p.getControllerState()));
        return m;
    }

    // ---------------------------------------------------------------- devices

    private static final class Group {
        final String name;
        final String displayName;
        long count;
        double idlePower;
        long usedChannels;

        Group(String name, String displayName) {
            this.name = name;
            this.displayName = displayName;
        }
    }

    /** Every node of the grid grouped by the item it shows as (a part, a block, a cable). */
    static Map<String, Object> devices(IGrid grid) {
        Map<String, Group> groups = new LinkedHashMap<>();
        double totalIdle = 0;
        for (IGridNode n : grid.getNodes()) {
            AEItemKey vis = n.getVisualRepresentation();
            String id = vis == null ? "unknown" : vis.getId().toString();
            Group g = groups.computeIfAbsent(id, k -> new Group(k, vis == null ? "Unknown" : vis.getDisplayName().getString()));
            double idle = n.getIdlePowerUsage();
            g.count++;
            g.idlePower += idle;
            g.usedChannels += n.getUsedChannels();
            totalIdle += idle;
        }
        List<Group> sorted = new ArrayList<>(groups.values());
        sorted.sort(Comparator.<Group>comparingDouble(g -> g.idlePower).reversed().thenComparing(g -> g.name));
        List<Object> list = new ArrayList<>();
        for (Group g : sorted) {
            Map<String, Object> m = new HashMap<>();
            m.put("name", g.name);
            m.put("displayName", g.displayName);
            m.put("count", g.count);
            m.put("idlePower", g.idlePower);
            m.put("usedChannels", g.usedChannels);
            list.add(m);
        }
        Map<String, Object> out = new HashMap<>();
        out.put("totalNodes", grid.size());
        out.put("totalIdlePower", totalIdle);
        out.put("devices", list);
        return out;
    }

    // ---------------------------------------------------------------- patterns and requests

    private static String patternType(IPatternDetails p) {
        AEItemKey def = p.getDefinition();
        if (def == null) return "unknown";
        String id = def.getId().toString();
        return switch (id) {
            case "ae2:crafting_pattern" -> "crafting";
            case "ae2:processing_pattern" -> "processing";
            case "ae2:smithing_table_pattern" -> "smithing";
            case "ae2:stonecutting_pattern" -> "stonecutting";
            default -> id;
        };
    }

    private static Map<String, Object> pattern(IPatternDetails p, ICraftingService cs) {
        Map<String, Object> m = new HashMap<>();
        AEItemKey def = p.getDefinition();
        if (def != null) m.put("patternItem", def.getId().toString());
        m.put("patternType", patternType(p));
        GenericStack primary = p.getPrimaryOutput();
        if (primary != null && primary.what() != null) m.put("primaryOutput", MeBridgeMethods.stack(primary.what(), primary.amount(), cs));
        List<Object> outputs = new ArrayList<>();
        for (GenericStack o : p.getOutputs()) {
            if (o != null && o.what() != null) outputs.add(MeBridgeMethods.stack(o.what(), o.amount(), cs));
        }
        m.put("outputs", outputs);
        List<Object> inputs = new ArrayList<>();
        for (IPatternDetails.IInput in : p.getInputs()) {
            GenericStack[] possible = in.getPossibleInputs();
            if (possible == null || possible.length == 0 || possible[0] == null || possible[0].what() == null) continue;
            Map<String, Object> im = new HashMap<>();
            List<Object> alternatives = new ArrayList<>();
            for (GenericStack s : possible) {
                if (s != null && s.what() != null) alternatives.add(MeBridgeMethods.stack(s.what(), s.amount(), cs));
            }
            im.put("primaryInput", alternatives.get(0));
            im.put("possibleInputs", alternatives);
            im.put("multiplier", in.getMultiplier());
            AEKey remaining = in.getRemainingKey(possible[0].what());
            if (remaining != null) im.put("remaining", MeBridgeMethods.stack(remaining, 1, cs));
            inputs.add(im);
        }
        m.put("inputs", inputs);
        return m;
    }

    /** Result of {@link #patterns}: the entries and whether the {@link #MAX_PATTERNS} cap cut the list. */
    record Patterns(List<Object> list, boolean truncated) {}

    static Patterns patterns(IGrid grid, Predicate<AEKey> keep) {
        ICraftingService cs = grid.getCraftingService();
        Set<IPatternDetails> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Object> out = new ArrayList<>();
        for (AEKey key : cs.getCraftables(k -> true)) {
            if (!keep.test(key)) continue;
            for (IPatternDetails p : cs.getCraftingFor(key)) {
                if (!seen.add(p)) continue;
                if (out.size() >= MAX_PATTERNS) return new Patterns(out, true);
                out.add(pattern(p, cs));
            }
        }
        return new Patterns(out, false);
    }

    /**
     * AE2 has no call that lists what the crafting service is requesting, only isRequestingAny and
     * the per-key isRequesting / getRequestedAmount. What it requests are pattern outputs, so after
     * the cheap isRequestingAny check every craftable key is tested (a set lookup each).
     */
    static List<Object> pendingRequests(IGrid grid) {
        ICraftingService cs = grid.getCraftingService();
        List<Object> out = new ArrayList<>();
        if (!cs.isRequestingAny()) return out;
        for (AEKey key : cs.getCraftables(k -> true)) {
            if (cs.isRequesting(key)) out.add(MeBridgeMethods.stack(key, cs.getRequestedAmount(key), cs));
        }
        return out;
    }
}
