package net.depression.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.depression.server.PlayerSaveTracker;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 写盘探针（保存生命周期 S2）：只观测，不改变 {@code PlayerDataStorage.save} 的任何行为。
 * <ul>
 *   <li>三个 {@code @WrapOperation} 都先原样执行被包装的调用（保证与别的模组对同一调用点的包装可以叠加），
 *       异常原样重新抛出（原版 {@code catch (Exception)} 照旧记 warn 并吞掉）。</li>
 *   <li>提交判据不自己重做 {@code safeReplaceOrMoveFile}，而是看事后条件：新文件已改名就位，
 *       即 {@code !exists(tmp) && exists(dat)}（与被原版丢弃的 boolean 等价，极端 race 见 {@link PlayerSaveTracker}）。</li>
 *   <li>本类不持有 NBT 引用（S3 失败快照冻结）。</li>
 * </ul>
 */
@Mixin(PlayerDataStorage.class)
public abstract class PlayerDataStorageMixin {
    @Inject(method = "save", at = @At("HEAD"))
    private void depression$saveBegin(Player player, CallbackInfo ci) {
        PlayerSaveTracker.onSaveBegin(player);
    }

    @WrapOperation(method = "save", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/nbt/NbtIo;writeCompressed(Lnet/minecraft/nbt/CompoundTag;Ljava/nio/file/Path;)V"))
    private void depression$wrapWrite(CompoundTag tag, Path tmp, Operation<Void> original,
                                      @Local(argsOnly = true) Player player) {
        try {
            original.call(tag, tmp);
        }
        catch (Exception e) {
            PlayerSaveTracker.onWriteFailed(player, tmp, e);
            throw e; //原样重新抛出：受检的 IOException 也不变，原版 catch 照旧处理
        }
        PlayerSaveTracker.onWriteDone(player, tmp);
    }

    @WrapOperation(method = "save", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/Util;safeReplaceFile(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/nio/file/Path;)V"))
    private void depression$wrapReplace(Path dat, Path tmp, Path datOld, Operation<Void> original,
                                        @Local(argsOnly = true) Player player) {
        original.call(dat, tmp, datOld);
        boolean committed = !Files.exists(tmp) && Files.exists(dat);
        PlayerSaveTracker.onReplaceDone(player, dat, tmp, committed);
    }

    @Inject(method = "save", at = @At("RETURN"))
    private void depression$saveEnd(Player player, CallbackInfo ci) {
        PlayerSaveTracker.onSaveEnd(player);
    }
}
