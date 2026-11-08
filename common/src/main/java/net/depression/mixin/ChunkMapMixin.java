package net.depression.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.depression.Depression;
import net.depression.rhythmcraft.PlayingChart;
import net.depression.server.Registry;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    public void onSave(ChunkAccess chunkAccess, CallbackInfoReturnable<Boolean> cir) {
        if (this.level instanceof PlayingChart playingChart
                && !playingChart.isEditMode) {
            cir.setReturnValue(false);
        }
    }

    /** 普通谱面局（M37）：POI 的周期/空闲写盘只在这条 tick 里提交，跳过即不写回共享谱面目录；编辑模式与非谱面原样。 */
    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager;tick(Ljava/util/function/BooleanSupplier;)V"))
    private void depression$wrapPoiTick(PoiManager poiManager, BooleanSupplier hasTimeLeft, Operation<Void> original) {
        if (this.level instanceof PlayingChart playingChart
                && !playingChart.isEditMode) {
            return;
        }
        original.call(poiManager, hasTimeLeft);
    }

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    public void onMove(ServerPlayer serverPlayer, CallbackInfo ci) {
        if (Registry.isPending(serverPlayer)) {
            Registry.addPendingChunkMap(serverPlayer, (ChunkMap) (Object) this);
            ci.cancel();
        }
    }
}
