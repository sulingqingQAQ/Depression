package net.depression.block.entity;

import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.depression.Depression;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BannerPattern;

public class ModBannerPatterns {

    public static final DeferredRegister<BannerPattern> BANNER_PATTERNS = DeferredRegister.create(Depression.MOD_ID, Registries.BANNER_PATTERN);
    public static final RegistrySupplier<BannerPattern> RIBBON = BANNER_PATTERNS.register("ribbon", () -> new BannerPattern(ResourceLocation.fromNamespaceAndPath("depression", "ribbon"), "block.minecraft.banner.depression.ribbon"));

    public static void register() {
        BANNER_PATTERNS.register();
    }

}
