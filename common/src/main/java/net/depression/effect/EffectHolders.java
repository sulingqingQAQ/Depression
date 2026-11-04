package net.depression.effect;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffect;

/**
 * Architectury 的 RegistrySupplier 自身不是 Reference，直塞 MobEffectInstance
 * 会在 1.21.1 存档序列化（holderByNameCodec → safeCastToReference）时炸
 * "Unregistered holder"。所有运行时使用效果 holder 的地方（查询/添加/移除）
 * 都必须先过一遍 resolve，拿到注册表真正的 Reference。
 */
public final class EffectHolders {
    private EffectHolders() {
    }

    public static Holder<MobEffect> resolve(Holder<MobEffect> holder) {
        if (holder instanceof Holder.Reference<MobEffect> ref) {
            return ref;
        }
        ResourceKey<MobEffect> key = holder.unwrapKey()
                .orElseThrow(() -> new IllegalStateException("Unregistered effect holder: " + holder));
        return BuiltInRegistries.MOB_EFFECT.getHolderOrThrow(key);
    }
}
