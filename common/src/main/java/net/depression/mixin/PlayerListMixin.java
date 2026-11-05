package net.depression.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.depression.mental.MentalStatus;
import net.depression.mental.PTSDManager;
import net.depression.network.MentalTraitPacket;
import net.depression.rhythmcraft.PlayingChart;
import net.depression.server.Registry;
import net.depression.util.TempValues;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
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
            ci.cancel();
        }
    }

    //B 修复：玩家中途退出（含关服时的 removeAll）时还原玩家数据并回收谱面维度，
    //原来只在客户端发 LOAD_BACK 时清理，退出后谱面会永久留在 playingCharts 里
    @Inject(method = "remove", at = @At("HEAD"))
    private void onRemovePlayingChart(ServerPlayer serverPlayer, CallbackInfo ci) {
        PlayingChart.onPlayerQuit(serverPlayer);
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
