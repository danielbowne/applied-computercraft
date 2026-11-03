package io.github.danielbowne.appliedcc;

import dan200.computercraft.api.ComputerCraftAPI;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(AppliedCC.MOD_ID)
public final class AppliedCC {
    public static final String MOD_ID = "appliedcc";

    public AppliedCC(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        modBus.addListener(FMLCommonSetupEvent.class,
                e -> ComputerCraftAPI.registerGenericSource(new MeBridgeMethods()));
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> CraftJobs.tick());
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, e -> CraftJobs.clear());
    }
}
