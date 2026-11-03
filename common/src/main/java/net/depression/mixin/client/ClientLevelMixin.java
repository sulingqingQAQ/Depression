package net.depression.mixin.client;

import net.depression.client.DepressionClient;
import net.depression.client.rhythmcraft.ClientPlayingChart;
import net.depression.mental.PTSDManager;
import net.depression.network.PlaySoundPacket;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.LevelEntityGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Shadow public abstract ClientChunkCache getChunkSource();

    @Shadow protected abstract LevelEntityGetter<Entity> getEntities();


    @Inject(method = "setDayTime", at = @At("HEAD"), cancellable = true)
    private void onTickTime(CallbackInfo ci) {
        ClientPlayingChart playingChart = DepressionClient.playingChart;
        if (playingChart != null && playingChart.isTimeFreeze) {
            ci.cancel();
        }
    }
    @Inject(method = "entitiesForRendering", at = @At("HEAD"), cancellable = true)
    private void modifyEntitiesForRendering(CallbackInfoReturnable<Iterable<Entity>> cir) {
        ClientPlayingChart playingChart = DepressionClient.playingChart;
        if (playingChart != null) {
            ArrayList<Entity> entities = new ArrayList<>();
            for (Entity entity : this.getEntities().getAll()) {
                entities.add(entity);
            }
            entities.addAll(playingChart.highlightedNotes.values());
            cir.setReturnValue(entities);
        }
    }

    @Inject(method = "onChunkLoaded", at = @At("HEAD"))
    private void onChunkLoad(ChunkPos chunkPos, CallbackInfo ci) {
        if (DepressionClient.playingChart != null) {
            DepressionClient.playingChart.onChunkLoad(chunkPos);
        }
    }

    @Inject(method = "unload", at = @At("HEAD"))
    private void onChunkUnload(LevelChunk levelChunk, CallbackInfo ci) {
        if (DepressionClient.playingChart != null) {
            DepressionClient.playingChart.onChunkUnload(levelChunk.getPos());
        }
    }

    // 1.21.1 的 ClientLevel 有两个 playLocalSound 重载，互不调用；只写方法名时 Mixin 只取类里第一个，所以两个都显式写描述符
    @Inject(method = "playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V", at = @At("HEAD"))
    private void playLocalSound(double d, double e, double f, SoundEvent soundEvent, SoundSource soundSource, float g, float h, boolean bl, CallbackInfo ci) {
        onPlayLocalSound(soundEvent);
    }

    @Inject(method = "playLocalSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V", at = @At("HEAD"))
    private void playLocalSound(Entity entity, SoundEvent soundEvent, SoundSource soundSource, float volume, float pitch, CallbackInfo ci) {
        onPlayLocalSound(soundEvent);
    }

    @Unique
    private static void onPlayLocalSound(SoundEvent soundEvent) {
        String id = soundEvent.getLocation().toString();
        if (PTSDManager.soundEventMap.containsKey(id)) {
            PlaySoundPacket.sendToServer(id);
        }
    }
}
