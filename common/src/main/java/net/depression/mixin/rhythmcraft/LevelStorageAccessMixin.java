package net.depression.mixin.rhythmcraft;

import net.depression.rhythmcraft.PlayingChart;
import net.minecraft.util.DirectoryLock;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 锁作用域（A04，设计稿 §4.7 / INV6）：只给 {@link PlayingChart#levelStorageSource} 创建的谱面存取关锁，
 * 主世界与其它存档恢复原版 {@code session.lock} 互斥（M29）。
 * <p>
 * 判据用 {@code LevelStorageSource} <b>实例身份</b>：{@code PlayingChart} 的静态初始化只 {@code new} 一个
 * LevelStorageSource（不建 Access），所以第一次构造任何 LevelStorageAccess 时触发它的类加载不会递归。
 */
@Mixin(LevelStorageSource.LevelStorageAccess.class)
public abstract class LevelStorageAccessMixin {
    @Shadow @Final private DirectoryLock lock;

    /** 只有谱面目录的存取句柄才置真（{@code <init>} 里判来源实例） */
    @Unique private boolean depression$chartScoped;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void onInit(LevelStorageSource levelStorageSource, String string, Path path, CallbackInfo ci) throws IOException {
        if (levelStorageSource != PlayingChart.levelStorageSource) {
            return; //主世界 / 其它存档：保持原版 session.lock 互斥
        }
        depression$chartScoped = true;
        this.lock.close(); //谱面目录允许多人同开（多人同谱面是现有玩法）
    }

    @Inject(method = "checkLock", at = @At("HEAD"), cancellable = true)
    public void onCheckLock(CallbackInfo ci) {
        if (depression$chartScoped) {
            ci.cancel();
        }
    }
}
