package io.github.danielbowne.appliedcc;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.parts.IPartHost;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.AEKeyFilter;
import appeng.api.storage.MEStorage;
import com.mojang.logging.LogUtils;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.GenericPeripheral;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.PeripheralType;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Generic peripheral attached to every AE2 block that hosts a grid node (controller, interface,
 * drive, energy acceptor, cable bus...). Method names follow Advanced Peripherals' ME Bridge where
 * they overlap: peripheral.find("me_bridge").
 */
public final class MeBridgeMethods implements GenericPeripheral {
    private static final Logger LOG = LogUtils.getLogger();
    private static final long DEFAULT_ITEM_COUNT = 64;
    private static final long DEFAULT_FLUID_COUNT = 1000;

    @Override
    public String id() {
        return AppliedCC.MOD_ID + ":me_bridge";
    }

    @Override
    public PeripheralType getType() {
        return PeripheralType.ofType("me_bridge");
    }

    // ---------------------------------------------------------------- status

    @LuaFunction(mainThread = true)
    public boolean isConnected(IInWorldGridNodeHost host) throws LuaException {
        return grid(host) != null;
    }

    @LuaFunction(mainThread = true)
    public boolean isOnline(IInWorldGridNodeHost host) throws LuaException {
        checkAllowed(host);
        IGridNode node = node(host);
        return node != null && node.isOnline();
    }

    // ---------------------------------------------------------------- listing

    @LuaFunction(value = {"getItems", "listItems"}, mainThread = true)
    public MethodResult getItems(IInWorldGridNodeHost host, IArguments args) throws LuaException {
        Filter f = Filter.opt(args, 0, DEFAULT_ITEM_COUNT);
        return list(host, k -> k instanceof AEItemKey && f.matches(k));
    }

    @LuaFunction(value = {"getFluids", "listFluids"}, mainThread = true)
    public MethodResult getFluids(IInWorldGridNodeHost host, IArguments args) throws LuaException {
        Filter f = Filter.opt(args, 0, DEFAULT_FLUID_COUNT);
        return list(host, k -> k instanceof AEFluidKey && f.matches(k));
    }

    /** Every stored key of every AE2 key type (items, fluids, and whatever add-ons register, such as FE). */
    @LuaFunction(value = {"getResources", "listResources"}, mainThread = true)
    public MethodResult getResources(IInWorldGridNodeHost host, IArguments args) throws LuaException {
        Filter f = Filter.opt(args, 0, DEFAULT_ITEM_COUNT);
        return list(host, f::matches);
    }

    @LuaFunction(mainThread = true)
    public MethodResult getResource(IInWorldGridNodeHost host, IArguments args) throws LuaException {
        return one(host, Filter.reqResource(args, 0, DEFAULT_ITEM_COUNT), AEKey.class);
    }

    @LuaFunction(mainThread = true)
    public MethodResult getItem(IInWorldGridNodeHost host, IArguments args) throws LuaException {
        return one(host, Filter.req(args, 0, DEFAULT_ITEM_COUNT), AEItemKey.class);
    }

    @LuaFunction(mainThread = true)
    public MethodResult getFluid(IInWorldGridNodeHost host, IArguments args) throws LuaException {
        return one(host, Filter.req(args, 0, DEFAULT_FLUID_COUNT), AEFluidKey.class);
    }

    @LuaFunction(value = {"getCraftableItems", "listCraftableItems"}, mainThread = true)
    public MethodResult getCraftableItems(IInWorldGridNodeHost host, IArguments args) throws LuaException {
        return craftables(host, AEItemKey.filter(), Filter.opt(args, 0, DEFAULT_ITEM_COUNT));
    }

    @LuaFunction(value = {"getCraftableFluids", "listCraftableFluids"}, mainThread = true)
    public MethodResult getCraftableFluids(IInWorldGridNodeHost host, IArguments args) throws LuaException {
        return craftables(host, AEFluidKey.filter(), Filter.opt(args, 0, DEFAULT_FLUID_COUNT));
    }

