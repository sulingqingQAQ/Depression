package net.depression.server;

import net.depression.mental.MentalStatus;
import net.depression.rhythmcraft.PlayingChart;
import net.depression.rhythmcraft.RhythmCraftProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 停服待补发记录（I8 的失败兜底，§4.9.1）。
 * <p>
 * 只存成绩与治疗（{@link PlayingChart.Result}），不存位置 / 背包 / 任何其它状态。只在 {@code stopServer} 的
 * {@code PlayerList.saveAll} 之前写：那次恢复 / 补发没成功的条目才会进来，补上的部分已经由原版 saveAll 写进 {@code .dat}。
 * <p>
 * 存主世界的 {@code DimensionDataStorage}，名字 {@code depression_rc_pending}（落盘 {@code <世界>/data/depression_rc_pending.dat}），
 * 跟着停服时 offset 224 的 {@code saveAllChunks} 落盘。玩家下次进服（{@code PlayerEvent.PLAYER_JOIN}）逐条补
 * {@code newScore} + {@code mentalHeal}，然后<b>立即删记录</b>并 {@code setDirty()}。
 * <p>
 * <b>两个崩溃窄窗（rt69-02，用户已知并接受）</b>：
 * <ul>
 *   <li><b>窗 A（丢）</b>：补发完成、该玩家 {@code .dat} 还没落盘就被强杀 / 崩溃 → 补上的成绩 / 治疗随内存消失。</li>
 *   <li><b>窗 B（重）</b>：{@code .dat} 已写成功、记录删除还没落盘就被强杀 → 磁盘上记录仍在，下次进服再补一遍 →
 *       治疗翻倍（成绩取最大值，幂等）。</li>
 * </ul>
 * 所以正确表述是"正常路径各算一次；崩溃落在两个不同时间点，分别对应丢失与重复"，<b>不是</b>"绝对不会算两次"。
 * 要消掉窗 B 必须先有 {@code grantId} + 玩家侧幂等标记（另一轮设计），不能只把删除往后推（那会把窗 A 换成窗 B）。
 */
public final class PendingGrants extends SavedData {
    private static final Logger LOGGER = LoggerFactory.getLogger(PendingGrants.class);
    private static final String DATA_NAME = "depression_rc_pending";
    private static final String KEY_PLAYERS = "players";
    private static final String KEY_UUID = "uuid";
    private static final String KEY_RESULTS = "results";

    /**
     * DataFixTypes 必须非空：{@code DimensionDataStorage.readTagFromDisk} 会直接调
     * {@code DataFixTypes.update(...)}，传 null 时 NPE 被 catch 掉、返回 null，记录会静默丢失（M45 会挂）。
     * 落盘时 {@code SavedData.save(File, provider)} 写入的是<b>当前</b> DataVersion，读回时版本相同 → DFU 是空操作，
     * 所以这里选哪个类型都不影响我们自己的结构（选 RANDOM_SEQUENCES 只是因为它对未知字段最不敏感）。
     */
    private static final SavedData.Factory<PendingGrants> FACTORY = new SavedData.Factory<>(
            PendingGrants::new, PendingGrants::load, DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    private final Map<UUID, List<PlayingChart.Result>> pending = new HashMap<>();

    private PendingGrants() {
    }

    /** 取（或首次创建）待补发记录；服务器 / 主世界不可用时返回 null。 */
    @Nullable
    public static PendingGrants get(@Nullable MinecraftServer server) {
        if (server == null) {
            return null;
        }
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return null;
        }
        try {
            return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
        } catch (Exception e) {
            LOGGER.error("[RhythmSession] failed to load pending grants", e);
            return null;
        }
    }

    /** 追加（不是覆盖）：同一玩家连续几次停服都没进服时记录累积。 */
    public static void put(@Nullable MinecraftServer server, @Nullable UUID uuid, @Nullable List<PlayingChart.Result> results) {
        if (uuid == null || results == null || results.isEmpty()) {
            return;
        }
        PendingGrants data = get(server);
        if (data == null) {
            LOGGER.error("[RhythmSession] cannot persist pending grants (data storage unavailable) uuid={} results={}", uuid, results);
            return;
        }
        data.pending.computeIfAbsent(uuid, key -> new ArrayList<>()).addAll(results);
        data.setDirty();
        LOGGER.warn("[RhythmSession] {} rhythm result(s) queued for re-grant uuid={} (server stopping)", results.size(), uuid);
    }

