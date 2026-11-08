package net.depression.mixin.rhythmcraft;

import net.depression.rhythmcraft.PlayingChart;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 重生挂点（A08，设计稿 §4.5）。
 * <p>
 * 选 {@code respawn} 的 RETURN：此时旧玩家已从谱面世界移除、新玩家已在重生世界登记，关谱面不会碰到"世界里还有玩家"，
 * 恢复对象是<b>新</b>玩家。HEAD 不行——旧玩家还在谱面世界里，先关世界再让原版 {@code removePlayerImmediately} 会出事。
 * <p>
 * {@code respawn} 只有一个 {@code areturn}，RETURN 注入点唯一；{@code depression.mixins.json} 的
 * {@code defaultRequire: 1} 会让 Fabric 上方法名不匹配直接启动报错（fail-fast，M36）。
 */
@Mixin(PlayerList.class)
public abstract class RhythmPlayerListRespawnMixin {
    @Inject(method = "respawn", at = @At("RETURN"))
    private void depression$endRhythmSessionOnRespawn(ServerPlayer oldPlayer, boolean keepAll, Entity.RemovalReason reason,
                                                      CallbackInfoReturnable<ServerPlayer> cir) {
        PlayingChart.onPlayerRespawn(oldPlayer, cir.getReturnValue());
    }
}