    // ---------------------------------------------------------------- crafting

    @LuaFunction(mainThread = true)
    public MethodResult isCraftable(IInWorldGridNodeHost host, IArguments args) throws LuaException {
        return craftableCheck(host, args, AEKey.class);
    }

    @LuaFunction(mainThread = true)
    public MethodResult isItemCraftable(IInWorldGridNodeHost host, IArguments args) throws LuaException {
        return craftableCheck(host, args, AEItemKey.class);
    }

    @LuaFunction(mainThread = true)
    public MethodResult isFluidCraftable(IInWorldGridNodeHost host, IArguments args) throws LuaException {
        return craftableCheck(host, args, AEFluidKey.class);
    }

    @LuaFunction(value = {"isCrafting", "isItemCrafting"}, mainThread = true)
    public MethodResult isCrafting(IInWorldGridNodeHost host, IArguments args) throws LuaException {
        IGrid grid = grid(host);
        if (grid == null) return notConnected(false);
        Filter f = Filter.req(args, 0, 1);
        for (ICraftingCPU cpu : grid.getCraftingService().getCpus()) {
            CraftingJobStatus st = cpu.getJobStatus();
            if (st != null && f.matches(st.crafting().what())) return MethodResult.of(true);
        }
        return MethodResult.of(false);
    }

    /** craftItem({name=..., count=64} [, cpuName]) -> jobId; the outcome arrives as a "me_crafting" event. */
    @LuaFunction(mainThread = true)
    public MethodResult craftItem(IInWorldGridNodeHost host, IComputerAccess computer, IArguments args) throws LuaException {
        return craft(host, computer, args, Filter.req(args, 0, DEFAULT_ITEM_COUNT), AEItemKey.class);
    }

    /** craftFluid({name=..., count=1000} [, cpuName]) -> jobId; count is in mB. */
    @LuaFunction(mainThread = true)
    public MethodResult craftFluid(IInWorldGridNodeHost host, IComputerAccess computer, IArguments args) throws LuaException {
        return craft(host, computer, args, Filter.req(args, 0, DEFAULT_FLUID_COUNT), AEFluidKey.class);
    }

    private MethodResult craft(IInWorldGridNodeHost host, IComputerAccess computer, IArguments args, Filter f,
                               Class<? extends AEKey> type) throws LuaException {
        Config.require(Config.ALLOW_CRAFTING, "Crafting");
        IGrid grid = grid(host);
        if (grid == null) return notConnected(null);
        IGridNode node = node(host);
        ICraftingService cs = grid.getCraftingService();
        AEKey key = findCraftable(cs, f, type);
        if (key == null) return MethodResult.of(null, "NOT_CRAFTABLE");
        if (CraftJobs.pendingFor(computer) >= Config.MAX_PENDING_CRAFTS.get()) {
            return MethodResult.of(null, "TOO_MANY_JOBS");
        }

        ICraftingCPU cpu = null;
        String cpuName = args.optString(1, "");
        if (!cpuName.isEmpty()) {
            cpu = findCpu(cs, cpuName);
            if (cpu == null) return MethodResult.of(null, "CPU_NOT_FOUND");
        }

        IActionSource src = IActionSource.ofMachine(() -> node);
        ICraftingSimulationRequester requester = new ICraftingSimulationRequester() {
            @Override public IActionSource getActionSource() { return src; }
            @Override public IGridNode getGridNode() { return node; }
        };
        var future = cs.beginCraftingCalculation(node.getLevel(), requester, key, f.count,
                CalculationStrategy.REPORT_MISSING_ITEMS);
        return MethodResult.of(CraftJobs.add(future, node, src, cpu, computer, key, f.count));
    }

