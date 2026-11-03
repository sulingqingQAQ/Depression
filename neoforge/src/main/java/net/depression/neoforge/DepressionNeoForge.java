package net.depression.neoforge;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import net.depression.Depression;
import net.depression.config.ServerConfig;
import net.depression.neoforge.world.NeoForgeVillageAddition;
import net.neoforged.fml.common.Mod;

@Mod(Depression.MOD_ID)
public final class DepressionNeoForge {
    public DepressionNeoForge() {
        // Architectury 13 无需手动提交 mod event bus（RegistrarManager 直连）。
        // Run our common setup.
        Depression.init();
        NeoForgeVillageAddition.register();
        ServerConfig.load();
        MixinExtrasBootstrap.init();
    }

}
