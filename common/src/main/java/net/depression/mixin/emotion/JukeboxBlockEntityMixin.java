package net.depression.mixin.emotion;

import net.depression.mental.MentalStatus;
import net.depression.network.MentalStatusPacket;
import net.depression.server.Registry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JukeboxBlockEntity.class)
public abstract class JukeboxBlockEntityMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private static void tick(Level level, BlockPos blockPos, BlockState blockState, JukeboxBlockEntity jukebox, CallbackInfo ci) {
        if (level.isClientSide())
            return;
        // 1.21.1 tick is a static ticker: no instance state, throttle by game time instead.
        if (level.getGameTime() % 20 != 0)
            return;
        if (!jukebox.getSongPlayer().isPlaying()) {
            return;
        }
        ServerLevel serverLevel = (ServerLevel) level;
        for (ServerPlayer player : serverLevel.players()) {
            if (player.position().distanceTo(jukebox.getBlockPos().getCenter()) <= 65) {
                MentalStatus mentalStatus = MentalStatus.getMentalStatusByServerPlayer(player);
                mentalStatus.mentalHeal(jukebox.getTheItem().getItem().arch$registryName().toString(), 1);
                MentalStatusPacket.sendToPlayer(player, mentalStatus);
            }
        }
    }
}
