package net.depression.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 写盘探针（保存生命周期 S2）：只观测，不改变保存行为。
 * <p>
 * 原版 {@code PlayerDataStorage.save} 会吞掉写盘异常，而且 {@code Util.safeReplaceFile} 返回 void、
 * 把 {@code safeReplaceOrMoveFile} 的 boolean 直接丢掉（换文件失败连异常都没有），所以
 * {@code PlayerList.remove} 返回、{@code PlayerDataStorage.save} 正常返回、SaveToFile 事件都不能当作“写盘成功”。
 * 这里在 {@link net.depression.mixin.PlayerDataStorageMixin} 的三个点上记录事实：
 * <ul>
 *   <li>O1 换文件之后检查事后条件 {@code !exists(tmp) && exists(dat)}：为真才是“提交”。
 *       已知极端 race：rename 成功但随后的存在性检查失败时，原版会把 {@code .dat_old} 改回 {@code .dat}，
 *       此时探针仍会判提交，但内容可能是旧档（概率极低，原版 runWithRetries 固有语义，S1+S2 不做对策）。</li>
 *   <li>O2 写临时文件抛异常 → FAILED_IO；O3 保存开始了但从未走到写盘步骤（序列化自己抛）→ FAILED_IO。</li>
 *   <li>O4 音游拦截 {@code PlayerList.save} → SUPPRESSED（故意不写，磁盘为准，不是失败）。</li>
 *   <li>O5 登出窗口内没有任何保存尝试 → NOT_ATTEMPTED（多半是别的模组接管了持久化）。</li>
 * </ul>
 * 结果只用于日志与 {@link #observe}。S3（失败快照 / 重试）已冻结：这里<b>不持有</b> NBT 引用，失败行为仍是原版的整档回滚。
 * 所有方法在服务器线程调用；容器用并发集合只是防御。
 */
public final class PlayerSaveTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerSaveTracker.class);
    private static final int MAP_CAP = 1024;

    public enum Outcome { COMMITTED, FAILED_IO, FAILED_REPLACE, SUPPRESSED, NOT_ATTEMPTED }

    /** 一次登出窗口（remove HEAD → RETURN）的结果 */
    public record LogoutResult(Outcome outcome, int serializations, int attempts, long windowMillis, String detail) {
    }

    /** 登出窗口内累计的事实 */
    private static final class Window {
        final long startNanos = System.nanoTime();
        int serializations;
        int attempts;
        boolean committed;
        boolean suppressed;
        Outcome lastFailure;
        String detail = "";
    }

    /** 一次 PlayerDataStorage.save 调用 */
    private static final class Attempt {
        final UUID id;
        final String name;
        final boolean serverRunning;
        boolean written;
        boolean writeFailed;
        Boolean committed; //null = 没走到换文件这一步
        Path dat;
        Path tmp;
        Throwable error;

        Attempt(UUID id, String name, boolean serverRunning) {
            this.id = id;
            this.name = name;
            this.serverRunning = serverRunning;
        }
    }

    /** observe() 的作用域 */
    private static final class ObserveScope {
        final UUID id;
        Outcome last;
        boolean anyCommitted;
        boolean suppressed;

        ObserveScope(UUID id) {
            this.id = id;
        }

        Outcome result() {
            if (anyCommitted) {
                return Outcome.COMMITTED;
            }
            if (last != null) {
                return last;
            }
            return suppressed ? Outcome.SUPPRESSED : Outcome.NOT_ATTEMPTED;
        }
    }

    private static final Map<UUID, Window> windows = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private static final ThreadLocal<Attempt> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<ObserveScope> SCOPE = new ThreadLocal<>();

    private PlayerSaveTracker() {
    }

    // ---------------------------------------------------------------- 登出窗口

    /** PlayerList.remove HEAD：开窗口（清零该 UUID 之前的记录） */
    public static void beginLogout(UUID id) {
        windows.put(id, new Window());
    }

    /** PlayerList.remove RETURN：取走并关闭窗口。没有窗口 → NOT_ATTEMPTED */
    public static LogoutResult finishLogout(UUID id) {
        Window w = windows.remove(id);
        consecutiveFailures.remove(id);
        if (w == null) {
            return new LogoutResult(Outcome.NOT_ATTEMPTED, 0, 0, 0L, "no logout window");
        }
        long millis = (System.nanoTime() - w.startNanos) / 1_000_000L;
        Outcome outcome;
        if (w.committed) {
            outcome = Outcome.COMMITTED; //窗口内任一次提交即视为提交（几次尝试序列化的是同一状态）
        }
        else if (w.attempts > 0) {
            outcome = w.lastFailure != null ? w.lastFailure : Outcome.FAILED_IO;
        }
        else if (w.suppressed) {
            outcome = Outcome.SUPPRESSED;
        }
        else {
            outcome = Outcome.NOT_ATTEMPTED;
        }
        return new LogoutResult(outcome, w.serializations, w.attempts, millis, w.detail);
    }

    /** 登录时丢弃该 UUID 残留的窗口 / 失败计数 */
    public static void clearWindow(UUID id) {
        windows.remove(id);
        consecutiveFailures.remove(id);
    }

    /** NBT 写回调每执行一次记一次（只统计登出窗口内的序列化次数，用于日志） */
    public static void noteSerialization(UUID id) {
        Window w = windows.get(id);
        if (w != null) {
            w.serializations++;
        }
    }

    /** PlayerListMixin.onSave：音游拦截了 PlayerList.save（故意不写，磁盘为准） */
    public static void noteSuppressed(UUID id) {
        Window w = windows.get(id);
        if (w != null) {
            w.suppressed = true;
        }
        ObserveScope scope = SCOPE.get();
        if (scope != null && scope.id.equals(id)) {
            scope.suppressed = true;
        }
    }

    // ---------------------------------------------------------------- PlayerDataStorage.save 的三个观测点

    public static void onSaveBegin(Player player) {
        //t69 实证（javap，21.1.219 / 21.1.230 两版一致）：stopServer 期间 running 仍可能为 true——
        //running 只由构造器与 halt(boolean) 写 false，而 crash / throwable 两条退出路径不经过 halt；
        //stopped 由 runServer 三条退出路径各自在 stopServer() 之前写，所以“正在停服”的精确判据是 isStopped()。
        boolean stopping = player instanceof ServerPlayer serverPlayer && serverPlayer.server != null
                && serverPlayer.server.isStopped();
        onSaveBegin(player.getUUID(), player.getGameProfile().getName(), !stopping);
    }

    static void onSaveBegin(UUID id, String name, boolean serverRunning) {
        CURRENT.set(new Attempt(id, name, serverRunning));
        Window w = windows.get(id);
        if (w != null) {
            w.attempts++;
        }
    }

    /** O2 成功：临时文件已写完 */
    public static void onWriteDone(Player player, Path tmp) {
        onWriteDone(player.getUUID(), tmp);
    }

    static void onWriteDone(UUID id, Path tmp) {
        Attempt a = current(id);
        if (a != null) {
            a.written = true;
            a.tmp = tmp;
        }
    }

    /** O2 失败：写临时文件抛异常（调用方随后原样重新抛出，让原版 catch 照旧记 warn） */
    public static void onWriteFailed(Player player, Path tmp, Throwable error) {
        onWriteFailed(player.getUUID(), tmp, error);
    }

    static void onWriteFailed(UUID id, Path tmp, Throwable error) {
        Attempt a = current(id);
        if (a != null) {
            a.writeFailed = true;
            a.tmp = tmp;
            a.error = error;
        }
    }

    /** O1：换文件之后的事后条件。committed = !exists(tmp) && exists(dat) */
    public static void onReplaceDone(Player player, Path dat, Path tmp, boolean committed) {
        onReplaceDone(player.getUUID(), dat, tmp, committed);
    }

    static void onReplaceDone(UUID id, Path dat, Path tmp, boolean committed) {
        Attempt a = current(id);
        if (a != null) {
            a.dat = dat;
            a.tmp = tmp;
            a.committed = committed;
        }
    }

    /** PlayerDataStorage.save RETURN：一次保存结束，归档结果 */
    public static void onSaveEnd(Player player) {
        onSaveEnd(player.getUUID());
    }

    static void onSaveEnd(UUID id) {
        Attempt a = CURRENT.get();
        CURRENT.remove();
        if (a == null || !a.id.equals(id)) {
            return;
        }
        Outcome outcome = classify(a);
        Window w = windows.get(id);
        if (w != null) {
            if (outcome == Outcome.COMMITTED) {
                w.committed = true;
            }
            else {
                w.lastFailure = outcome;
                w.detail = describe(a);
            }
        }
        else if (outcome == Outcome.COMMITTED) {
            consecutiveFailures.remove(id);
        }
        else {
            if (consecutiveFailures.size() > MAP_CAP) {
                consecutiveFailures.clear();
            }
            int n = consecutiveFailures.merge(id, 1, Integer::sum);
            if (!a.serverRunning) {
                //服务器已在停止流程（stopServer 里的 saveAll）：这是最后一次落盘机会，失败要大声报
                LOGGER.error("[Depression/Save] shutdown save FAILED uuid={} name={} result={} {}", id, a.name, outcome, describe(a));
            }
            else if (n >= 2) {
                LOGGER.warn("[Depression/Save] save failed {} times in a row uuid={} name={} result={} {}", n, id, a.name, outcome, describe(a));
            }
        }
        ObserveScope scope = SCOPE.get();
        if (scope != null && scope.id.equals(id)) {
            scope.last = outcome;
            if (outcome == Outcome.COMMITTED) {
                scope.anyCommitted = true;
            }
        }
    }

    // ---------------------------------------------------------------- 对外 API

    /**
     * 执行一次保存调用并返回它的写盘结果（I4，供音游开局等“需要明确持久化结果”的调用方使用）。
     * 注意：对单人房主，这里只反映 {@code playerdata/<uuid>.dat} 镜像的写盘结果，level.dat 仍不可观测，应视为“尽力而为”的信号。
     */
    public static Outcome observe(UUID id, Runnable saveCall) {
        ObserveScope previous = SCOPE.get();
        ObserveScope scope = new ObserveScope(id);
        SCOPE.set(scope);
        try {
            saveCall.run();
        }
        finally {
            if (previous == null) {
                SCOPE.remove();
            }
            else {
                SCOPE.set(previous);
            }
        }
        return scope.result();
    }

    /** 服务器开始前 / 停止后重置 */
    public static void reset() {
        windows.clear();
        consecutiveFailures.clear();
        CURRENT.remove();
        SCOPE.remove();
    }

    // ---------------------------------------------------------------- 内部

    private static Attempt current(UUID id) {
        Attempt a = CURRENT.get();
        return a != null && a.id.equals(id) ? a : null;
    }

    private static Outcome classify(Attempt a) {
        if (a.committed != null && a.committed) {
            return Outcome.COMMITTED;
        }
        if (a.writeFailed) {
            return Outcome.FAILED_IO;
        }
        if (a.committed != null || a.written) {
            return Outcome.FAILED_REPLACE; //已写好临时文件，但换文件没有提交
        }
        return Outcome.FAILED_IO; //没走到任何写盘步骤（saveWithoutId / createTempFile 抛出）
    }

    private static String describe(Attempt a) {
        String phase = a.writeFailed ? "write" : (a.committed != null || a.written) ? "replace" : "before-write";
        StringBuilder sb = new StringBuilder("phase=").append(phase);
        if (a.dat != null) {
            sb.append(" dat=").append(a.dat);
        }
        if (a.tmp != null) {
            //FAILED_REPLACE 时这个临时文件含完整数据，可供管理员手工救援
            sb.append(" tmp=").append(a.tmp);
        }
        if (a.error != null) {
            sb.append(" error=").append(a.error);
        }
        return sb.toString();
    }
}
