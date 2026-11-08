package net.depression.rhythmcraft;

import com.google.common.collect.ImmutableList;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.Lifecycle;
import dev.architectury.event.EventResult;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import net.depression.Depression;
import net.depression.mental.MentalStatus;
import net.depression.mixin.rhythmcraft.PlayerListAccessor;
import net.depression.mixin.rhythmcraft.PrimaryLevelDataAccessor;
import net.depression.network.RhythmCraftPacket;
import net.depression.server.PendingGrants;
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
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.util.ProgressListener;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.WorldData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

/**
 * 音游一局（Attempt）＝一个 {@code PlayingChart}（一个谱面 {@link ServerLevel}）。
 * <p>
 * 一次<b>会话</b>（Session）＝某玩家从第一次拿到恢复点（{@link #playerData}）到恢复点被消费或作废；
 * 重试链上的多局共用同一份恢复点与同一份 {@link #sessionResults}。会话的两张表只能经
 * {@link #dropSession(UUID)} 一起删除，唯一收尾入口是 {@link #endSession}（INV1～INV9，见设计稿 §3/§4.3）。
 */
public class PlayingChart extends ServerLevel {
    private static final Logger LOGGER = Depression.LOGGER;
    /** 谱面结束后，客户端迟迟不发 LOAD_BACK 时的兜底回收超时（5 分钟） */
    private static final long END_IDLE_TIMEOUT_TICKS = 20L * 60L * 5L;
    /** 加载超时：客户端正常 3 秒发 READY（RCLoadingScreen），60 秒留足卡顿余量（rt61-05） */
    private static final long LOADING_TIMEOUT_TICKS = 20L * 60L;
    /** 隔离态（RESTORE_FAILED）的重试间隔（rt69-04：每 100 tick 再试一次） */
    private static final long RESTORE_RETRY_INTERVAL_TICKS = 100L;

    public static MinecraftServer server;
    public static Executor executor;
    public static LevelStorageSource levelStorageSource = new LevelStorageSource(Platform.getGameFolder().resolve("rc_charts"),
            Platform.getGameFolder().resolve("rc_charts"), null, null);
    public static WorldData worldData;
    public static LevelStem chartStem;
    public static ChunkProgressListener chunkProgressListener;
    public static RandomSequences randomSequences;
    /** 局表：{@code playingCharts[uuid] 存在 ⇒ playerData[uuid] 存在}（INV1）；存档拦截的判据就是这张表（INV9） */
    public static LinkedHashMap<UUID, PlayingChart> playingCharts = new LinkedHashMap<>();
    /** 恢复点：进谱面前 {@code player.saveWithoutId(...)} 的快照（INV3：只 putIfAbsent，存在期间绝不覆盖） */
    public static HashMap<UUID, CompoundTag> playerData = new HashMap<>();
    /** 本会话已结算局的结果（V04：成绩 + 治疗），与 {@link #playerData} 同生同灭，只经 {@link #dropSession(UUID)} 删除 */
    public static HashMap<UUID, List<Result>> sessionResults = new HashMap<>();

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
    /** 局创建时刻（overworld gameTime），用于 LOADING 60 秒超时（rt61-05） */
    public long createdGameTime = -1L;
    /** {@link #endSession} 执行中：重入直接返回（INV8）；恢复传送会触发换维度事件等回调，靠它挡住二次收尾 */
    public boolean closing;
    /** 恢复失败的隔离态：局留表（继续拦截存档）、不再推进、定期重试；不能用 isEnded 代替 */
    public boolean restoreFailed;
    /** 本局是 tick 自然跑到时长并生成了 {@link Result}（区别于隔离态强行置的 isEnded） */
    public boolean settled;
    /** 上一次隔离重试的 overworld gameTime（挂在局对象上，不额外建表） */
    public long lastRestoreRetryTick = 0L;
    public File levelFile;
    /** 谱面目录的存档访问句柄（ServerLevel 不保存它），dispose() 时关闭以释放 session 锁 */
    private LevelStorageSource.LevelStorageAccess chartStorage;
    public TreeMap<Long, Double> speedIntegration = new TreeMap<>();