    @LuaFunction(mainThread = true)
    public MethodResult getCraftingCPUs(IInWorldGridNodeHost host) throws LuaException {
        IGrid grid = grid(host);
        if (grid == null) return notConnected(null);
        ICraftingService cs = grid.getCraftingService();
        List<Object> out = new ArrayList<>();
        int n = 0;
        for (ICraftingCPU cpu : cs.getCpus()) {
            Map<String, Object> m = cpuInfo(cpu, ++n);
            CraftingJobStatus st = cpu.getJobStatus();
            if (st != null) m.put("craftingJob", job(st, cs));
            out.add(m);
        }
        return MethodResult.of(out);
    }

    @LuaFunction(mainThread = true)
    public MethodResult getCraftingTasks(IInWorldGridNodeHost host) throws LuaException {
        IGrid grid = grid(host);
        if (grid == null) return notConnected(null);
        ICraftingService cs = grid.getCraftingService();
        List<Object> out = new ArrayList<>();
        int n = 0;
        for (ICraftingCPU cpu : cs.getCpus()) {
            n++;
            CraftingJobStatus st = cpu.getJobStatus();
            if (st == null) continue;
            Map<String, Object> m = job(st, cs);
            m.put("cpu", cpuInfo(cpu, n));
            out.add(m);
        }
        return MethodResult.of(out);
    }

    /**
     * cancelCraftingTasks(filter): cancels running jobs for the filtered resource, plus this
     * computer's requests that are still calculating. A filter is required; pass {all=true} to
     * cancel every job on the network. Returns how many were cancelled.
     */
    @LuaFunction(mainThread = true)
    public MethodResult cancelCraftingTasks(IInWorldGridNodeHost host, IComputerAccess computer, IArguments args) throws LuaException {
        Config.require(Config.ALLOW_CANCEL, "Cancelling crafting jobs");
        IGrid grid = grid(host);
        if (grid == null) return notConnected(0);
        Filter f = Filter.cancel(args, 0);
        int n = CraftJobs.cancelPending(computer, f::matches);
        for (ICraftingCPU cpu : grid.getCraftingService().getCpus()) {
            CraftingJobStatus st = cpu.getJobStatus();
            if (st != null && f.matches(st.crafting().what())) {
                cpu.cancelJob();
                n++;
            }
        }
        return MethodResult.of(n);
    }

    // ---------------------------------------------------------------- energy

    @LuaFunction(mainThread = true)
    public MethodResult getStoredEnergy(IInWorldGridNodeHost host) throws LuaException {
        IGrid g = grid(host, false);
        return g == null ? notConnected(0) : MethodResult.of(g.getEnergyService().getStoredPower());
    }

    @LuaFunction(mainThread = true)
    public MethodResult getEnergyCapacity(IInWorldGridNodeHost host) throws LuaException {
        IGrid g = grid(host, false);
        return g == null ? notConnected(0) : MethodResult.of(g.getEnergyService().getMaxStoredPower());
    }

    @LuaFunction(mainThread = true)
    public MethodResult getEnergyUsage(IInWorldGridNodeHost host) throws LuaException {
        IGrid g = grid(host, false);
        return g == null ? notConnected(0) : MethodResult.of(g.getEnergyService().getAvgPowerUsage());
    }

    @LuaFunction(mainThread = true)
    public MethodResult getAverageEnergyInput(IInWorldGridNodeHost host) throws LuaException {
        IGrid g = grid(host, false);
        return g == null ? notConnected(0) : MethodResult.of(g.getEnergyService().getAvgPowerInjection());
    }

    @LuaFunction(mainThread = true)
    public MethodResult getEnergyInfo(IInWorldGridNodeHost host) throws LuaException {
        IGrid g = grid(host, false);
        if (g == null) return notConnected(null);
        IEnergyService e = g.getEnergyService();
        Map<String, Object> m = new HashMap<>();
        m.put("stored", e.getStoredPower());
        m.put("capacity", e.getMaxStoredPower());
        m.put("usage", e.getAvgPowerUsage());
        m.put("idleUsage", e.getIdlePowerUsage());
        m.put("input", e.getAvgPowerInjection());
        m.put("powered", e.isNetworkPowered());
        return MethodResult.of(m);
    }

