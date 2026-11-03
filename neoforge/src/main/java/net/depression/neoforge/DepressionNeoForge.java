package net.depression.neoforge;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import dev.architectury.platform.neoforge.EventBuses;
import net.depression.Depression;
import net.depression.config.ServerConfig;
import net.depression.neoforge.world.NeoForgeVillageAddition;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(Depression.MOD_ID)
public final class DepressionNeoForge {
    public DepressionNeoForge() {
        // Submit our event bus to let Architectury API register our content on the right time.
        EventBuses.registerModEventBus(Depression.MOD_ID, FMLJavaModLoadingContext.get().getModEventBus());
        // Run our common setup.
        Depression.init();
        NeoForgeVillageAddition.register();
        ServerConfig.load();
        MixinExtrasBootstrap.init();
    }

}
