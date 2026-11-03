package net.depression.block.entity;

import net.depression.Depression;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.21.1 起旗帜图案是纯数据包注册表（data/depression/banner_pattern/*.json），
 * 启动时 Registry.REGISTRY 里根本没有 minecraft:banner_pattern，
 * 用 DeferredRegister/Registry.register 会直接炸（"Registry ... does not exist!"）。
 * 这里只保留图案 ID 常量，实际注册走 JSON。
 */
public class ModBannerPatterns {

    public static final ResourceLocation RIBBON_ID = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "ribbon");

    public static void register() {
        // no-op：见 data/depression/banner_pattern/ribbon.json
    }

}
