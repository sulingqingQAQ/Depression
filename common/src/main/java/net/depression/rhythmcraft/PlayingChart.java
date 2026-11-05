package net.depression.rhythmcraft;

import com.google.common.collect.ImmutableList;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.Lifecycle;
import dev.architectury.event.EventResult;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import net.depression.Depression;
import net.depression.listener.TickEventListener;
import net.depression.mental.MentalStatus;
import net.depression.mixin.rhythmcraft.PlayerListAccessor;
import net.depression.mixin.rhythmcraft.PrimaryLevelDataAccessor;
import net.depression.network.RhythmCraftPacket;
import net.depression.server.Registry;
import net.depression.world.dimension.ModDimensions;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.TimeCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.util.ProgressListener;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CommandBlock;
import net.minecraft.world.level.block.DetectorRailBlock;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.WorldData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

public class PlayingChart extends ServerLevel {
    /** 谱面结束后，客户端迟迟不发 LOAD_BACK 时的兜底回收超时（5 分钟） */
    private static final long END_IDLE_TIMEOUT_TICKS = 20L * 60L * 5L;
    public static MinecraftServer server;
    public static Executor executor;
    public static LevelStorageSource levelStorageSource = new LevelStorageSource(Platform.getGameFolder().resolve("rc_charts"),
            Platform.getGameFolder().resolve("rc_charts"), null, null);
    public static WorldData worldData;
    public static LevelStem chartStem;
    public static ChunkProgressListener chunkProgressListener;
    public static RandomSequences randomSequences;
    public static LinkedHashMap<UUID, PlayingChart> playingCharts = new LinkedHashMap<>();
    public static HashMap<UUID, CompoundTag> playerData = new HashMap<>();
    public boolean isEditMode;
    public boolean isPaused;
    public Boolean isJustStarted;
    public ServerPlayer player;
    public Chart chart;
    public RCMinecart minecart;
    public Vec3 speed = Vec3.ZERO;
    public ConcurrentSkipListSet<BlockPos> remainingNotes;
    public double score = 0;
    public int scoreInt = 0;
    public int combo = 0;
    //public int highestCombo = 0;
    public long tickCount = 0;
    public int prevTick = 0;
    public boolean isEnded;
    /** 幂等清理标记：复用同一个谱面对象重复调用 dispose() 时只清理一次 */
    public boolean isDisposed;
    /** 谱面结束时刻（overworld gameTime），-1 表示还没结束；用于客户端不发 LOAD_BACK 时的兜底回收 */
    public long endedGameTime = -1L;
    public File levelFile;
    /** 谱面目录的存档访问句柄（ServerLevel 不保存它），dispose() 时关闭以释放 session 锁 */
    private LevelStorageSource.LevelStorageAccess chartStorage;
    public TreeMap<Long, Double> speedIntegration = new TreeMap<>();
    public PlayingChart(ServerPlayer player, Chart chart, boolean isEditMode) throws IOException {
        this(player, chart, isEditMode, levelStorageSource.createAccess(chart.song.id + "/" + chart.levelPath));
    }

