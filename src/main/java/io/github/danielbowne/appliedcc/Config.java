package io.github.danielbowne.appliedcc;

import dan200.computercraft.api.lua.LuaException;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Server config: config/appliedcc-server.toml in the server (or singleplayer instance) folder. */
final class Config {
    static final ModConfigSpec SPEC;
    static final ModConfigSpec.BooleanValue ENABLED;
    static final ModConfigSpec.BooleanValue ALLOW_EXPORT;
    static final ModConfigSpec.BooleanValue ALLOW_IMPORT;
    static final ModConfigSpec.BooleanValue ALLOW_CRAFTING;
    static final ModConfigSpec.BooleanValue ALLOW_CANCEL;
    static final ModConfigSpec.BooleanValue ALLOW_ON_CABLES;
    static final ModConfigSpec.BooleanValue ALLOW_NETWORK_INFO;
    static final ModConfigSpec.BooleanValue EXPOSE_DRIVE_POSITIONS;
    static final ModConfigSpec.IntValue MAX_PENDING_CRAFTS;

    static {
        var b = new ModConfigSpec.Builder();
        ENABLED = b.comment("Master switch. When false, every me_bridge method returns an error.")
                .define("enabled", true);
        ALLOW_EXPORT = b.comment("Allow exportItem/exportFluid (moving things out of the ME network).")
                .define("allowExport", true);
        ALLOW_IMPORT = b.comment("Allow importItem/importFluid (moving things into the ME network).")
                .define("allowImport", true);
        ALLOW_CRAFTING = b.comment("Allow craftItem/craftFluid.")
                .define("allowCrafting", true);
        ALLOW_CANCEL = b.comment("Allow cancelCraftingTasks.")
                .define("allowCancel", true);
        ALLOW_ON_CABLES = b.comment("Allow the bridge on cables and part hosts (cable bus).",
                        "Set false on public servers so only full AE2 blocks (controller, interface, drive...) work,",
                        "which makes it harder to tap someone else's network from a cable.")
                .define("allowOnCables", true);
        ALLOW_NETWORK_INFO = b.comment("Allow the read-only network inspection methods: getDrives, getCells, the storage capacity",
                        "methods, getChannelInfo, getDevices, getPatterns and getPendingRequests.")
                .define("allowNetworkInfo", true);
        EXPOSE_DRIVE_POSITIONS = b.comment("Include the block position of each drive and ME chest in getDrives and getCells.",
                        "Set false to hide where the storage of a network is.")
                .define("exposeDrivePositions", true);
        MAX_PENDING_CRAFTS = b.comment("Crafting requests a single computer may have calculating at once.")
                .defineInRange("maxPendingCraftsPerComputer", 8, 1, 256);
        SPEC = b.build();
    }

    private Config() {}

    static void require(ModConfigSpec.BooleanValue flag, String what) throws LuaException {
        if (!ENABLED.get()) throw new LuaException("Applied ComputerCraft is disabled on this server");
        if (!flag.get()) throw new LuaException(what + " is disabled on this server");
    }
}
