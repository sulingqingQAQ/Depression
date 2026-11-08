package net.depression.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.depression.Depression;
import net.depression.mental.MentalStatus;
import net.depression.mental.PTSDManager;
import net.depression.network.MentalTraitPacket;
import net.depression.rhythmcraft.PlayingChart;
import net.depression.server.PlayerSaveLifecycle;
import net.depression.server.PlayerSaveTracker;
import net.depression.server.Registry;
import net.depression.util.TempValues;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void onSave(ServerPlayer serverPlayer, CallbackInfo ci) {
        if (PlayingChart.playingCharts.containsKey(serverPlayer.getUUID())) {
            //音游拦截：故意不写盘（磁盘为准），不是写盘失败。契约不变，只额外告诉写盘探针这是“被拦截”
            PlayerSaveTracker.noteSuppressed(serverPlayer.getUUID());
            ci.cancel();
            return;
        }
        if (PlayerSaveLifecycle.isEvicted(serverPlayer)) {
            //这个实体的退出清理已经做完：此时再存会因状态已清而写出缺 mod 键的 NBT 覆盖 .dat（二次 remove / 第三方误调）
            ci.cancel();
        }
    }

    //B 修复：玩家中途退出（含关服时的 removeAll）时还原玩家数据并回收谱面维度，
    //原来只在客户端发 LOAD_BACK 时清理，退出后谱面会永久留在 playingCharts 里
    @Inject(method = "remove", at = @At("HEAD"))
    private void onRemovePlayingChart(ServerPlayer serverPlayer, CallbackInfo ci) {
        PlayerSaveTracker.beginLogout(serverPlayer.getUUID()); //先开登出窗口，再让音游收尾
        try {
            PlayingChart.onPlayerQuit(serverPlayer);
        }
        catch (RuntimeException e) {
            //音游收尾失败不能让 remove 中断：否则玩家既没保存也没移除。保存仍会照常执行（若音游已进隔离态则被拦截，磁盘为准）
            Depression.LOGGER.error("[Depression/Save] rhythm quit hook failed uuid={}, continue logout", serverPlayer.getUUID(), e);
        }
    }

    //保存生命周期：清理放在该次 remove 自身的保存之后（RETURN），不再放在 NBT 序列化回调里
    @Inject(method = "remove", at = @At("RETURN"))
    private void onRemoveReturn(ServerPlayer serverPlayer, CallbackInfo ci) {
        PlayerSaveLifecycle.finishLogout((PlayerList) (Object) this, serverPlayer);
    }

    //登录 = 干净起点：早于 PlayerList.load，丢弃同 UUID 没有在线旧会话时的残留
    @Inject(method = "placeNewPlayer", at = @At("HEAD"))
    private void onLoginBegin(Connection connection, ServerPlayer serverPlayer, CommonListenerCookie cookie, CallbackInfo ci) {
        PlayerSaveLifecycle.onLoginBegin((PlayerList) (Object) this, serverPlayer);
    }
    @Inject(method = "broadcast", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void onPlaySound(CallbackInfo ci, @Local(ordinal = 0) ServerPlayer player) {
        PTSDManager.onPlaySound(player);
    }

    @Inject(method = "broadcast", at = @At(value = "RETURN"))
    private void onBroadcastOver(Player player, double d, double e, double f, double g, ResourceKey<Level> resourceKey, Packet<?> packet, CallbackInfo ci) {
        if (TempValues.broadcastEntity != null) {
            TempValues.broadcastEntity = null;
        }
        if (TempValues.broadcastDamageSource != null) {
            TempValues.broadcastDamageSource = null;
        }
    }
}
