package io.github.danielbowne.appliedcc;

import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import com.mojang.logging.LogUtils;
import dan200.computercraft.api.lua.LuaException;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Read and change the IO mode of an AppliedFlux Flux Accessor, block or cable-mounted part.
 * AppliedFlux is an optional dependency, so nothing here names one of its classes at compile time:
 * the classes and methods are looked up once by reflection (see {@link #API}) and every failure to
 * do so, including a missing mod, means "not available".
 *
 * <p>Only the two accessor classes are matched, not AppliedFlux's IEnergyDistributor interface:
 * its mixins add that interface to AE2's interfaces and pattern providers, where the IO methods are
 * inert defaults.
 *
 * <p>AppliedFlux's setIOMode and setFastMode only store the field. Its own GUI does the same and
 * relies on the value being saved with the next chunk save, so after a change this class marks the
 * block entity (or the part's host) as needing a save. No client update is needed: the accessor's
 * screen reads the values through the menu, and the block has no mode-dependent model.
 */
final class FluxControl {
    private static final Logger LOG = LogUtils.getLogger();

    private static final String TILE_CLASS = "com.glodblock.github.appflux.common.tileentities.TileFluxAccessor";
    private static final String PART_CLASS = "com.glodblock.github.appflux.common.parts.PartFluxAccessor";
    private static final String IO_CLASS = "com.glodblock.github.appflux.api.EnergyIO";

    static final String STATUS_MISSING = "APPFLUX_NOT_LOADED";
    static final String STATUS_NOT_ACCESSOR = "NOT_FLUX_ACCESSOR";

    private FluxControl() {}

    /** The four reflective handles of one accessor class. */
    private record Handles(Class<?> type, Method getMode, Method setMode, Method isFast, Method setFast) {
        static Handles of(Class<?> type, Class<?> ioType) throws NoSuchMethodException {
            return new Handles(type,
                    type.getMethod("getIOMode"),
                    type.getMethod("setIOMode", ioType),
                    type.getMethod("isFastMode"),
                    type.getMethod("setFastMode", boolean.class));
        }
    }

    private record Api(Handles tile, Handles part, Object[] modes) {}

    /** Resolved on first use. Empty when AppliedFlux is absent or is a version with a different API. */
    private static final class Holder {
        static final Optional<Api> API = load();

        private static Optional<Api> load() {
            try {
                ClassLoader cl = FluxControl.class.getClassLoader();
                Class<?> io = Class.forName(IO_CLASS, false, cl);
                Object[] modes = io.getEnumConstants();
                if (modes == null) throw new NoSuchMethodException(IO_CLASS + " is not an enum");
                Handles tile = Handles.of(Class.forName(TILE_CLASS, false, cl), io);
                Handles part = Handles.of(Class.forName(PART_CLASS, false, cl), io);
                return Optional.of(new Api(tile, part, modes));
            } catch (ClassNotFoundException e) {
                LOG.debug("AppliedFlux is not present, getFluxMode and setFluxMode are unavailable");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOG.warn("AppliedFlux is present but its Flux Accessor API is not what Applied ComputerCraft expects", e);
            }
            return Optional.empty();
        }
    }

    /** True when AppliedFlux's accessor classes were found. */
    static boolean available() {
        return Holder.API.isPresent();
    }

    /** One accessor: the object, its handles, and what to call to have it saved. */
    static final class Target {
        private final Object accessor;
        private final Handles handles;
        private final Runnable markSaved;

        private Target(Object accessor, Handles handles, Runnable markSaved) {
            this.accessor = accessor;
            this.handles = handles;
            this.markSaved = markSaved;
        }

        /** "input", "output" or "both". */
        String mode() throws LuaException {
            return ((Enum<?>) invoke(handles.getMode(), accessor)).name().toLowerCase(Locale.ROOT);
        }

        boolean fast() throws LuaException {
            return (Boolean) invoke(handles.isFast(), accessor);
        }

        /** mode is already validated by {@link FluxControl#parseMode}. */
        void setMode(Object mode) throws LuaException {
            invoke(handles.setMode(), accessor, mode);
            markSaved.run();
        }

        void setFast(boolean fast) throws LuaException {
            invoke(handles.setFast(), accessor, fast);
            markSaved.run();
        }
    }

    /**
     * The Flux Accessor behind a bridge host, or null when the host is not one. Call
     * {@link #available()} first. A block entity is matched directly; on a cable bus the accessor
     * parts are looked for, and {@code side} picks one when there are several.
     */
    static Target find(Object host, Direction side) throws LuaException {
        Api api = Holder.API.orElseThrow();
        if (api.tile().type().isInstance(host) && host instanceof BlockEntity be) {
            return new Target(host, api.tile(), be::setChanged);
        }
        if (!(host instanceof IPartHost partHost)) return null;
        List<IPart> found = new ArrayList<>();
        List<Direction> sides = side != null ? List.of(side) : List.of(Direction.values());
        for (Direction d : sides) {
            IPart p = partHost.getPart(d);
            if (p != null && api.part().type().isInstance(p)) found.add(p);
        }
        if (found.isEmpty()) return null;
        if (found.size() > 1) {
            throw new LuaException("This cable has several Flux Accessors: pass the side of the one you mean");
        }
        return new Target(found.get(0), api.part(), partHost::markForSave);
    }

    /** "input", "output" or "both" (any case) as AppliedFlux's EnergyIO constant. */
    static Object parseMode(String text) throws LuaException {
        String t = text.toLowerCase(Locale.ROOT);
        for (Object mode : Holder.API.orElseThrow().modes()) {
            if (((Enum<?>) mode).name().toLowerCase(Locale.ROOT).equals(t)) return mode;
        }
        throw new LuaException("Unknown flux mode \"" + text + "\": use \"input\", \"output\" or \"both\"");
    }

    /** A side argument: a compass direction, "up"/"top" or "down"/"bottom"; null for no argument. */
    static Direction parseSide(String text) throws LuaException {
        if (text == null) return null;
        String t = text.toLowerCase(Locale.ROOT);
        Direction d = switch (t) {
            case "top" -> Direction.UP;
            case "bottom" -> Direction.DOWN;
            default -> Direction.byName(t);
        };
        if (d == null) throw new LuaException("Unknown side \"" + text + "\": use up, down, north, south, east or west");
        return d;
    }

    static Map<String, Object> describe(Target t) throws LuaException {
        Map<String, Object> m = new HashMap<>();
        m.put("mode", t.mode());
        m.put("fast", t.fast());
        return m;
    }

    private static Object invoke(Method m, Object target, Object... args) throws LuaException {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOG.warn("{} failed on {}", m.getName(), target.getClass().getName(), cause);
            throw new LuaException("The Flux Accessor refused the call: " + cause);
        } catch (IllegalAccessException | RuntimeException e) {
            LOG.warn("Calling {} on {} failed", m.getName(), target.getClass().getName(), e);
            throw new LuaException("Could not call the Flux Accessor: " + e);
        }
    }
}