    private PlayingChart(ServerPlayer player, Chart chart, boolean isEditMode, LevelStorageSource.LevelStorageAccess chartStorage) throws IOException {
        super(player.server, executor, chartStorage,
                new PrimaryLevelData(worldData.getLevelSettings(), worldData.worldGenOptions(), PrimaryLevelData.SpecialWorldProperty.NONE, Lifecycle.stable()),
                ModDimensions.CHART, chartStem, chunkProgressListener, false, 0, ImmutableList.of(), false, randomSequences);
        this.chartStorage = chartStorage;
        this.player = player;
        this.chart = chart;
        this.isEditMode = isEditMode;
        remainingNotes = new ConcurrentSkipListSet<>(chart.notes.comparator());
        if (!isEditMode) {
            remainingNotes.addAll(chart.notes);
        }

        if (isEditMode) {
            player.setGameMode(GameType.CREATIVE);
        }
        else {
            player.setGameMode(GameType.SURVIVAL);
        }

        BlockPos pos = chart.startingPos;
        player.teleportTo(this, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0f, 0f); //gh这俩参数一直在被偷偷篡改，每次测试都不一样，所以只能用lookAt了
        if (chart.isNeedMinecart) {
            this.minecart = new RCMinecart(this, pos.getX() + 0.5, pos.getY() + 0.0625, pos.getZ() + 0.5);
            addFreshEntity(minecart);
            player.startRiding(minecart, true);
        }
        Vec3 eyePos = player.getEyePosition();
        Vec3i direction = chart.direction.getNormal();
        player.lookAt(EntityAnchorArgument.Anchor.EYES, eyePos.add(direction.getX(), direction.getY(), direction.getZ()));

        levelFile = Platform.getGameFolder().resolve("rc_charts/" + chart.song.id + "/" + chart.levelPath + "/level.dat").toFile();
        CompoundTag playerTag = NbtIo.readCompressed(levelFile.toPath(), NbtAccounter.unlimitedHeap()).getCompound("Data").getCompound("Player"); // 1.21.1: NbtIo 改用 Path + NbtAccounter
        ListTag inventoryTag = playerTag.getList("Inventory", 10);
        player.getInventory().load(inventoryTag);

        integrate(0);
    }
    public void start() {
        isJustStarted = true;
    }
    public EventResult onBlockBreak(BlockPos pos) {
        if (chart.notes.contains(pos)) {
            ++scoreInt;
            ++combo;
            score = (double) scoreInt / chart.notes.size() * 1e6;
            remainingNotes.remove(pos);
            RhythmCraftPacket.sendNoteChange(player, pos, false);
            RhythmCraftPacket.sendGameplayChange(player, (int) Math.round(score), combo);
            if (isEditMode) {
                chart.notes.remove(pos);
                chart.isEdited = true;
                return EventResult.interruptFalse();
            }
            return EventResult.pass();
        }
        if (isEditMode) {
            return EventResult.pass();
        }
        else {
            return EventResult.interruptFalse();
        }
    }
    public void integrate(long startingTick) {
        Map.Entry<Long, Double> curEntry = chart.speedMap.ceilingEntry(startingTick);
        if (curEntry == null) {
            return;
        }
        Map.Entry<Long, Double> prevEntry;
        do {
            prevEntry = chart.speedMap.lowerEntry(curEntry.getKey());
            if (prevEntry == null) {
                speedIntegration.put(curEntry.getKey(), 0D);
            }
            else {
                speedIntegration.put(curEntry.getKey(), speedIntegration.get(prevEntry.getKey()) + prevEntry.getValue() / 1200d * (curEntry.getKey() - prevEntry.getKey()));
            }
            curEntry = chart.speedMap.higherEntry(curEntry.getKey());
        }
        while (curEntry != null);
    }
    public void seek(long tick) {
        BlockPos pos = chart.startingPos;
        Vec3 start = new Vec3(pos.getX() + 0.5, pos.getY() + 0.0625, pos.getZ() + 0.5);
        Vec3i normalInt = chart.direction.getNormal();
        Vec3 normal = new Vec3(normalInt.getX(), normalInt.getY(), normalInt.getZ());
        Long lastKey = chart.speedMap.floorKey(tick);
        if (lastKey == null) {
            return;
        }
        Vec3 dest = start.add(normal.scale(
                speedIntegration.get(lastKey) + chart.speedMap.get(lastKey) / 1200d * (tick - lastKey)
        ));
        player.server.execute(() -> {
            //B 修复：isNeedMinecart=false 的谱面没有矿车，原实现直接解引用会 NPE
            if (minecart != null) {
                minecart.teleportTo(dest.x, dest.y, dest.z);
            }
        });
        speed = new Vec3(normalInt.getX(), normalInt.getY(), normalInt.getZ()).scale(chart.speedMap.get(lastKey) / 1200d);
        if (chart.isNeedMinecart && !isPaused) {
            minecart.setLockedDeltaMovement(speed);
        }
        tickCount = tick;
    }
    @Override
    public void tick(BooleanSupplier booleanSupplier) {
        ((PrimaryLevelDataAccessor) this.getLevelData()).invokeSetGameTime(server.overworld().getGameTime());
        if (isEnded) {
            return;
        }
        if (!isPaused) {
            if (isJustStarted == null) {
                //B 修复：纯步行谱面 minecart 为 null（原来这里每 tick 都会 NPE）
                if (minecart != null) {
                    minecart.setLockedDeltaMovement(Vec3.ZERO);
                }
                super.tick(booleanSupplier);
                return;
            }
            else {
                if (isJustStarted) {
                    RhythmCraftPacket.sendPlaySong(player, chart.song.id);
                    isJustStarted = false;
                }
            }
            BlockPos playerPos = player.blockPosition();
            if (!isEditMode) { //是否断连
                for (BlockPos pos : remainingNotes) {
                    if (chart.notes.comparator().compare(pos, playerPos) < 0 && player.getEyePosition().distanceTo(pos.getCenter()) >= 5) {
                        //highestCombo = Math.max(highestCombo, combo);
                        combo = 0;
                        remainingNotes.remove(pos);
                        RhythmCraftPacket.sendGameplayChange(player, (int) Math.round(score), combo);
                    } else {
                        break;
                    }
                }
            }
            if (chart.speedMap.containsKey(tickCount)) {
                //速度变化
                Vec3i direction = chart.direction.getNormal();
                speed = new Vec3(direction.getX(), direction.getY(), direction.getZ()).scale(chart.speedMap.get(tickCount) / 1200d);
                if (chart.isNeedMinecart) {
                    minecart.setLockedDeltaMovement(speed);
                }
            }
            else {
                player.setDeltaMovement(speed);
            }
            if (chart.timeMap.containsKey(tickCount)) {
                long time = chart.timeMap.get(tickCount);
                setDayTime(time);
                RhythmCraftPacket.sendTimeChange(player, time);
            }
            ++tickCount;
            if (tickCount > chart.song.durationInTicks) { //游戏结束
                //highestCombo = Math.max(highestCombo, combo);
                //B 修复：纯步行谱面 minecart 为 null（原来这里必然 NPE，谱面永远结束不了）
                if (minecart != null) {
                    minecart.setLockedDeltaMovement(Vec3.ZERO);
                }
                RhythmCraftProfile profile = RhythmCraftProfile.getProfileByServerPlayer(player);
                int prevScore = 0;
                ArrayList<Integer> scores = profile.chartScores.get(chart.song.id);
                if (scores != null && scores.size() > chart.difficulty) {
                    prevScore = scores.get(chart.difficulty);
                }
                double mentalHealValue = 0;
                if (score == 1_000_000) {
                    mentalHealValue = Math.max(mentalHealValue, chart.level / 16d);
                }
                if (score > prevScore) {
                    mentalHealValue = Math.max(mentalHealValue, (score - prevScore) * chart.level / 6_400_000d);
                }
                if (mentalHealValue > 0) {
                    MentalStatus mentalStatus = MentalStatus.getMentalStatusByServerPlayer(player);
                    mentalStatus.mentalHeal("rhythmcraft", mentalHealValue);
                }
                RhythmCraftPacket.sendGameEnd(player, (int) Math.round(score), scoreInt, prevScore);
                profile.newScore(chart.song.id, chart.difficulty, (int) Math.round(score));
                isEnded = true;
                endedGameTime = server.overworld().getGameTime(); //记录结束时刻，供 checkStale() 超时兜底
            }
        }
        super.tick(booleanSupplier);
    }
    @Override
    public void save(@Nullable ProgressListener progressListener, boolean bl, boolean bl2) {
        if (isEditMode) {
            super.save(progressListener, bl, bl2);
            chart.save();
            try {
                CompoundTag levelTag = NbtIo.readCompressed(levelFile.toPath(), NbtAccounter.unlimitedHeap());
                levelTag.getCompound("Data").put("Player", player.saveWithoutId(new CompoundTag()));
                NbtIo.writeCompressed(levelTag, levelFile.toPath());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static void onReceiveReadChart(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext packetContext) {
        String songId = buf.readCharSequence(buf.readInt(), StandardCharsets.UTF_8).toString();
        int difficulty = buf.readInt();
        boolean isEditMode = buf.readBoolean();
        ServerPlayer player = (ServerPlayer) packetContext.getPlayer();
        if (isEditMode) {
            if (player.hasPermissions(2)) {
                RhythmCraftPacket.sendAcceptEdit(player);
            }
            else {
                return;
            }
        }
        player.server.execute(
                () -> {
                    Song song = Song.idMap.get(songId);
                    if (song == null) {
                        return;
                    }
                    Chart chart = song.charts.get(difficulty);
                    if (chart == null) {
                        return;
                    }
                    try {
                        //存储PlayerList的玩家数据
                        PlayerListAccessor accessor = (PlayerListAccessor) player.server.getPlayerList();
                        accessor.invokeSave(player);
                        //记录玩家数据用于对单人玩家数据的偷梁换柱（X
                        playerData.put(player.getUUID(), player.saveWithoutId(new CompoundTag()));
                        //开始游戏
                        PlayingChart playingChart = new PlayingChart(player, chart, isEditMode);
                        playingCharts.put(player.getUUID(), playingChart);
                    } catch (IOException e) {
                        playerData.remove(player.getUUID());
                        playingCharts.remove(player.getUUID());
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }
                }
        );
    }
    public static void onReceiveReady(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext packetContext) {
        ServerPlayer player = (ServerPlayer) packetContext.getPlayer();
        player.server.execute(
                () -> {
                    PlayingChart playingChart = playingCharts.get(player.getUUID());
                    if (playingChart == null) {
                        return;
                    }
                    playingChart.start();
                }
        );
    }
    /** 还原玩家进入谱面前的存档（位置 / 物品 / 维度），LOAD_BACK 与"玩家中途退出"的兜底清理共用。 */
    public static boolean restorePlayerData(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        UUID uuid = player.getUUID();
        CompoundTag compoundTag = playerData.get(uuid);
        if (compoundTag == null) {
            return false;
        }
        player.stopRiding(); //先下车再整体还原存档，避免骑着矿车被传送
        player.load(compoundTag);
        DataResult dataResult = DimensionType.parseLegacy(new Dynamic<>(NbtOps.INSTANCE, compoundTag.get("Dimension")));
        ResourceKey<Level> resourceKey = (ResourceKey<Level>) dataResult.result().orElse(Level.OVERWORLD);
        ServerLevel serverLevel = server == null ? null : server.getLevel(resourceKey);
        if (serverLevel != null) {
            player.teleportTo(serverLevel, player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot());
        }
        Registry.particles.remove(uuid.toString());
        return true;
    }

    /**
     * B 修复：幂等回收谱面。
     * 原实现只在 LOAD_BACK 时把谱面从 playingCharts 里删掉，中途退出 / 崩溃 / 客户端不发 LOAD_BACK 时，
     * 这个 ServerLevel 会永久留在表里被 tickChildren / pollTaskInternal / saveAllChunks 反复处理，
     * 维度、chunkSource、实体管理器和存档句柄全部泄漏。
     */
    public void dispose() {
        if (isDisposed) {
            return;
        }
        isDisposed = true;
        UUID uuid = player == null ? null : player.getUUID();
        if (player != null && minecart != null && player.getVehicle() == minecart) {
            player.stopRiding();
        }
        if (minecart != null) {
            minecart.discard(); //B：矿车原来 addFreshEntity 之后从不移除，退出后会留在谱面维度里
            minecart = null;
        }
        if (remainingNotes != null) {
            remainingNotes.clear();
        }
        if (uuid != null) {
            playingCharts.remove(uuid, this); //只移除自己，避免旧实例误删同 UUID 的新谱面
            playerData.remove(uuid);
        }
        try {
            close(); //关闭 chunkSource / entityManager
        } catch (Exception e) {
            Depression.LOGGER.warn("Failed to close playing chart level {}", levelFile, e);
        }
        if (chartStorage != null) {
            try {
                chartStorage.close(); //ServerLevel 不持有 LevelStorageAccess，这里释放谱面目录的 session 锁
            } catch (Exception e) {
                Depression.LOGGER.warn("Failed to close playing chart storage {}", levelFile, e);
            }
            chartStorage = null;
        }
    }

    /** 玩家退出（含关服时的 PlayerList.removeAll）兜底：先还原玩家数据，再回收谱面维度。 */
    public static void onPlayerQuit(ServerPlayer player) {
        if (player == null) {
            return;
        }
        PlayingChart playingChart = playingCharts.get(player.getUUID());
        if (playingChart == null || playingChart.isDisposed) {
            return;
        }
        restorePlayerData(player); //不还原的话玩家会被留在谱面维度里，物品也停留在谱面存档
        playingChart.dispose();
    }

    /** 关服兜底：把残留的谱面维度全部回收。 */
    public static void disposeAll() {
        for (PlayingChart playingChart : new ArrayList<>(playingCharts.values())) {
            playingChart.dispose();
        }
    }

    /** 每 tick 巡检：谱面结束但客户端一直不发 LOAD_BACK 时，超时回收。 */
    public static void cleanupStaleCharts() {
        for (PlayingChart playingChart : new ArrayList<>(playingCharts.values())) {
            playingChart.checkStale();
        }
    }

    private void checkStale() {
        if (isDisposed) {
            return;
        }
        if (player == null) {
            dispose();
            return;
        }
        if (isEnded && endedGameTime >= 0 && server != null) {
            if (server.overworld().getGameTime() - endedGameTime > END_IDLE_TIMEOUT_TICKS) {
                restorePlayerData(player);
                dispose();
            }
        }
    }

    public static void onReceiveLoadBack(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext packetContext) {
        ServerPlayer player = (ServerPlayer) packetContext.getPlayer();
        player.server.execute(
                () -> {
                    UUID uuid = player.getUUID();
                    if (!playerData.containsKey(uuid)) {
                        return;
                    }
                    PlayingChart playingChart = playingCharts.get(uuid);
                    restorePlayerData(player);
                    if (playingChart != null) {
                        playingChart.dispose(); //B：连谱面维度一起回收，不再只从表里删掉
                    }
                    else {
                        playerData.remove(uuid);
                    }
                }
        );
    }

    public static void onReceivePauseChange(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext packetContext) {
        PlayingChart playingChart = PlayingChart.playingCharts.get(packetContext.getPlayer().getUUID());
        long tick = buf.readLong();
        //B 修复：谱面可能已被兜底回收，迟到的暂停包直接丢弃
        if (playingChart == null || playingChart.isDisposed) {
            return;
        }
        playingChart.isPaused = !playingChart.isPaused;
        playingChart.player.server.execute(() -> {
            if (playingChart.isDisposed) {
                return;
            }
            if (playingChart.isPaused) {
                playingChart.seek(tick);
                //B 修复：isNeedMinecart=false 时 minecart 为 null
                if (playingChart.minecart != null) {
                    playingChart.minecart.setLockedDeltaMovement(Vec3.ZERO);
                }
            }
            else if (playingChart.minecart != null) {
                playingChart.minecart.setLockedDeltaMovement(playingChart.speed);
            }
        });
    }
    public static void onReceiveProgressChange(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext packetContext) {
        PlayingChart playingChart = PlayingChart.playingCharts.get(packetContext.getPlayer().getUUID());
        long tick = buf.readLong();
        if (playingChart == null || playingChart.isDisposed) { //B 修复：谱面已回收时丢弃迟到的进度包
            return;
        }
        playingChart.tickCount = tick;
        playingChart.seek(tick);
    }

}