    // ---------------------------------------------------------------- moving stuff

    /** exportItem({name=..., count=64}, "up"|"top"|"north"|...|peripheralName) -> amount moved */
    @LuaFunction(mainThread = true)
    public MethodResult exportItem(IInWorldGridNodeHost host, IComputerAccess computer, IArguments args) throws LuaException {
        Config.require(Config.ALLOW_EXPORT, "Exporting");
        return export(host, computer, Filter.req(args, 0, DEFAULT_ITEM_COUNT), args.getString(1),
                Capabilities.Item.BLOCK, AEItemKey.class, k -> ((AEItemKey) k).toResource());
    }

    @LuaFunction(mainThread = true)
    public MethodResult exportFluid(IInWorldGridNodeHost host, IComputerAccess computer, IArguments args) throws LuaException {
        Config.require(Config.ALLOW_EXPORT, "Exporting");
        return export(host, computer, Filter.req(args, 0, DEFAULT_FLUID_COUNT), args.getString(1),
                Capabilities.Fluid.BLOCK, AEFluidKey.class, k -> ((AEFluidKey) k).toResource());
    }

    /** importItem({name=..., count=64} or {} for anything, direction|peripheralName) -> amount moved */
    @LuaFunction(mainThread = true)
    public MethodResult importItem(IInWorldGridNodeHost host, IComputerAccess computer, IArguments args) throws LuaException {
        Config.require(Config.ALLOW_IMPORT, "Importing");
        return importInto(host, computer, Filter.opt(args, 0, DEFAULT_ITEM_COUNT), args.getString(1),
                Capabilities.Item.BLOCK, r -> AEItemKey.of((ItemResource) r));
    }

    @LuaFunction(mainThread = true)
    public MethodResult importFluid(IInWorldGridNodeHost host, IComputerAccess computer, IArguments args) throws LuaException {
        Config.require(Config.ALLOW_IMPORT, "Importing");
        return importInto(host, computer, Filter.opt(args, 0, DEFAULT_FLUID_COUNT), args.getString(1),
                Capabilities.Fluid.BLOCK, r -> AEFluidKey.of((FluidResource) r));
    }

    /*
     * Never call into ME storage while one of our NeoForge transactions is open: AE2's storage bus
     * opens its own root transaction and NeoForge forbids two roots on one thread. So each side is
     * moved in its own step, and any shortfall is handed back to where it came from.
     */

    private <R extends Resource> MethodResult export(IInWorldGridNodeHost host, IComputerAccess computer, Filter f,
                                                     String target, BlockCapability<ResourceHandler<R>, Direction> cap,
                                                     Class<? extends AEKey> keyType, Function<AEKey, R> toResource) throws LuaException {
        IGrid grid = grid(host);
        if (grid == null) return notConnected(0);
        ResourceHandler<R> handler = resolve(host, computer, target, cap);
        if (handler == null) return MethodResult.of(0, "INVENTORY_NOT_FOUND");
        MEStorage storage = grid.getStorageService().getInventory();
        IActionSource src = IActionSource.ofMachine(() -> node(host));

        AEKey key = null;
        for (var e : grid.getStorageService().getCachedInventory()) {
            if (keyType.isInstance(e.getKey()) && e.getLongValue() > 0 && f.matches(e.getKey())) {
                key = e.getKey();
                break;
            }
        }
        if (key == null) return MethodResult.of(0, "NOT_FOUND");

        R res = toResource.apply(key);
        long avail = storage.extract(key, f.count, Actionable.SIMULATE, src);
        int want = (int) Math.min(avail, Integer.MAX_VALUE);
        if (want <= 0) return MethodResult.of(0);

        int accepted;
        try (Transaction tx = Transaction.openRoot()) {
            accepted = handler.insert(res, want, tx); // never committed: a simulation
        }
        if (accepted <= 0) return MethodResult.of(0, "TARGET_FULL");

        long taken = storage.extract(key, accepted, Actionable.MODULATE, src);
        int inserted = 0;
        try {
            try (Transaction tx = Transaction.openRoot()) {
                inserted = handler.insert(res, (int) taken, tx);
                tx.commit();
            }
        } finally {
            long left = taken - inserted;
            if (left > 0) {
                long back = storage.insert(key, left, Actionable.MODULATE, src);
                if (back < left) LOG.warn("exportItem: {} x {} could not be returned to the ME network and was lost", left - back, key.getId());
            }
        }
        return MethodResult.of(inserted);
    }

