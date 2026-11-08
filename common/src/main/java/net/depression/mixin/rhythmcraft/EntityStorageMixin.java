package net.depression.mixin.rhythmcraft;

import net.depression.rhythmcraft.PlayingChart;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.entity.ChunkEntities;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 普通谱面局（非编辑模式）不把实体写回共享的 {@code rc_charts}（M37）。
 * <p>
 * {@code storeEntities} 是实体区域文件写盘的唯一提交口（chunk 卸载、{@code autoSave} 都走这里），
 * 在入口取消即可；编辑模式与非谱面世界完全原样。{@code close()} 链不动，句柄照常释放。
 */
@Mixin(EntityStorage.class)
public abstract class EntityStorageMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "storeEntities", at = @At("HEAD"), cancellable = true)
    private void depression$onStoreEntities(ChunkEntities<Entity> chunkEntities, CallbackInfo ci) {
        if (this.level instanceof PlayingChart playingChart
                && !playingChart.isEditMode) {
            ci.cancel();
        }
    }
}