    /** 一局结算出的结果（V04：只记成绩与治疗，位置 / 背包等一律回滚到进谱面前） */
    public record Result(String songId, int difficulty, int score, double healValue, int level) {
    }

    /**
     * 收尾原因。{@code code} 是 S2C 的线格式（客户端只按码判断，不加载本类）。
     * <p>
     * 注意：{@code RETRY} 有两处用法——①重试开局：成功后<b>保留</b>恢复点与成绩记录，交给紧接着的
     * {@code startSession} 复用；②隔离重试（RESTORE_FAILED，rt69-04）：成功后调用方必须紧跟
     * {@link #dropSession(UUID)}，因为后面没有"下一局"来接这份恢复点。
     */
    public enum EndReason {
        LOAD_BACK(0), RETRY(1), QUIT(2), RESPAWN(3), STALE(4), ORPHAN_REBIND(5), ORPHAN_GONE(6),
        LEFT_CHART(7), START_FAILED(8), TICK_FAILED(9), STOP_SETTLE(10), SERVER_STOP(11), RESTORE_FAILED(12);

        public final int code;

        EndReason(int code) {
            this.code = code;
        }
    }

    /**
     * 构造器只建世界，不再碰玩家（改玩家全部挪到 {@link #enter(ServerPlayer, ListTag)}，且必须在登记之后，INV4）。
     */
    public PlayingChart(ServerPlayer player, Chart chart, boolean isEditMode, LevelStorageSource.LevelStorageAccess chartStorage) throws IOException {
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
        levelFile = Platform.getGameFolder().resolve("rc_charts/" + chart.song.id + "/" + chart.levelPath + "/level.dat").toFile();
        createdGameTime = server == null ? -1L : server.overworld().getGameTime();
    }