    private <R extends Resource> MethodResult importInto(IInWorldGridNodeHost host, IComputerAccess computer, Filter f,
                                                         String target, BlockCapability<ResourceHandler<R>, Direction> cap,
                                                         Function<R, AEKey> toKey) throws LuaException {
        IGrid grid = grid(host);
        if (grid == null) return notConnected(0);
        ResourceHandler<R> handler = resolve(host, computer, target, cap);
        if (handler == null) return MethodResult.of(0, "INVENTORY_NOT_FOUND");
        MEStorage storage = grid.getStorageService().getInventory();
        IActionSource src = IActionSource.ofMachine(() -> node(host));

        long remaining = f.count;
        long moved = 0;
        for (int slot = 0; slot < handler.size() && remaining > 0; slot++) {
            R res = handler.getResource(slot);
            if (res == null || res.isEmpty()) continue;
            AEKey key = toKey.apply(res);
            if (key == null || !f.matches(key)) continue;
            long amount = Math.min(handler.getAmountAsLong(slot), remaining);
            long fits = storage.insert(key, amount, Actionable.SIMULATE, src);
            if (fits <= 0) continue;

            int extracted;
            try (Transaction tx = Transaction.openRoot()) {
                extracted = handler.extract(slot, res, (int) Math.min(fits, Integer.MAX_VALUE), tx);
                tx.commit();
            }
            if (extracted <= 0) continue;

            long stored = 0;
            try {
                stored = storage.insert(key, extracted, Actionable.MODULATE, src);
            } finally {
                // whatever ME did not take (including everything, if the insert threw) goes back to the source
                long left = extracted - stored;
                if (left > 0) {
                    int back = 0;
                    try (Transaction tx = Transaction.openRoot()) {
                        back = handler.insert(slot, res, (int) left, tx);
                        if (back < left) back += handler.insert(res, (int) (left - back), tx);
                        tx.commit();
                    } catch (RuntimeException e) {
                        LOG.warn("importItem: returning {} x {} to the source inventory failed", left, key.getId(), e);
                    }
                    if (back < left) LOG.warn("importItem: {} x {} could not be returned to the source inventory and was lost", left - back, key.getId());
                }
            }
            moved += stored;
            remaining -= stored;
            if (stored < extracted) break; // ME is full for this resource
        }
        return MethodResult.of(moved);
    }

