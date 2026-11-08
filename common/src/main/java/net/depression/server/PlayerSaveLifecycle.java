package net.depression.server;

import net.depression.Depression;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 保存生命周期协调（S1）：登出 / 登录 / 关服时何时清、清什么。
 * <ul>
 *   <li>NBT 序列化回调是纯读，绝不清理（见 {@link net.depression.mixin.PlayerMixin}）。</li>
 *   <li>登出清理只在 {@code PlayerList.remove} 的 RETURN（该次 remove 自身的 save 之后）。</li>
 *   <li>登录（{@code placeNewPlayer} HEAD）只清“没有在线旧会话”的残留。</li>
 *   <li>服务器停止后（SERVER_STOPPED）与开始前（{@link Registry#init()}）全量清扫。</li>
 *   <li>单人房主的持久状态在 remove RETURN 不清，留到 SERVER_STOPPED：单人服随即停止，
 *       且 {@code IntegratedPlayerList.playerData}（写 level.dat 用）之后仍可能被重新捕获，保留内存状态能保证任何后续捕获都是完整数据。</li>
 *   <li>死亡重生 / 换维度不经过 remove，不会触发任何清理。</li>
 * </ul>
 */
public final class PlayerSaveLifecycle {
    private static final Logger LOGGER = Depression.LOGGER;
    private static final String TAG = "[Depression/Save]";
    private static final int EVICTED_CAP = 256;

    /**
     * 刚被登出清理过的实体（UUID → 实体 id），用来拦截“清理之后又对同一个实体调 PlayerList.save”（二次 remove、第三方误调），
     * 否则那次保存会因状态已清而写出缺 mod 键的 NBT 覆盖掉 .dat（INV-S8）。
     * 只记最近 {@link #EVICTED_CAP} 个，不持有实体引用；实体 id 在 JVM 内唯一。
     */
    private static final Map<UUID, Integer> evictedEntities = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Integer> eldest) {
            return size() > EVICTED_CAP;
        }
    });

    /** 状态缺失告警去重（同一 UUID 同一类只告警一次） */
    private static final Set<String> missingWarned = ConcurrentHashMap.newKeySet();

    private PlayerSaveLifecycle() {
    }

    // ---------------------------------------------------------------- 登出

    /** PlayerList.remove RETURN：此时该次 remove 自己的 save 已经执行完 */
    public static void finishLogout(PlayerList playerList, ServerPlayer player) {
        UUID uuid = player.getUUID();
        PlayerSaveTracker.LogoutResult result = PlayerSaveTracker.finishLogout(uuid);
        try {
            boolean owner = player.server.isSingleplayerOwner(player.getGameProfile());
            logLogout(player, result, owner);
            if (playerList.getPlayer(uuid) != null) {
                //同 UUID 已有更新（或仍在线）的会话：注册表现在属于它，不动
                LOGGER.warn("{} logout cleanup skipped uuid={} (a session with the same uuid is online)", TAG, uuid);
                return;
            }
            Registry.clearTransient(uuid, player); //pending / 队列 / 日记挂起：与写盘结果无关，一律清
            if (!owner) {
                Registry.evictPersistent(uuid);
                evictedEntities.put(uuid, player.getId());
                missingWarned.removeIf(key -> key.startsWith(uuid.toString()));
            }
        }
        catch (RuntimeException e) {
            //清理失败不能打断 remove：残留由登录对账和 SERVER_STOPPED 兜底
            LOGGER.error("{} logout cleanup failed uuid={}", TAG, uuid, e);
        }
    }

    private static void logLogout(ServerPlayer player, PlayerSaveTracker.LogoutResult r, boolean owner) {
        String name = player.getGameProfile().getName();
        switch (r.outcome()) {
            case COMMITTED, NOT_ATTEMPTED -> LOGGER.info(
                    "{} logout uuid={} name={} result={} ser={} attempts={} window={}ms owner={}",
                    TAG, player.getUUID(), name, r.outcome(), r.serializations(), r.attempts(), r.windowMillis(), owner);
            case SUPPRESSED -> LOGGER.warn(
                    "{} logout uuid={} name={} result=SUPPRESSED (rhythm chart blocked the save on purpose, disk is the source of truth) ser={} owner={}",
                    TAG, player.getUUID(), name, r.serializations(), owner);
            case FAILED_IO, FAILED_REPLACE -> LOGGER.error(
                    "{} logout uuid={} name={} result={} ser={} attempts={} window={}ms owner={} {} -- save did not reach disk, state rolls back to the last successful save",
                    TAG, player.getUUID(), name, r.outcome(), r.serializations(), r.attempts(), r.windowMillis(), owner, r.detail());
        }
    }

    // ---------------------------------------------------------------- 登录

    /**
     * PlayerList.placeNewPlayer HEAD（早于 PlayerList.load）：登录 = 干净起点。
     * 同 UUID 没有在线旧会话时，上一次没走完的退出留下的残留一律作废；有在线旧会话则不动。
     */
    public static void onLoginBegin(PlayerList playerList, ServerPlayer player) {
        UUID uuid = player.getUUID();
        PlayerSaveTracker.clearWindow(uuid);
        if (playerList.getPlayer(uuid) != null) {
            LOGGER.warn("{} login uuid={} while a session with the same uuid is still online, keep registry state", TAG, uuid);
            return;
        }
        boolean stale = Registry.hasPersistentState(uuid);
        Registry.evictPersistent(uuid);
        Registry.clearTransient(uuid, null);
        evictedEntities.remove(uuid);
        if (stale) {
            LOGGER.warn("{} login uuid={} dropped state left behind by an unfinished logout", TAG, uuid);
        }
    }

    // ---------------------------------------------------------------- 关服 / 重置

    /** SERVER_STOPPED：全量清扫（含单人房主的持久状态） */
    public static void onServerStopped() {
        Registry.resetAll();
    }

    /** 由 {@link Registry#resetAll()} 调用：写盘追踪、已清理实体记录、告警去重一起重置 */
    static void reset() {
        PlayerSaveTracker.reset();
        evictedEntities.clear();
        missingWarned.clear();
    }

    // ---------------------------------------------------------------- 序列化 / 保存辅助

    /** 该实体是否已被登出清理（INV-S8：已清理的实体不得再被 PlayerList.save 写盘） */
    public static boolean isEvicted(ServerPlayer player) {
        Integer entityId = evictedEntities.get(player.getUUID());
        return entityId != null && entityId == player.getId();
    }

    /** 序列化时发现状态缺失：只告警一次，不写默认值 */
    public static void warnMissingOnce(UUID uuid, String what) {
        if (missingWarned.size() > 1024) {
            missingWarned.clear();
        }
        if (missingWarned.add(uuid + ":" + what)) {
            LOGGER.warn("{} serialize uuid={} without {} (state missing: keys skipped, no default written)", TAG, uuid, what);
        }
    }
}
