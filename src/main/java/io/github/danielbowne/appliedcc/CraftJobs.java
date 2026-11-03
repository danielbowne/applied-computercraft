package io.github.danielbowne.appliedcc;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import dan200.computercraft.api.peripheral.IComputerAccess;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Predicate;

/**
 * AE2 crafting plans are calculated off-thread. Lua gets a job id back immediately; when the
 * plan finishes we submit it on the server thread and fire one "me_crafting" event:
 * os.pullEvent("me_crafting") -> id, ok, name, amount, message
 * message is STARTED, CANCELLED, NOT_CONNECTED, MISSING_INGREDIENTS: ..., an AE2 submit error
 * code (CPU_BUSY, NO_CPU_FOUND, ...) or ERROR: ...
 * The event means the job was handed to a crafting CPU, not that the items are finished.
 */
final class CraftJobs {
    private record Pending(int id, Future<ICraftingPlan> future, IGridNode node, IActionSource src,
                           ICraftingCPU cpu, IComputerAccess computer, AEKey key, long amount) {}

    private static final List<Pending> PENDING = new ArrayList<>();
    /** Events raised during a Lua call; fired next tick, since CC drops events queued while the caller waits. */
    private static final List<Pending> CANCELLED = new ArrayList<>();
    private static int nextId = 1;

    private CraftJobs() {}

    /** Same computer = same computer id; CC gives each peripheral attachment its own IComputerAccess. */
    private static boolean sameComputer(Pending p, IComputerAccess computer) {
        return p.computer() == computer || p.computer().getID() == computer.getID();
    }

    static int pendingFor(IComputerAccess computer) {
        int n = 0;
        for (Pending p : PENDING) if (sameComputer(p, computer)) n++;
        return n;
    }

    static int add(Future<ICraftingPlan> future, IGridNode node, IActionSource src, ICraftingCPU cpu,
                   IComputerAccess computer, AEKey key, long amount) {
        int id = nextId++;
        PENDING.add(new Pending(id, future, node, src, cpu, computer, key, amount));
        return id;
    }

    /** Cancel requests from this computer that are still calculating. Returns how many. */
    static int cancelPending(IComputerAccess computer, Predicate<AEKey> match) {
        int n = 0;
        Iterator<Pending> it = PENDING.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (!sameComputer(p, computer) || !match.test(p.key())) continue;
            it.remove();
            p.future().cancel(true);
            CANCELLED.add(p);
            n++;
        }
        return n;
    }

    static void tick() {
        if (!CANCELLED.isEmpty()) {
            CANCELLED.forEach(p -> fire(p, false, "CANCELLED"));
            CANCELLED.clear();
        }
        if (PENDING.isEmpty()) return;
        Iterator<Pending> it = PENDING.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (!p.future().isDone()) continue;
            it.remove();
            try {
                ICraftingPlan plan = p.future().get();
                if (plan.simulation()) {
                    StringBuilder missing = new StringBuilder("MISSING_INGREDIENTS:");
                    for (var e : plan.missingItems()) {
                        missing.append(' ').append(e.getLongValue()).append('x').append(e.getKey().getId());
                    }
                    fire(p, false, missing.toString());
                    continue;
                }
                IGrid grid;
                try {
                    grid = p.node().isActive() ? p.node().getGrid() : null;
                } catch (IllegalStateException destroyed) {
                    grid = null; // block broken while the plan was calculating
                }
                if (grid == null) {
                    fire(p, false, "NOT_CONNECTED");
                    continue;
                }
                ICraftingSubmitResult result = grid.getCraftingService()
                        .submitJob(plan, null, p.cpu(), false, p.src());
                fire(p, result.successful(), result.successful() ? "STARTED" : String.valueOf(result.errorCode()));
            } catch (Exception ex) {
                fire(p, false, "ERROR: " + ex.getMessage());
            }
        }
    }

    static void clear() {
        PENDING.forEach(p -> p.future().cancel(true));
        PENDING.clear();
        CANCELLED.clear();
    }

    private static void fire(Pending p, boolean ok, String message) {
        try {
            p.computer().queueEvent("me_crafting", p.id(), ok, p.key().getId().toString(), p.amount(), message);
        } catch (RuntimeException ignored) {
            // computer detached or unloaded since the request; nothing to notify
        }
    }
}