    /**
     * Target is a direction relative to the AE block ("up"/"top", "down"/"bottom", "north", ...)
     * or the name of a peripheral the computer can see (a side like "left" or a wired name).
     */
    @SuppressWarnings("unchecked")
    private static <R extends Resource> ResourceHandler<R> resolve(IInWorldGridNodeHost host, IComputerAccess computer,
                                                                   String target, BlockCapability<ResourceHandler<R>, Direction> cap) {
        String t = target.toLowerCase(Locale.ROOT);
        Direction dir = switch (t) {
            case "top" -> Direction.UP;
            case "bottom" -> Direction.DOWN;
            default -> Direction.byName(t);
        };
        if (dir != null && host instanceof BlockEntity be && be.getLevel() != null) {
            return be.getLevel().getCapability(cap, be.getBlockPos().relative(dir), dir.getOpposite());
        }
        IPeripheral p = computer.getAvailablePeripheral(target);
        if (p == null) return null;
        Object obj = p.getTarget();
        if (obj instanceof BlockEntity be && be.getLevel() != null) {
            return be.getLevel().getCapability(cap, be.getBlockPos(), null);
        }
        if (obj instanceof ResourceHandler<?> rh) return (ResourceHandler<R>) rh;
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private static IGridNode node(IInWorldGridNodeHost host) {
        IGridNode n = host.getGridNode(null);
        if (n != null) return n;
        for (Direction d : Direction.values()) {
            n = host.getGridNode(d);
            if (n != null) return n;
        }
        return null;
    }

    /** The grid if the bridge may use it right now; null if not connected. Throws when the server disabled it. */
    private static IGrid grid(IInWorldGridNodeHost host) throws LuaException {
        return grid(host, true);
    }

    /** requireActive=false is for read-only energy readouts, which matter most when the network is unpowered. */
    private static IGrid grid(IInWorldGridNodeHost host, boolean requireActive) throws LuaException {
        checkAllowed(host);
        IGridNode n = node(host);
        if (n == null || !n.hasGridBooted() || (requireActive && !n.isActive())) return null;
        try {
            return n.getGrid();
        } catch (IllegalStateException destroyed) {
            return null;
        }
    }

    private static void checkAllowed(IInWorldGridNodeHost host) throws LuaException {
        if (!Config.ENABLED.get()) throw new LuaException("Applied ComputerCraft is disabled on this server");
        if (host instanceof IPartHost && !Config.ALLOW_ON_CABLES.get()) {
            throw new LuaException("The ME bridge only works on full AE2 blocks on this server, not cables");
        }
    }

    private static MethodResult notConnected(Object value) {
        return MethodResult.of(value, "NOT_CONNECTED");
    }

    private MethodResult list(IInWorldGridNodeHost host, Predicate<AEKey> keep) throws LuaException {
        IGrid grid = grid(host);
        if (grid == null) return notConnected(null);
        ICraftingService cs = grid.getCraftingService();
        List<Object> out = new ArrayList<>();
        for (var e : grid.getStorageService().getCachedInventory()) {
            if (e.getLongValue() > 0 && keep.test(e.getKey())) out.add(stack(e.getKey(), e.getLongValue(), cs));
        }
        return MethodResult.of(out);
    }

    private MethodResult one(IInWorldGridNodeHost host, Filter f, Class<? extends AEKey> type) throws LuaException {
        IGrid grid = grid(host);
        if (grid == null) return notConnected(null);
        ICraftingService cs = grid.getCraftingService();
        for (var e : grid.getStorageService().getCachedInventory()) {
            if (type.isInstance(e.getKey()) && f.matches(e.getKey())) return MethodResult.of(stack(e.getKey(), e.getLongValue(), cs));
        }
        AEKey craftable = findCraftable(cs, f, type);
        if (craftable != null) return MethodResult.of(stack(craftable, 0, cs));
        return MethodResult.of(null, "NOT_FOUND");
    }

    private MethodResult craftables(IInWorldGridNodeHost host, AEKeyFilter type, Filter f) throws LuaException {
        IGrid grid = grid(host);
        if (grid == null) return notConnected(null);
        ICraftingService cs = grid.getCraftingService();
        var cached = grid.getStorageService().getCachedInventory();
        List<Object> out = new ArrayList<>();
        for (AEKey k : cs.getCraftables(type)) {
            if (f.matches(k)) out.add(stack(k, cached.get(k), cs));
        }
        return MethodResult.of(out);
    }

    private MethodResult craftableCheck(IInWorldGridNodeHost host, IArguments args, Class<? extends AEKey> type) throws LuaException {
        IGrid grid = grid(host);
        if (grid == null) return notConnected(false);
        return MethodResult.of(findCraftable(grid.getCraftingService(), Filter.req(args, 0, 1), type) != null);
    }

    private static AEKey findCraftable(ICraftingService cs, Filter f, Class<? extends AEKey> type) {
        for (AEKey k : cs.getCraftables(type::isInstance)) {
            if (f.matches(k)) return k;
        }
        return null;
    }

    /** The CPU's own name, or "CPU #n" (n = 1-based position in getCpus()) when unnamed, like AE2's screen. */
    private static String cpuLabel(ICraftingCPU cpu, int position) {
        return cpu.getName() != null ? cpu.getName().getString() : "CPU #" + position;
    }

    /**
     * Finds a CPU by the name it was given in AE2, else by the "CPU #n" label of an unnamed CPU
     * (n = 1-based position in getCpus()), else, for 0.1.0 compatibility, "Unnamed" for the first
     * unnamed CPU. A CPU literally named "CPU #3" wins over the unnamed CPU in position 3.
     */
    private static ICraftingCPU findCpu(ICraftingService cs, String wanted) {
        for (ICraftingCPU c : cs.getCpus()) {
            if (c.getName() != null && wanted.equals(c.getName().getString())) return c;
        }
        int n = 0;
        for (ICraftingCPU c : cs.getCpus()) {
            n++;
            if (c.getName() != null) continue;
            if (wanted.equals(cpuLabel(c, n)) || wanted.equals("Unnamed")) return c;
        }
        return null;
    }

    private static Map<String, Object> cpuInfo(ICraftingCPU cpu, int position) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", cpuLabel(cpu, position));
        m.put("storage", cpu.getAvailableStorage());
        m.put("coProcessors", cpu.getCoProcessors());
        m.put("isBusy", cpu.isBusy());
        m.put("selectionMode", String.valueOf(cpu.getSelectionMode()));
        return m;
    }