    /**
     * 进局：模式 / 传送 / 矿车 / 背包。调用方必须已经把本局登记进 {@link #playingCharts}（INV4：先拦存档再改玩家），
     * 任何一步抛异常都由调用方走 {@code endSession(START_FAILED)} 按恢复点还原。
     */
    public void enter(ServerPlayer player, ListTag inventoryTag) {
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
        if (isEnded || closing) {
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
                //S6 / V04：结算只记 Result，不在这一刻改玩家；恢复成功后由 applyCarryOver 统一补成绩 + 治疗
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
                int finalScore = (int) Math.round(score);
                sessionResults.computeIfAbsent(player.getUUID(), key -> new ArrayList<>())
                        .add(new Result(chart.song.id, chart.difficulty, finalScore, mentalHealValue, chart.level));
                RhythmCraftPacket.sendGameEnd(player, finalScore, scoreInt, prevScore);
                settled = true;
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

    // ------------------------------------------------------------------ 会话收尾（唯一入口）

    /**
     * 唯一收尾入口（INV8：幂等、可重入）。{@code target} 是要恢复到的"当前在线"玩家对象，null 表示不恢复（磁盘为准）。
     *
     * @return 是否已完全收尾；false 表示进入了 RESTORE_FAILED 隔离态（局留表、恢复点保留、存档继续拦截）
     */
    public static boolean endSession(UUID uuid, @Nullable ServerPlayer target, EndReason reason) {
        if (target != null && !target.getUUID().equals(uuid)) { //rt61-03：防御断言，绝不恢复到别人身上
            LOGGER.error("[RhythmSession] endSession uuid mismatch {} vs {} reason={}", uuid, target.getUUID(), reason);
            return false;
        }
        PlayingChart chart = playingCharts.get(uuid);
        if (chart != null && chart.closing) { //INV8 重入
            return false;
        }
        if (chart == null && !playerData.containsKey(uuid) && !sessionResults.containsKey(uuid)) {
            return true; //幂等：已经收尾过
        }
        if (chart != null) {
            chart.closing = true;
        }
        try {
            boolean restored = true;
            List<Result> unapplied = Collections.emptyList();
            if (target != null && reason != EndReason.SERVER_STOP && reason != EndReason.ORPHAN_GONE) {
                CompoundTag snapshot = playerData.get(uuid);
                if (snapshot == null) {
                    //防御：没有恢复点（理论上 INV1 保证有）时至少把已结算结果补上，不要连成绩一起吞掉
                    unapplied = applyCarryOver(uuid, target);
                }
                else if (tryRestore(target, snapshot)) {
                    unapplied = applyCarryOver(uuid, target);
                }
                else {
                    restored = false;
                }
            }
            if (!restored) {
                //隔离：不删恢复点、不关局、继续拦截存档；玩家下次在线时由巡检重试（§4.6 第 2 条）
                if (chart != null) {
                    chart.restoreFailed = true;
                    chart.isEnded = true;
                    chart.player = target;
                }
                LOGGER.error("[RhythmSession] restore failed uuid={} reason={}, session kept for retry", uuid, reason);
                //rt69-03：失败分支必须在 return false 之前把客户端摘出去，否则客户端停在加载 / 结算屏等一个永不到来的收尾
                if (target != null && needsClientNotice(reason)) {
                    RhythmCraftPacket.sendSessionEndQuietly(target, EndReason.RESTORE_FAILED);
                }
                return false;
            }
            if (reason == EndReason.STOP_SETTLE && !unapplied.isEmpty()) {
                //停服兜底（§4.9.1）：只保结果，玩家本身以磁盘为准；必须在 dropSession 之前取
                PendingGrants.put(server, uuid, unapplied);
            }
            if (chart != null) {
                chart.dispose(); //只关局；dispose 不再删 playerData
            }
            if (reason != EndReason.RETRY) {
                dropSession(uuid); //INV2；重试保留原恢复点（INV3）与成绩记录
            }
            if (target != null && needsClientNotice(reason)) {
                RhythmCraftPacket.sendSessionEnd(target, reason);
            }
            return true;
        } catch (Exception e) {
            LOGGER.error("[RhythmSession] endSession failed uuid={} reason={}", uuid, reason, e);
            return false;
        } finally {
            if (chart != null) {
                chart.closing = false;
            }
        }
    }

    /**
     * 会话两张表（恢复点 + 成绩记录）只能经这里一起删；全文件不许再出现单独的 playerData.remove / sessionResults.remove。
     */
    public static void dropSession(UUID uuid) {
        playerData.remove(uuid);
        sessionResults.remove(uuid);
    }

    /** 需要给客户端发 {@code SESSION_END} 的收尾原因（RETRY / ORPHAN_GONE / STOP_SETTLE / SERVER_STOP 不发）。 */
    private static boolean needsClientNotice(EndReason reason) {
        return switch (reason) {
            case QUIT, LOAD_BACK, RESPAWN, STALE, ORPHAN_REBIND, LEFT_CHART, START_FAILED, TICK_FAILED, RESTORE_FAILED -> true;
            case RETRY, ORPHAN_GONE, STOP_SETTLE, SERVER_STOP -> false;
        };
    }

    /**
     * 把玩家整体还原到恢复点（位置 / 维度 / 背包 / 生命 / 经验 / 情绪 / PTSD / DTT 统计）。
     * 抛任何异常都返回 false，由调用方进入 RESTORE_FAILED 隔离态。
     */
    private static boolean tryRestore(ServerPlayer target, CompoundTag snapshot) {
        try {
            target.stopRiding(); //先下车再整体还原存档，避免骑着矿车被传送
            target.load(snapshot);
            DataResult<ResourceKey<Level>> dataResult = DimensionType.parseLegacy(new Dynamic<>(NbtOps.INSTANCE, snapshot.get("Dimension")));
            ResourceKey<Level> resourceKey = dataResult.result().orElse(Level.OVERWORLD);
            ServerLevel serverLevel = server == null ? null : server.getLevel(resourceKey);
            if (serverLevel != null) {
                target.teleportTo(serverLevel, target.getX(), target.getY(), target.getZ(),
                        target.getYRot(), target.getXRot());
            }
            Registry.particles.remove(target.getUUID().toString());
            return true;
        } catch (Exception e) {
            LOGGER.error("[RhythmSession] tryRestore failed uuid={}", target.getUUID(), e);
            return false;
        }
    }

    /**
     * V04：恢复成功后按结算顺序把本会话所有已结算局的结果补到玩家身上（成绩取最大值、治疗各算一次）。
     * 逐条 try/catch：某条失败只记 ERROR 并继续，返回没补上的条目（停服兜底时写进待补发记录）。
     */
    private static List<Result> applyCarryOver(UUID uuid, ServerPlayer target) {
        List<Result> results = sessionResults.get(uuid);
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }
        List<Result> unapplied = new ArrayList<>();
        RhythmCraftProfile profile = null;
        for (Result result : results) {
            try {
                if (profile == null) {
                    profile = RhythmCraftProfile.getProfileByServerPlayer(target);
                }
                profile.newScore(result.songId(), result.difficulty(), result.score());
                if (result.healValue() > 0) {
                    MentalStatus.getMentalStatusByServerPlayer(target).mentalHeal("rhythmcraft", result.healValue());
                }
            } catch (Exception e) {
                LOGGER.error("[RhythmSession] carry over failed uuid={} song={} difficulty={} score={} heal={} level={}",
                        uuid, result.songId(), result.difficulty(), result.score(), result.healValue(), result.level(), e);
                unapplied.add(result);
            }
        }
        return unapplied;
    }

    /** 停服时（{@code stopServer} 的 {@code PlayerList.saveAll} 之前）把已结算局结算掉，让原版 saveAll 正常写盘（I8）。 */
    public static void settleBeforeStopSave() {
        MinecraftServer current = server;
        for (PlayingChart chart : new ArrayList<>(playingCharts.values())) {
            if (chart.player == null) {
                continue;
            }
            UUID uuid = chart.player.getUUID();
            ServerPlayer live = current == null ? null : current.getPlayerList().getPlayer(uuid);
            boolean liveBound = live != null && live == chart.player && !live.isRemoved();
            try {
                if (chart.settled && liveBound) {
                    //恢复 + 补成绩 / 治疗 + 关局 + dropSession → 不再拦截存档；隔离态在这里等于再试一次恢复（M47）
                    if (!endSession(uuid, live, EndReason.STOP_SETTLE)) {
                        List<Result> results = sessionResults.get(uuid);
                        if (results != null && !results.isEmpty()) {
                            PendingGrants.put(current, uuid, results);
                        }
                    }
                }
                else if (hasResults(uuid)) {
                    //① 已结算但玩家对象已不是在线绑定对象；② 重试链：当前局未结算、本会话前面已有结算局（M49）。
                    //只把本会话全部已结算结果写进待补发记录，不恢复玩家，当前局的临时进度不保留。
                    PendingGrants.put(current, uuid, sessionResults.get(uuid));
                }
                //未结算且本会话没有任何结算结果：什么都不做，留给 RETURN，磁盘为准
            } catch (Exception e) {
                LOGGER.error("[RhythmSession] settle before stop save failed uuid={}", uuid, e);
            }
        }
    }

    private static boolean hasResults(UUID uuid) {
        List<Result> results = sessionResults.get(uuid);
        return results != null && !results.isEmpty();
    }

    /** 单局 tick 抛异常（用户已定：终止这一局并恢复玩家，服务器继续跑）。不捕获 Error。 */
    public static void handleTickFailure(PlayingChart chart, Throwable error) {
        UUID uuid = chart.player == null ? null : chart.player.getUUID();
        LOGGER.error("[RhythmSession] chart tick failed uuid={} song={} difficulty={}",
                uuid, chart.chart == null ? "?" : chart.chart.song.id, chart.chart == null ? -1 : chart.chart.difficulty, error);
        if (uuid == null) {
            chart.dispose();
            return;
        }
        ServerPlayer target = chart.player;
        if (target != null && target.isRemoved()) {
            target = null; //已移除的旧对象不做恢复，交给孤儿巡检
        }
        try {
            endSession(uuid, target, EndReason.TICK_FAILED);
        } catch (Exception e) {
            LOGGER.error("[RhythmSession] TICK_FAILED cleanup failed uuid={}", uuid, e);
            chart.dispose();
        }
    }

    // ------------------------------------------------------------------ 开局

    public static void onReceiveReadChart(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext packetContext) {
        ServerPlayer player = (ServerPlayer) packetContext.getPlayer();
        if (serverStopping(player)) { //rt69-05：停服窗口内不开新局（含重试），不 invokeSave、不登记
            return;
        }
        String songId = buf.readCharSequence(buf.readInt(), StandardCharsets.UTF_8).toString();
        int difficulty = buf.readInt();
        boolean isEditMode = buf.readBoolean();
        executeOnServer(player, () -> {
            if (serverStopping(player)) {
                return;
            }
            if (isEditMode && !player.hasPermissions(2)) {
                return;
            }
            Song song = Song.idMap.get(songId);
            if (song == null) {
                return;
            }
            Chart chart = song.charts.get(difficulty);
            if (chart == null) {
                return;
            }
            if (isEditMode) {
                RhythmCraftPacket.sendAcceptEdit(player);
            }
            startSession(player, song, chart, isEditMode);
        });
    }

    /**
     * §4.4：恢复点先保全，再改玩家。任何一步失败都走 {@link #abortStart}（未改玩家）或 {@code endSession(START_FAILED)}。
     */
    private static void startSession(ServerPlayer player, Song song, Chart chart, boolean isEditMode) {
        UUID uuid = player.getUUID();
        PlayingChart existing = playingCharts.get(uuid);
        if (existing != null) {
            if (existing.closing) {
                return; //收尾中，拒绝
            }
            if (existing.restoreFailed) { //隔离态：拒绝新局 / 重试，等巡检重试成功
                LOGGER.warn("[RhythmSession] {} rejected new attempt: session is in RESTORE_FAILED", uuid);
                RhythmCraftPacket.sendSessionEndQuietly(player, EndReason.START_FAILED);
                return;
            }
            //重试：先恢复玩家到原世界（补上本会话已结算结果），恢复点保持最初那份（INV3）
            if (!endSession(uuid, player, EndReason.RETRY)) {
                //重试的恢复失败：客户端此刻正停在 RCLoadingScreen 等 PLAY_SONG，必须当刻把它摘出去（同 rt69-03 的道理）。
                //这里显式发一次，是因为 endSession 对 RETRY 不发提示（否则每 100 tick 的隔离重试会刷屏）。
                RhythmCraftPacket.sendSessionEndQuietly(player, EndReason.RESTORE_FAILED);
                return; //已隔离，不再继续开局
            }
        }
        else {
            //新会话：先把当前玩家存盘（拦截前最后一次写盘 = 进谱面前的状态），再取恢复点
            try {
                PlayerListAccessor accessor = (PlayerListAccessor) player.server.getPlayerList();
                accessor.invokeSave(player);
            } catch (Exception e) {
                LOGGER.error("[RhythmSession] invokeSave before chart start failed uuid={}", uuid, e);
            }
            playerData.putIfAbsent(uuid, player.saveWithoutId(new CompoundTag()));
            sessionResults.putIfAbsent(uuid, new ArrayList<>());
        }

        //3. 预读谱面 level.dat 的背包（A07：I/O 全部挪到改玩家之前）
        ListTag inventoryTag;
        File levelFile = Platform.getGameFolder()
                .resolve("rc_charts/" + song.id + "/" + chart.levelPath + "/level.dat").toFile();
        try {
            CompoundTag levelTag = NbtIo.readCompressed(levelFile.toPath(), NbtAccounter.unlimitedHeap());
            inventoryTag = levelTag.getCompound("Data").getCompound("Player").getList("Inventory", 10);
        } catch (Exception e) {
            LOGGER.error("[RhythmSession] failed to read chart level.dat song={} difficulty={} path={}",
                    song.id, chart.difficulty, levelFile, e);
            abortStart(player, uuid, EndReason.START_FAILED);
            return;
        }

        //4. 开谱面目录的存取句柄
        LevelStorageSource.LevelStorageAccess access;
        try {
            access = levelStorageSource.createAccess(song.id + "/" + chart.levelPath);
        } catch (Exception e) {
            LOGGER.error("[RhythmSession] failed to open chart storage song={} difficulty={}", song.id, chart.difficulty, e);
            abortStart(player, uuid, EndReason.START_FAILED);
            return;
        }

        //5. 建局（构造器只建世界，不碰玩家）
        PlayingChart newChart;
        try {
            newChart = new PlayingChart(player, chart, isEditMode, access);
        } catch (Exception e) {
            LOGGER.error("[RhythmSession] failed to create chart level song={} difficulty={}", song.id, chart.difficulty, e);
            closeQuietly(access, levelFile);
            abortStart(player, uuid, EndReason.START_FAILED);
            return;
        }

        //6. 先登记（INV4：存档从这里开始被拦截），再改玩家
        playingCharts.put(uuid, newChart);

        //7. 进局
        try {
            newChart.enter(player, inventoryTag);
        } catch (Exception e) {
            LOGGER.error("[RhythmSession] failed to enter chart song={} difficulty={}", song.id, chart.difficulty, e);
            endSession(uuid, player, EndReason.START_FAILED);
        }
    }

    /** 开局失败且玩家从未被改动：两张表一起删（rt61-01），再通知客户端摘屏。 */
    private static void abortStart(ServerPlayer player, UUID uuid, EndReason reason) {
        dropSession(uuid);
        RhythmCraftPacket.sendSessionEndQuietly(player, reason);
    }

    private static void closeQuietly(LevelStorageSource.LevelStorageAccess access, File levelFile) {
        try {
            access.close();
        } catch (Exception e) {
            LOGGER.warn("[RhythmSession] failed to close chart storage {}", levelFile, e);
        }
    }

    // ------------------------------------------------------------------ 包处理（主线程 + 状态校验 + 停服守卫）

    /**
     * 停服窗口内一律丢弃 C2S（rt69-05）：{@code execute} 会进一个不再被消费的队列（永不执行），
     * 或在非服务器线程上内联跑一段会碰世界 / 表的代码。
     * <p>
     * 同时判 {@code !isRunning()}：{@code /stop} 是 {@code halt} 先写 {@code running=false}、线程退出循环后才写
     * {@code stopped=true}，中间有一个窗口。只判 {@code !isRunning()} 会漏掉 crash / throwable 两条退出路径
     * （那两条不经过 halt，{@code running} 仍为 true），所以两个都判。
     */
    private static boolean serverStopping(@Nullable ServerPlayer player) {
        MinecraftServer current = player == null ? null : player.server;
        return current == null || current.isStopped() || !current.isRunning();
    }

    private static void executeOnServer(ServerPlayer player, Runnable task) {
        MinecraftServer current = player.server;
        if (current == null) {
            return;
        }
        try {
            current.execute(task);
        } catch (RejectedExecutionException e) {
            LOGGER.debug("[RhythmSession] server already shutting down, drop rhythm packet task");
        }
    }

    public static void onReceiveReady(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext packetContext) {
        ServerPlayer player = (ServerPlayer) packetContext.getPlayer();
        if (serverStopping(player)) {
            return;
        }
        executeOnServer(player, () -> {
            PlayingChart playingChart = playingCharts.get(player.getUUID());
            if (playingChart == null || playingChart.isDisposed || playingChart.closing || playingChart.restoreFailed) {
                return;
            }
            if (playingChart.player != player) {
                return; //旧玩家对象迟到的包
            }
            if (playingChart.isJustStarted != null) {
                return; //重复 READY
            }
            playingChart.start();
        });
    }

    public static void onReceiveLoadBack(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext packetContext) {
        ServerPlayer player = (ServerPlayer) packetContext.getPlayer();
        if (serverStopping(player)) {
            return;
        }
        executeOnServer(player, () -> {
            UUID uuid = player.getUUID();
            PlayingChart playingChart = playingCharts.get(uuid);
            if (playingChart == null && !playerData.containsKey(uuid)) {
                return; //重复 LOAD_BACK：已收尾
            }
            if (playingChart != null && (playingChart.closing || playingChart.restoreFailed)) {
                return; //隔离态交给巡检重试，不接受 LOAD_BACK
            }
            endSession(uuid, player, EndReason.LOAD_BACK);
        });
    }

    public static void onReceivePauseChange(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext packetContext) {
        ServerPlayer player = (ServerPlayer) packetContext.getPlayer();
        if (serverStopping(player)) {
            return;
        }
        long tick = buf.readLong();
        executeOnServer(player, () -> {
            PlayingChart playingChart = playingCharts.get(player.getUUID());
            if (!isLivePlaying(playingChart, player)) {
                return; //非 PLAYING（含重试后旧局迟到的包）一律丢弃
            }
            playingChart.isPaused = !playingChart.isPaused;
            if (playingChart.isPaused) {
                playingChart.seek(tick);
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
        ServerPlayer player = (ServerPlayer) packetContext.getPlayer();
        if (serverStopping(player)) {
            return;
        }
        long tick = buf.readLong();
        executeOnServer(player, () -> {
            PlayingChart playingChart = playingCharts.get(player.getUUID());
            if (!isLivePlaying(playingChart, player)) {
                return;
            }
            playingChart.tickCount = tick;
            playingChart.seek(tick);
        });
    }

    /** 只接受"当前玩家对象、已 READY、未结束、未隔离、未收尾"的局（INV7 的读改都在主线程里做）。 */
    private static boolean isLivePlaying(@Nullable PlayingChart chart, ServerPlayer player) {
        return chart != null
                && !chart.isDisposed
                && !chart.closing
                && !chart.restoreFailed
                && !chart.isEnded
                && chart.isJustStarted != null
                && !chart.isJustStarted
                && chart.player == player;
    }

    // ------------------------------------------------------------------ 重生 / 退出 / 巡检 / 关服

    /** 重生挂点（A08）：{@code PlayerList.respawn} RETURN 调用，此时旧玩家已从谱面世界移除、新玩家已登记。 */
    public static void onPlayerRespawn(@Nullable ServerPlayer oldPlayer, @Nullable ServerPlayer newPlayer) {
        if (oldPlayer == null || newPlayer == null) {
            return;
        }
        PlayingChart chart = playingCharts.get(oldPlayer.getUUID());
        if (chart == null || chart.isDisposed) {
            return;
        }
        if (chart.player == null || !chart.player.getUUID().equals(oldPlayer.getUUID())) {
            return;
        }
        try {
            endSession(oldPlayer.getUUID(), newPlayer, EndReason.RESPAWN);
        } catch (Exception e) {
            LOGGER.error("[RhythmSession] RESPAWN cleanup failed uuid={}", oldPlayer.getUUID(), e);
        }
    }

    /** 玩家退出（含关服时的 {@code PlayerList.removeAll}）兜底：统一走 endSession（t70 的 remove HEAD 会吞异常，这里自己 catch）。 */
    public static void onPlayerQuit(ServerPlayer player) {
        if (player == null) {
            return;
        }
        try {
            endSession(player.getUUID(), player, EndReason.QUIT);
        } catch (Exception e) {
            LOGGER.error("[RhythmSession] QUIT cleanup failed uuid={}", player.getUUID(), e);
        }
    }

    /** 关服兜底（stopServer RETURN）：未结算局以磁盘为准，并清空静态表与静态字段（C11 / C09）。 */
    public static void disposeAll() {
        for (PlayingChart playingChart : new ArrayList<>(playingCharts.values())) {
            UUID uuid = playingChart.player == null ? null : playingChart.player.getUUID();
            if (uuid == null) {
                playingChart.dispose();
                continue;
            }
            try {
                endSession(uuid, null, EndReason.SERVER_STOP);
            } catch (Exception e) {
                LOGGER.error("[RhythmSession] SERVER_STOP cleanup failed uuid={}", uuid, e);
                playingChart.dispose();
            }
        }
        //removeAll 只断连接、不走 PlayerList.remove（C09），所以静态表必须在这里兜底清空
        for (UUID uuid : new ArrayList<>(playerData.keySet())) {
            dropSession(uuid);
        }
        for (UUID uuid : new ArrayList<>(sessionResults.keySet())) {
            dropSession(uuid);
        }
        playingCharts.clear();
        server = null;
        executor = null;
        worldData = null;
        chartStem = null;
        chunkProgressListener = null;
        randomSequences = null;
        //levelStorageSource 是进程级的，保留
    }

    /** 每 tick 巡检：谱面结束但客户端一直不发 LOAD_BACK 时，超时回收。 */
    public static void cleanupStaleCharts() {
        for (PlayingChart playingChart : new ArrayList<>(playingCharts.values())) {
            playingChart.checkStale();
        }
    }

    /**
     * 固定顺序判定（rt61-04：孤儿必须排在 5 分钟超时之前，否则会对已移除的旧玩家对象做恢复）：
     * 孤儿 → 隔离重试 → 离开谱面 → 结算超时 → 加载超时。命中一条就收尾并结束本局判定。
     */
    private void checkStale() {
        if (isDisposed || closing) {
            return;
        }
        if (player == null) {
            dispose();
            return;
        }
        MinecraftServer current = server;
        if (current == null) {
            return;
        }
        UUID uuid = player.getUUID();

        //1. 孤儿：绑定玩家已移除。在线有同 UUID 新对象就改绑恢复，否则不恢复（磁盘为准）
        if (player.isRemoved()) {
            ServerPlayer live = current.getPlayerList().getPlayer(uuid);
            if (live != null && !live.isRemoved() && live != player) {
                endSession(uuid, live, EndReason.ORPHAN_REBIND);
            }
            else if (live == null) {
                endSession(uuid, null, EndReason.ORPHAN_GONE);
            }
            return;
        }

        //2. 隔离重试（rt69-04：复用 EndReason.RETRY，成功后必须 dropSession）
        if (restoreFailed) {
            long now = current.overworld().getGameTime();
            if (now - lastRestoreRetryTick >= RESTORE_RETRY_INTERVAL_TICKS) {
                lastRestoreRetryTick = now;
                retryRestore(this);
            }
            return;
        }

        //3. 离开谱面（建议项，M27）：LOADING / PLAYING 时玩家被 /tp 等带走
        if (!isEnded && player.level() != this) {
            endSession(uuid, player, EndReason.LEFT_CHART);
            return;
        }

        //4. 结算超时：ENDED 超过 5 分钟
        if (isEnded) {
            if (endedGameTime >= 0 && current.overworld().getGameTime() - endedGameTime > END_IDLE_TIMEOUT_TICKS) {
                endSession(uuid, player, EndReason.STALE);
            }
            return;
        }

        //5. 加载超时：LOADING 超过 60 秒没收到 READY（rt61-05）
        if (isJustStarted == null && createdGameTime >= 0
                && current.overworld().getGameTime() - createdGameTime > LOADING_TIMEOUT_TICKS) {
            endSession(uuid, player, EndReason.START_FAILED);
        }
    }

    /**
     * 隔离态重试：复用 {@code EndReason.RETRY}（成功保留恢复点 + 不发提示），成功后必须紧跟 {@link #dropSession(UUID)}——
     * 隔离重试后面没有"下一局"来接这份恢复点，留着会让下一次新会话的 putIfAbsent 复用上一次会话的陈旧快照。
     */
    private static void retryRestore(PlayingChart chart) {
        MinecraftServer current = server;
        if (current == null || chart.player == null) {
            return;
        }
        ServerPlayer live = current.getPlayerList().getPlayer(chart.player.getUUID());
        if (live == null || live.isRemoved()) {
            return; //孤儿交给第 1 条
        }
        UUID uuid = live.getUUID();
        if (endSession(uuid, live, EndReason.RETRY)) {
            dropSession(uuid);
            chart.restoreFailed = false;
        }
    }

    /**
     * B 修复：幂等回收谱面。只关局（矿车 / 世界 / 存储句柄 / 从 playingCharts 按身份移除），
     * <b>不再删 playerData</b>——恢复点的删除只由 {@link #endSession} / {@link #dropSession(UUID)} 决定（A03 / A07 的根源）。
     */
    public void dispose() {
        if (isDisposed) {
            return;
        }
        isDisposed = true;
        UUID uuid = player == null ? null : player.getUUID();
        if (player != null && !player.isRemoved() && minecart != null && player.getVehicle() == minecart) {
            player.stopRiding(); //重生路径里旧玩家已移除，不再碰它
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
        }
        try {
            close(); //关闭 chunkSource / entityManager
        } catch (Exception e) {
            LOGGER.warn("Failed to close playing chart level {}", levelFile, e);
        }
        if (chartStorage != null) {
            try {
                chartStorage.close(); //ServerLevel 不持有 LevelStorageAccess，这里释放谱面目录的 session 锁
            } catch (Exception e) {
                LOGGER.warn("Failed to close playing chart storage {}", levelFile, e);
            }
            chartStorage = null;
        }
    }

}
