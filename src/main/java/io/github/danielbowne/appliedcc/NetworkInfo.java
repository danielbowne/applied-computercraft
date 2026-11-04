package io.github.danielbowne.appliedcc;

import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.IChestOrDrive;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.pathing.IPathingService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.IBasicCellItem;
import appeng.api.storage.cells.StorageCell;
import appeng.helpers.patternprovider.PatternContainer;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Nameable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * Read-only network inspection behind getDrives, getCells, the storage capacity methods,
 * getChannelInfo, getDevices, getPatterns, getPatternCapacity and getPendingRequests. Everything here uses
 * appeng.api, with one deliberate exception: getPatternCapacity reads appeng.helpers.patternprovider.PatternContainer,
 * the interface AE2's own pattern terminal uses, because it is the only common type of AE2 pattern
 * providers and of add-on ones (AdvancedAE's Advanced Pattern Provider is not a PatternProviderLogicHost).
 * Callers hold the main thread and have already passed the config gate.
 */
final class NetworkInfo {
    private static final Logger LOG = LogUtils.getLogger();

    /** getPatterns returns at most this many patterns per call... */
    static final int MAX_PATTERNS = 2000;
    /** ...and stops once the patterns returned hold this many stack entries (outputs and input alternatives) in total. */
    static final int MAX_ENTRIES = 20000;
    /** An input lists at most this many alternatives; the input then has alternativesTruncated = true. */
    static final int MAX_ALTERNATIVES = 16;

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

    /** One cell as seen from a drive slot. bytes and types are only set for IBasicCellItem cells that read fine. */
    private static final class Cell {
        final Map<String, Object> data = new HashMap<>();
        AEKeyType keyType; // null when the cell is not an IBasicCellItem, or reading it failed
        long totalBytes;
        long usedBytes;
    }

    private static List<Cell> cells(IChestOrDrive drive) {
        List<Cell> out = new ArrayList<>();
        for (int slot = 0; slot < drive.getCellCount(); slot++) {
            CellState state = drive.getCellStatus(slot);
            Item item = drive.getCellItem(slot);
            if (state == null || state == CellState.ABSENT || item == null || item == Items.AIR) continue;
            Cell c;
            try {
                c = cell(drive, slot, state, item);
            } catch (RuntimeException e) {
                // One misbehaving add-on cell must not take the whole listing down: report the
                // basic fields and the reason, and leave the cell out of the storage totals.
                LOG.debug("Reading cell {} in slot {} failed", item, slot + 1, e);
                c = new Cell();
                fillBasic(c.data, slot, state, item);
                c.data.put("error", String.valueOf(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
            out.add(c);
        }
        return out;
    }

    private static void fillBasic(Map<String, Object> m, int slot, CellState state, Item item) {
        m.put("slot", slot + 1);
        m.put("item", BuiltInRegistries.ITEM.getKey(item).toString());
        m.put("state", state.name());
    }

    private static Cell cell(IChestOrDrive drive, int slot, CellState state, Item item) {
        Cell c = new Cell();
        Map<String, Object> m = c.data;
        fillBasic(m, slot, state, item);

        StorageCell inv = drive.getOriginalCellInventory(slot);
        if (inv != null) m.put("idleDrain", inv.getIdleDrain());

        if (item instanceof IBasicCellItem basic) {
            // AE2's own cells ignore the stack in these getters; a bare stack of the item is what
            // the drive API leaves us (it does not expose the real one).
            ItemStack stack = new ItemStack(item);
            AEKeyType type = basic.getKeyType();
            int bytes = basic.getBytes(stack);
            int bytesPerType = basic.getBytesPerType(stack);
            int totalTypes = Math.max(1, Math.min(MAX_CELL_TYPES, basic.getTotalTypes(stack)));
            double idleDrain = inv == null ? basic.getIdleDrain() : 0;
            long usedTypes = 0;
            long usedBytes = 0;
            if (inv != null) {
                long amount = 0;
                for (var e : inv.getAvailableStacks()) {
                    if (e.getLongValue() > 0) {
                        usedTypes++;
                        amount += e.getLongValue();
                    }
                }
                long perByte = type.getAmountPerByte();
                if (perByte <= 0) throw new IllegalStateException("key type " + type.getId() + " reports amountPerByte " + perByte);
                // BasicCellInventory.getUsedBytes: types * bytesPerType + ceil(amount / amountPerByte).
                // AE2 writes the ceiling as (amount + unused) / amountPerByte, where unused pads
                // amount up to the next multiple of amountPerByte.
                usedBytes = usedTypes * bytesPerType + (amount + perByte - 1) / perByte;
            }
            // everything read: only now set the fields, so a failure above leaves the cell untouched
            c.keyType = type;
            m.put("type", keyTypeName(type));
            m.put("keyType", type.getId().toString());
            m.put("bytes", bytes);
            m.put("totalBytes", bytes);
            m.put("bytesPerType", bytesPerType);
            m.put("totalTypes", totalTypes);
            c.totalBytes = bytes;
            if (inv == null) {
                m.put("idleDrain", idleDrain);
            } else {
                c.usedBytes = usedBytes;
                m.put("usedTypes", usedTypes);
                m.put("usedBytes", usedBytes);
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
            if (d instanceof BlockEntity be) {
                Block block = be.getBlockState().getBlock();
                String display = block.getName().getString();
                String name = display;
                // Nameable is vanilla; AE2's block entities implement it, so a renamed drive shows its name
                if (be instanceof Nameable n && n.hasCustomName() && n.getCustomName() != null) name = n.getCustomName().getString();
                m.put("name", name);
                m.put("id", BuiltInRegistries.BLOCK.getKey(block).toString());
                m.put("displayName", display);
            }
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

    // ---------------------------------------------------------------- storage totals

    /** Totals per key type id: {total, used} bytes. */
    private record CachedStorage(long gameTime, Map<String, long[]> totals) {}

    /**
     * One scan per grid per game tick: several getters in the same tick (or a poll calling all of
     * them) share it. Keyed weakly by grid; the values hold only numbers, never the grid. Main thread only.
     */
    private static final Map<IGrid, CachedStorage> STORAGE_CACHE = new WeakHashMap<>();

    private static Map<String, long[]> storageTotals(IGrid grid) {
        IGridNode pivot = grid.getPivot();
        long now = pivot == null ? -1 : pivot.getLevel().getGameTime();
        CachedStorage cached = STORAGE_CACHE.get(grid);
        if (now >= 0 && cached != null && cached.gameTime() == now) return cached.totals();

        Map<String, long[]> totals = new LinkedHashMap<>();
        totals.put(AEKeyType.items().getId().toString(), new long[2]);
        totals.put(AEKeyType.fluids().getId().toString(), new long[2]);
        for (IChestOrDrive d : drives(grid)) {
            if (!d.isPowered()) continue;
            for (Cell c : cells(d)) {
                if (c.keyType == null) continue;
                long[] t = totals.computeIfAbsent(c.keyType.getId().toString(), k -> new long[2]);
                t[0] += c.totalBytes;
                t[1] += c.usedBytes;
            }
        }
        if (now >= 0) STORAGE_CACHE.put(grid, new CachedStorage(now, totals));
        return totals;
    }

    /** {total, used} bytes over the cells of one key type in powered drives and chests. */
    static long[] storage(IGrid grid, AEKeyType type) {
        long[] t = storageTotals(grid).get(type.getId().toString());
        return t == null ? new long[2] : t.clone();
    }

    private static Map<String, Object> totalsEntry(long[] t) {
        Map<String, Object> m = new HashMap<>();
        m.put("total", t[0]);
        m.put("used", t[1]);
        m.put("available", t[0] - t[1]);
        return m;
    }

    /** {items = {total, used, available}, fluids = {...}, other = {[keyTypeId] = {...}}} from one scan. */
    static Map<String, Object> storageInfo(IGrid grid) {
        Map<String, long[]> totals = storageTotals(grid);
        String items = AEKeyType.items().getId().toString();
        String fluids = AEKeyType.fluids().getId().toString();
        Map<String, Object> out = new HashMap<>();
        out.put("items", totalsEntry(totals.get(items)));
        out.put("fluids", totalsEntry(totals.get(fluids)));
        Map<String, Object> other = new HashMap<>();
        for (var e : totals.entrySet()) {
            if (!e.getKey().equals(items) && !e.getKey().equals(fluids)) other.put(e.getKey(), totalsEntry(e.getValue()));
        }
        out.put("other", other);
        return out;
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
        long channelsCarried;

        Group(String name, String displayName) {
            this.name = name;
            this.displayName = displayName;
        }
    }

    /**
     * Every node of the grid grouped by the item it shows as (a part, a block, a cable).
     * IGridNode.getUsedChannels is what passes through a node (AE2 sums the connections beyond it),
     * not what the node itself uses, so the per-group sum is reported as channelsCarried.
     */
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
            g.channelsCarried += n.getUsedChannels();
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
            m.put("channelsCarried", g.channelsCarried);
            list.add(m);
        }
        Map<String, Object> out = new HashMap<>();
        out.put("totalNodes", grid.size());
        out.put("totalIdlePower", totalIdle);
        out.put("devices", list);
        return out;
    }

    // ---------------------------------------------------------------- pattern capacity

    /** Per kind of host (keyed by block or part item id): how many there are and their pattern slots. */
    private static final class PatternKind {
        final String name;
        final String displayName;
        long count;
        long used;
        long total;

        PatternKind(String name, String displayName) {
            this.name = name;
            this.displayName = displayName;
        }
    }

    /**
     * Machines that hold patterns without being a PatternContainer. Only real crafting providers
     * (ICraftingProvider) are considered, and only when they have a public no-arg
     * getPatternInventory() returning an InternalInventory. That is how add-on blocks without the AE2
     * interface expose their slots (RBMAddon's Blood Altar Assembler); a machine that is not a
     * provider (AdvancedAE's Quantum Crafter keeps its own recipe slots there) is left out. There is
     * no compile dependency on any add-on, so it is found by reflection, once per class; a class
     * without the method is remembered too.
     */
    private static final ClassValue<Optional<Method>> PATTERN_INV_METHOD = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            if (PatternContainer.class.isAssignableFrom(type) || !ICraftingProvider.class.isAssignableFrom(type)) {
                return Optional.empty();
            }
            try {
                Method m = type.getMethod("getPatternInventory");
                if (InternalInventory.class.isAssignableFrom(m.getReturnType())) return Optional.of(m);
            } catch (NoSuchMethodException e) {
                LOG.debug("{} has no getPatternInventory()", type.getName());
            } catch (RuntimeException | LinkageError e) {
                LOG.debug("Looking up getPatternInventory() on {} failed", type.getName(), e);
            }
            return Optional.empty();
        }
    };

    private static boolean holdsPatterns(Class<?> c) {
        return PatternContainer.class.isAssignableFrom(c) || PATTERN_INV_METHOD.get(c).isPresent();
    }

    /** The pattern slots of one grid machine, or null when it has none (or the add-on's method failed). */
    private static InternalInventory patternInventory(Object owner) {
        try {
            if (owner instanceof PatternContainer pc) return pc.getTerminalPatternInventory();
            Optional<Method> m = PATTERN_INV_METHOD.get(owner.getClass());
            if (m.isEmpty()) return null;
            return (InternalInventory) m.get().invoke(owner);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // One misbehaving add-on host must not take the whole count down: leave it out.
            LOG.debug("Reading the pattern slots of {} failed", owner.getClass().getName(), e);
            return null;
        }
    }

    /**
     * Pattern slots used and free over every pattern holder on the grid. Holders are the machines
     * that implement AE2's PatternContainer (AE2 pattern providers, block and part, and add-ons such
     * as AdvancedAE's Advanced Pattern Provider and ExtendedAE's Assembler Matrix Pattern Core, which
     * is the same inventory the pattern terminal lists), plus crafting providers with a
     * getPatternInventory() (see {@link #PATTERN_INV_METHOD}). Machines are found the way drives()
     * does it: IGrid.getMachines only matches the exact class a machine registered under, so walk
     * the machine classes and read each node's owner. Classes that cannot hold patterns are skipped
     * without touching their nodes. Each owner and each inventory counts once.
     */
    static Map<String, Object> patternCapacity(IGrid grid) {
        Map<String, PatternKind> kinds = new LinkedHashMap<>();
        Set<Object> seenOwners = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<InternalInventory> seenInvs = Collections.newSetFromMap(new IdentityHashMap<>());
        long used = 0;
        long total = 0;
        for (Class<?> c : grid.getMachineClasses()) {
            if (!holdsPatterns(c)) continue;
            for (IGridNode n : grid.getMachineNodes(c)) {
                Object owner = n.getOwner();
                if (owner == null || !seenOwners.add(owner)) continue;
                try {
                    InternalInventory inv = patternInventory(owner);
                    if (inv == null || !seenInvs.add(inv)) continue;
                    int size = inv.size();
                    int filled = 0;
                    for (int i = 0; i < size; i++) {
                        if (!inv.getStackInSlot(i).isEmpty()) filled++;
                    }
                    String kind = hostKind(owner, n);
                    PatternKind k = kinds.get(kind);
                    if (k == null) {
                        k = new PatternKind(kind, hostDisplayName(owner, n));
                        kinds.put(kind, k);
                    }
                    k.count++;
                    k.used += filled;
                    k.total += size;
                    used += filled;
                    total += size;
                } catch (RuntimeException | LinkageError e) {
                    LOG.debug("Counting the pattern slots of {} failed", owner.getClass().getName(), e);
                }
            }
        }
        List<PatternKind> sorted = new ArrayList<>(kinds.values());
        sorted.sort(Comparator.<PatternKind>comparingLong(k -> k.total).reversed().thenComparing(k -> k.name));
        List<Object> hosts = new ArrayList<>();
        for (PatternKind k : sorted) {
            Map<String, Object> m = new HashMap<>();
            m.put("name", k.name);
            m.put("displayName", k.displayName);
            m.put("count", k.count);
            m.put("used", k.used);
            m.put("total", k.total);
            m.put("free", k.total - k.used);
            hosts.add(m);
        }
        Map<String, Object> out = new HashMap<>();
        out.put("used", used);
        out.put("total", total);
        out.put("free", total - used);
        out.put("hosts", hosts);
        return out;
    }

    /**
     * Registry id of a pattern holder's kind: its block, or for a part (no block) the item it shows
     * as; "unknown" when it is neither, as in devices(). Cheap, so it is done per host; the display
     * name is only resolved when the kind is first seen.
     */
    private static String hostKind(Object owner, IGridNode node) {
        if (owner instanceof BlockEntity be) {
            return BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).toString();
        }
        AEItemKey vis = node.getVisualRepresentation();
        return vis == null ? "unknown" : vis.getId().toString();
    }

    private static String hostDisplayName(Object owner, IGridNode node) {
        if (owner instanceof BlockEntity be) return be.getBlockState().getBlock().getName().getString();
        AEItemKey vis = node.getVisualRepresentation();
        return vis == null ? "Unknown" : vis.getDisplayName().getString();
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

    /** State of one getPatterns call: display names resolved so far, and how many stack entries were built. */
    private static final class Ctx {
        final ICraftingService cs;
        final boolean alternatives;
        final Map<AEKey, String> names = new HashMap<>();
        int entries;

        Ctx(ICraftingService cs, boolean alternatives) {
            this.cs = cs;
            this.alternatives = alternatives;
        }

        Map<String, Object> stack(AEKey key, long amount) {
            entries++;
            return MeBridgeMethods.stack(key, amount, cs, names);
        }
    }

    private static Map<String, Object> pattern(IPatternDetails p, Ctx ctx) {
        Map<String, Object> m = new HashMap<>();
        AEItemKey def = p.getDefinition();
        if (def != null) m.put("patternItem", def.getId().toString());
        m.put("patternType", patternType(p));
        GenericStack primary = p.getPrimaryOutput();
        if (primary != null && primary.what() != null) m.put("primaryOutput", ctx.stack(primary.what(), primary.amount()));
        List<Object> outputs = new ArrayList<>();
        for (GenericStack o : p.getOutputs()) {
            if (o != null && o.what() != null) outputs.add(ctx.stack(o.what(), o.amount()));
        }
        m.put("outputs", outputs);
        List<Object> inputs = new ArrayList<>();
        for (IPatternDetails.IInput in : p.getInputs()) {
            GenericStack[] possible = in.getPossibleInputs();
            if (possible == null || possible.length == 0 || possible[0] == null || possible[0].what() == null) continue;
            Map<String, Object> im = new HashMap<>();
            im.put("primaryInput", ctx.stack(possible[0].what(), possible[0].amount()));
            if (ctx.alternatives) {
                List<Object> alternatives = new ArrayList<>();
                boolean capped = false;
                for (GenericStack s : possible) {
                    if (s == null || s.what() == null) continue;
                    if (alternatives.size() >= MAX_ALTERNATIVES) {
                        capped = true;
                        break;
                    }
                    alternatives.add(ctx.stack(s.what(), s.amount()));
                }
                im.put("possibleInputs", alternatives);
                if (capped) im.put("alternativesTruncated", true);
            }
            im.put("multiplier", in.getMultiplier());
            AEKey remaining = in.getRemainingKey(possible[0].what());
            if (remaining != null) im.put("remaining", ctx.stack(remaining, 1));
            inputs.add(im);
        }
        m.put("inputs", inputs);
        return m;
    }

    /** Result of {@link #patterns}: the entries and whether a cap ({@link #MAX_PATTERNS}, {@link #MAX_ENTRIES}) cut the list. */
    record Patterns(List<Object> list, boolean truncated) {}

    private static final Comparator<AEKey> BY_ID =
            Comparator.<AEKey, String>comparing(k -> k.getId().toString()).thenComparing(k -> k.getType().getId().toString());

    /** Craftables are visited in id order, so a capped result is the same subset every time. */
    static Patterns patterns(IGrid grid, Predicate<AEKey> keep, boolean alternatives) {
        ICraftingService cs = grid.getCraftingService();
        Ctx ctx = new Ctx(cs, alternatives);
        List<AEKey> keys = new ArrayList<>();
        for (AEKey k : cs.getCraftables(k -> true)) {
            if (keep.test(k)) keys.add(k);
        }
        keys.sort(BY_ID);
        Set<IPatternDetails> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Object> out = new ArrayList<>();
        for (AEKey key : keys) {
            for (IPatternDetails p : cs.getCraftingFor(key)) {
                if (!seen.add(p)) continue;
                if (out.size() >= MAX_PATTERNS || ctx.entries >= MAX_ENTRIES) return new Patterns(out, true);
                out.add(pattern(p, ctx));
            }
        }
        return new Patterns(out, false);
    }

    /**
     * AE2 has no call that lists what the crafting service is requesting, only isRequestingAny and
     * the per-key isRequesting / getRequestedAmount. What it waits for are pattern outputs, and
     * getCraftables only indexes primary outputs, so byproducts would be missed. After the cheap
     * isRequestingAny check, the candidates are every output of every pattern of every craftable.
     */
    static List<Object> pendingRequests(IGrid grid) {
        ICraftingService cs = grid.getCraftingService();
        List<Object> out = new ArrayList<>();
        if (!cs.isRequestingAny()) return out;
        Set<AEKey> candidates = new LinkedHashSet<>();
        Set<IPatternDetails> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AEKey key : cs.getCraftables(k -> true)) {
            candidates.add(key);
            for (IPatternDetails p : cs.getCraftingFor(key)) {
                if (!seen.add(p)) continue;
                for (GenericStack o : p.getOutputs()) {
                    if (o != null && o.what() != null) candidates.add(o.what());
                }
            }
        }
        List<AEKey> sorted = new ArrayList<>(candidates);
        sorted.sort(BY_ID);
        for (AEKey key : sorted) {
            if (cs.isRequesting(key)) out.add(MeBridgeMethods.stack(key, cs.getRequestedAmount(key), cs));
        }
        return out;
    }
}