    private static Map<String, Object> stack(AEKey key, long amount, ICraftingService cs) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", key.getId().toString());
        m.put("displayName", key.getDisplayName().getString());
        m.put("count", amount);
        m.put("amount", amount);
        m.put("isCraftable", cs != null && cs.isCraftable(key));
        String keyType = key.getType().getId().toString();
        m.put("type", key instanceof AEFluidKey ? "fluid" : key instanceof AEItemKey ? "item" : keyType);
        m.put("keyType", keyType);
        String unit = key.getUnitSymbol();
        if (unit != null) m.put("unit", unit);
        m.put("amountPerUnit", key.getAmountPerUnit());
        if (key instanceof AEItemKey ik) {
            m.put("maxStackSize", ik.getMaxStackSize());
            m.put("hasComponents", ik.hasComponents());
        }
        return m;
    }

    private static Map<String, Object> job(CraftingJobStatus st, ICraftingService cs) {
        Map<String, Object> m = new HashMap<>();
        m.put("resource", stack(st.crafting().what(), st.crafting().amount(), cs));
        m.put("quantity", st.crafting().amount());
        m.put("totalItems", st.totalItems());
        m.put("progress", st.progress());
        m.put("completion", st.totalItems() > 0 ? st.progress() / (double) st.totalItems() : 0.0);
        return m;
    }

    /**
     * Lua filter: a string id, or a table with any of
     *   name = "minecraft:iron_ingot"   exact id
     *   name = "#c:ingots"              tag (item or fluid tag, by key type)
     *   displayName = "iron"            case-insensitive substring
     *   keyType = "appflux:flux"        exact AE2 key type id (see the keyType field of getResources)
     *   type = "item" | "fluid" | an AE2 key type id   same idea, with the short names for items and fluids
     * keyType and type only narrow a search. Methods that move, craft or cancel need a name, tag or
     * displayName as well; only getResources and getResource accept a keyType or type on its own.
     *   count / amount = 64             how many to move or craft
     * Components are not matched: a name matches every variant of that id.
     */
    private static final class Filter {
        final String name;
        final Identifier tag;
        final String displayName;
        final String keyType;
        final String type;
        final boolean all;
        final long count;

        private Filter(String name, String displayName, String keyType, String type, boolean all, long count) throws LuaException {
            if (name != null && name.startsWith("#")) {
                Identifier id = name.length() > 1 ? Identifier.tryParse(name.substring(1)) : null;
                if (id == null) throw new LuaException("bad tag: " + name);
                this.tag = id;
                this.name = null;
            } else {
                this.tag = null;
                this.name = name;
            }
            this.displayName = displayName;
            this.keyType = keyType;
            this.type = type;
            this.all = all;
            this.count = count;
        }

        static Filter req(IArguments args, int i, long defCount) throws LuaException {
            Filter f = opt(args, i, defCount);
            if (f.isEmpty()) {
                throw new LuaException("filter needs a name, tag or displayName, e.g. {name=\"minecraft:iron_ingot\"}");
            }
            return f;
        }

        /** getResource: like req, but a keyType or type alone is enough (it returns the first match). */
        static Filter reqResource(IArguments args, int i, long defCount) throws LuaException {
            Filter f = opt(args, i, defCount);
            if (f.isEmpty() && f.keyType == null && f.type == null) {
                throw new LuaException("filter needs a name, tag, displayName, keyType or type, e.g. {keyType=\"appflux:flux\"}");
            }
            return f;
        }

        /** Cancel needs a real filter or an explicit {all=true}, so a nil argument can't wipe every job. */
        static Filter cancel(IArguments args, int i) throws LuaException {
            Filter f = opt(args, i, 1);
            if (f.isEmpty() && !f.all) throw new LuaException("pass a filter with a name, tag or displayName, or {all=true} to cancel every job on the network");
            return f;
        }

        static Filter opt(IArguments args, int i, long defCount) throws LuaException {
            if (args.count() <= i || args.get(i) == null) return new Filter(null, null, null, null, false, defCount);
            Object raw = args.get(i);
            if (raw instanceof String s) return new Filter(s, null, null, null, false, defCount);
            Map<?, ?> t = args.getTable(i);
            String name = t.get("name") instanceof String s ? s : null;
            String dn = t.get("displayName") instanceof String s ? s.toLowerCase(Locale.ROOT) : null;
            String keyType = t.get("keyType") instanceof String s ? s : null;
            String type = t.get("type") instanceof String s ? s : null;
            boolean all = Boolean.TRUE.equals(t.get("all"));
            long count = t.get("count") instanceof Number n ? n.longValue()
                    : t.get("amount") instanceof Number n2 ? n2.longValue() : defCount;
            if (count < 1) throw new LuaException("count must be >= 1");
            return new Filter(name, dn, keyType, type, all, count);
        }

        boolean isEmpty() {
            return name == null && tag == null && displayName == null;
        }

        private boolean matchesType(AEKey k) {
            return switch (type) {
                case "item" -> k instanceof AEItemKey;
                case "fluid" -> k instanceof AEFluidKey;
                default -> type.equals(k.getType().getId().toString());
            };
        }

        boolean matches(AEKey k) {
            if (name != null && !name.equals(k.getId().toString())) return false;
            if (keyType != null && !keyType.equals(k.getType().getId().toString())) return false;
            if (type != null && !matchesType(k)) return false;
            if (tag != null) {
                boolean tagged = k instanceof AEItemKey ? k.isTagged(TagKey.create(Registries.ITEM, tag))
                        : k instanceof AEFluidKey && k.isTagged(TagKey.create(Registries.FLUID, tag));
                if (!tagged) return false;
            }
            return displayName == null || k.getDisplayName().getString().toLowerCase(Locale.ROOT).contains(displayName);
        }
    }
}