    /**
     * 玩家进服补发（Architectury {@code PlayerEvent.PLAYER_JOIN}，此时 {@code .dat} 已读完）。
     * 补发即删：先摘记录、再逐条补，失败只记 ERROR（带完整结果）不无限重试。
     * <p>
     * 停服窗口里直接返回（rt69-05）：正常停服 {@code removeAll} 不走登录，但第三方模组手动走登录路径时，
     * 补发会写进一个不会被保存的玩家对象。
     */
    public static void onPlayerJoin(ServerPlayer player) {
        if (player == null) {
            return;
        }
        MinecraftServer server = player.server;
        if (server == null || server.isStopped()) {
            return;
        }
        PendingGrants data = get(server);
        if (data == null) {
            return;
        }
        UUID uuid = player.getUUID();
        List<PlayingChart.Result> results = data.pending.remove(uuid);
        if (results == null || results.isEmpty()) {
            return;
        }
        data.setDirty();
        RhythmCraftProfile profile = null;
        MentalStatus mentalStatus = null;
        int applied = 0;
        for (PlayingChart.Result result : results) {
            try {
                if (profile == null) {
                    profile = RhythmCraftProfile.getProfileByServerPlayer(player);
                }
                profile.newScore(result.songId(), result.difficulty(), result.score());
                if (result.healValue() > 0) {
                    if (mentalStatus == null) {
                        mentalStatus = MentalStatus.getMentalStatusByServerPlayer(player);
                    }
                    mentalStatus.mentalHeal("rhythmcraft", result.healValue());
                }
                ++applied;
            } catch (Exception e) {
                LOGGER.error("[RhythmSession] failed to re-grant rhythm result uuid={} song={} difficulty={} score={} heal={} level={}",
                        uuid, result.songId(), result.difficulty(), result.score(), result.healValue(), result.level(), e);
            }
        }
        LOGGER.info("[RhythmSession] re-granted {}/{} queued rhythm result(s) uuid={}", applied, results.size(), uuid);
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider provider) {
        ListTag players = new ListTag();
        for (Map.Entry<UUID, List<PlayingChart.Result>> entry : pending.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID(KEY_UUID, entry.getKey());
            ListTag results = new ListTag();
            for (PlayingChart.Result result : entry.getValue()) {
                CompoundTag resultTag = new CompoundTag();
                resultTag.putString("song", result.songId());
                resultTag.putInt("difficulty", result.difficulty());
                resultTag.putInt("score", result.score());
                resultTag.putDouble("heal", result.healValue());
                resultTag.putInt("level", result.level());
                results.add(resultTag);
            }
            playerTag.put(KEY_RESULTS, results);
            players.add(playerTag);
        }
        tag.put(KEY_PLAYERS, players);
        return tag;
    }

    private static PendingGrants load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider provider) {
        PendingGrants data = new PendingGrants();
        ListTag players = tag.getList(KEY_PLAYERS, 10);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag playerTag = players.getCompound(i);
            if (!playerTag.hasUUID(KEY_UUID)) {
                continue;
            }
            UUID uuid = playerTag.getUUID(KEY_UUID);
            ListTag results = playerTag.getList(KEY_RESULTS, 10);
            List<PlayingChart.Result> list = new ArrayList<>(results.size());
            for (int j = 0; j < results.size(); j++) {
                CompoundTag resultTag = results.getCompound(j);
                list.add(new PlayingChart.Result(
                        resultTag.getString("song"),
                        resultTag.getInt("difficulty"),
                        resultTag.getInt("score"),
                        resultTag.getDouble("heal"),
                        resultTag.getInt("level")));
            }
            if (!list.isEmpty()) {
                data.pending.put(uuid, list);
            }
        }
        return data;
    }
}
