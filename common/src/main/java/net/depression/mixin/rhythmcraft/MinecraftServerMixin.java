package net.depression.mixin.rhythmcraft;

import com.google.common.collect.ImmutableList;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.Lifecycle;
import net.depression.rhythmcraft.PlayingChart;
import net.depression.world.dimension.ModDimensions;
import net.minecraft.client.gui.screens.worldselection.ConfirmExperimentalFeaturesScreen;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.WorldData;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Shadow @Final private Map<ResourceKey<Level>, ServerLevel> levels;

    @Shadow @Final protected WorldData worldData;

    @Shadow @Final private Executor executor;

    @Shadow @Final protected LevelStorageSource.LevelStorageAccess storageSource;

    protected MinecraftServerMixin() {
    }

    @Shadow public abstract RegistryAccess.Frozen registryAccess();

    @Shadow private boolean allowFlight;
    @Shadow public abstract boolean isSingleplayer();

    @Shadow private PlayerList playerList;

    @Shadow public abstract boolean isSingleplayerOwner(GameProfile gameProfile);

    @Shadow @Nullable public abstract GameProfile getSingleplayerProfile();

    @Inject(method = "saveAllChunks", at = @At(value = "RETURN"))
    private void onSaveAllChunks(boolean bl, boolean bl2, boolean bl3, CallbackInfoReturnable<Boolean> cir) {
        //B：遍历副本，清理时不会 ConcurrentModificationException
        for (PlayingChart playingChart : new ArrayList<>(PlayingChart.playingCharts.values())) {
            playingChart.save(null, bl2, playingChart.noSave && !bl3);
        }
    }
    @ModifyArg(method = "saveAllChunks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;saveDataTag(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/storage/WorldData;Lnet/minecraft/nbt/CompoundTag;)V", ordinal = 0)
            , index = 2)
    private CompoundTag onSaveSinglePlayerData(CompoundTag compoundTag) {
        GameProfile gameProfile = this.getSingleplayerProfile();
        if (gameProfile == null) {
            return compoundTag;
        }
        if (PlayingChart.playerData.containsKey(gameProfile.getId())) {
            return PlayingChart.playerData.get(gameProfile.getId());
        }
        return compoundTag;
    }
    @Inject(method = "createLevels", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/border/WorldBorder;applySettings(Lnet/minecraft/world/level/border/WorldBorder$Settings;)V"))
    private void getArguments(ChunkProgressListener chunkProgressListener, CallbackInfo ci) {
        Registry<LevelStem> registry = this.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        LevelStem levelStem = registry.get(ModDimensions.CHART_STEM);
        RandomSequences randomSequences = this.levels.get(Level.OVERWORLD).getRandomSequences();
        PlayingChart.server = (MinecraftServer) (Object) this;
        PlayingChart.executor = this.executor;
        PlayingChart.worldData = this.worldData;
        PlayingChart.chartStem = levelStem;
        PlayingChart.chunkProgressListener = chunkProgressListener;
        PlayingChart.randomSequences = randomSequences;
    }

    @Inject(method = "tickChildren", at = @At(value = "HEAD"))
    private void tickPlayingCharts(BooleanSupplier booleanSupplier, CallbackInfo ci) {
        if (PlayingChart.playingCharts.isEmpty()) { //没有谱面时不复制、不巡检，避免每 tick 分配
            return;
        }
        PlayingChart.cleanupStaleCharts(); //B：先回收结束/断连的谱面，避免继续 tick 已关闭的维度
        for (PlayingChart playingChart : new ArrayList<>(PlayingChart.playingCharts.values())) {
            //用户已定（决策二）：单局 tick 抛异常只终止这一局并恢复玩家，不让一首歌的 bug 打崩整台服务器的 tick。
            //不捕获 Error（OOM 等照常抛）；恢复本身再失败时按 RESTORE_FAILED 隔离，不重复抛。
            try {
                playingChart.tick(booleanSupplier);
            } catch (Exception e) {
                PlayingChart.handleTickFailure(playingChart, e);
            }
        }
    }

    @Inject(method = "pollTaskInternal", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;getAllLevels()Ljava/lang/Iterable;"), cancellable = true)
    private void pollTaskPlayingCharts(CallbackInfoReturnable<Boolean> cir) {
        if (PlayingChart.playingCharts.isEmpty()) {
            return;
        }
        for (PlayingChart playingChart : new ArrayList<>(PlayingChart.playingCharts.values())) {
            if (playingChart.getChunkSource().pollTask()) {
                cir.setReturnValue(true);
            }
        }
    }

    /**
     * I8（用户已定）：{@code stopServer} 里 {@code PlayerList.saveAll} <b>之前</b>把已结算局结算掉
     * （恢复玩家 + 补成绩 / 治疗 + 关局 + 移出 playingCharts），随后原版 saveAll 按正常路径写这名玩家。
     * 未结算局仍留在表里、保存照旧被拦截（磁盘为准）。
     * <p>
     * 用符号锚点（INVOKE target = PlayerList;saveAll()V），与 21.1.219 / 21.1.230 的字节码偏移差无关；
     * 本方法的记录跟着 offset 224 的 saveAllChunks 落盘。
     */
    @Inject(method = "stopServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;saveAll()V"))
    private void settleRhythmBeforeSaveAll(CallbackInfo ci) {
        PlayingChart.settleBeforeStopSave();
    }

    @Inject(method = "stopServer", at = @At(value = "RETURN"))
    private void disposePlayingCharts(CallbackInfo ci) {
        PlayingChart.disposeAll(); //B：关服兜底，回收所有残留谱面维度并清空静态字段（C11 / C09）
    }
}
