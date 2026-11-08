package net.depression.server;

import com.mojang.authlib.GameProfile;
import dev.architectury.networking.NetworkManager;
import net.depression.Depression;
import net.depression.mental.MentalStatus;
import net.depression.mental.MentalTrait;
import net.depression.network.DiaryUpdatePacket;
import net.depression.rhythmcraft.PlayingChart;
import net.depression.rhythmcraft.RhythmCraftProfile;
import net.depression.world.ParticleFormulaInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.Connection;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WrittenBookContent;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Registry {

    public static final HashMap<UUID, MentalStatus> mentalStatus = new HashMap<>();
    public static final HashMap<UUID, StatManager> statManager = new HashMap<>();
    public static final ConcurrentHashMap<UUID, RhythmCraftProfile> profileMap = new ConcurrentHashMap<>();
    private static final HashMap<UUID, ItemStack> diaryUpdateMap = new HashMap<>();
    private static final ConcurrentHashMap<UUID, ServerLevel> pendingLevels = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, ServerPlayer> pendingPlayers = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, ChunkMap> pendingChunkMaps = new ConcurrentHashMap<>();
    public static final HashMap<UUID, HashSet<UUID>> playerEventMap = new HashMap<>();
    public static final ConcurrentLinkedQueue<Player> addPlayerQueue = new ConcurrentLinkedQueue<>();

    public static HashMap<String, LinkedList<ParticleFormulaInstance>> particles = new HashMap<>();
    public static void init() {
        resetAll();
        PlayingChart.playingCharts.clear();
        PlayingChart.playerData.clear();
    }

    /**
     * 清空全部与玩家绑定的服务端注册表（不含音游的 playingCharts / playerData，那两张表由音游自己收尾，init 里另行保留）。
     * 只在服务器开始前（init）和服务器停止后（SERVER_STOPPED）调用，绝不在保存期间调用。
     */
    public static void resetAll() {
        mentalStatus.clear();
        statManager.clear();
        diaryUpdateMap.clear();
        pendingLevels.clear();
        pendingPlayers.clear();
        pendingChunkMaps.clear();
        playerEventMap.clear();
        addPlayerQueue.clear(); //重开服务器时一并清空待定队列，避免残留的旧 ServerPlayer 引用

        profileMap.clear();
        PlayerSaveLifecycle.reset(); //写盘追踪 / 已清理实体记录 / 缺失告警去重，随服务器生命周期一起重置
    }

    /** 该 UUID 是否还留有持久状态（登录对账时只用来决定要不要打日志） */
    public static boolean hasPersistentState(UUID uuid) {
        return mentalStatus.containsKey(uuid) || statManager.containsKey(uuid) || profileMap.containsKey(uuid);
    }

    /**
     * 清理该玩家的持久状态（心理状态 / 旧统计 / 音游 profile）。幂等，缺失项无动作。
     * 只能在“该玩家的退出保存已经完成之后”（PlayerList.remove RETURN）、登录对账、服务器开始前 / 停止后调用。
     */
    public static void evictPersistent(UUID uuid) {
        mentalStatus.remove(uuid);
        statManager.remove(uuid);
        profileMap.remove(uuid);
    }

    /**
     * 清理该玩家的瞬态数据（首次选特质的 pending、待处理队列、挂起的日记更新）。与写盘结果无关，一律清。
     * player 非空（退出）时按实体身份判断，只清属于这个实体的项，避免误清同 UUID 更新会话的 pending；
     * player 为空（登录对账，新实体还没登记）时按 UUID 清。
     */
    public static void clearTransient(UUID uuid, @Nullable ServerPlayer player) {
        if (player == null || pendingPlayers.get(uuid) == player) {
            pendingPlayers.remove(uuid);
            pendingLevels.remove(uuid);
            pendingChunkMaps.remove(uuid);
        }
        if (player == null) {
            addPlayerQueue.removeIf(queued -> queued.getUUID().equals(uuid));
        }
        else {
            addPlayerQueue.removeIf(queued -> queued == player);
        }
        diaryUpdateMap.remove(uuid);
    }
    public static void eventAddPlayer(ServerBossEvent event, ServerPlayer player) {
        if (!playerEventMap.containsKey(player.getUUID())) {
            playerEventMap.put(player.getUUID(), new HashSet<>());
        }
        playerEventMap.get(player.getUUID()).add(event.getId());
    }

    public static void eventRemovePlayer(ServerBossEvent event, ServerPlayer player) {
        HashSet<UUID> events = playerEventMap.get(player.getUUID());
        if (events != null) {
            events.remove(event.getId());
            if (events.isEmpty()) {
                playerEventMap.remove(player.getUUID());
            }
        }
    }

    public static void addPendingPlayer(ServerLevel serverLevel, ServerPlayer player) {
        pendingLevels.put(player.getUUID(), serverLevel);
        pendingPlayers.put(player.getUUID(), player);
        while (pendingLevels.size() > 100) { // 防止服务器被假人攻击
            pendingLevels.remove(pendingLevels.keySet().iterator().next());
        }
        while (pendingPlayers.size() > 100) {
            pendingPlayers.remove(pendingPlayers.keySet().iterator().next());
        }
    }
    public static void addPendingChunkMap(ServerPlayer player, ChunkMap chunkMap) {
        pendingChunkMaps.put(player.getUUID(), chunkMap);
        while (pendingChunkMaps.size() > 100) {
            pendingChunkMaps.remove(pendingChunkMaps.keySet().iterator().next());
        }
    }
    public static boolean isPending(Player player) {
        return pendingPlayers.containsKey(player.getUUID());
    }
    public static void receiveMentalTraitPacket(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext packetContext) {
        String id = buf.readCharSequence(buf.readableBytes(), DiaryUpdatePacket.charset).toString();
        Player player = packetContext.getPlayer();
        UUID uuid = player.getUUID();
        MentalStatus mentalStatus = Registry.mentalStatus.get(uuid);
        if (mentalStatus == null) {
            //玩家已退出并被清理后才到达的迟到选择包：没有可写入的状态，直接忽略（不能写进新会话 / 复活旧对象）
            Depression.LOGGER.debug("[Depression/Save] ignore late mental trait packet from {} (no mental status)", uuid);
            return;
        }
        mentalStatus.loadMentalTrait(MentalTrait.byId(id));
        addPlayerQueue.add(player);
    }
    public static void loadPendingPlayers() {
        //逐个消费队列：处理完的玩家立刻出队。原来是每 tick 遍历整张队列且从不 poll，
        //已处理过的玩家会永久留在队列里，既让每 tick 都重复遍历一遍，又让 ServerPlayer 引用无法回收
        Player player;
        while ((player = addPlayerQueue.poll()) != null) {
            UUID uuid = player.getUUID();
            //身份与在线校验：队列里的实体必须就是当前 pending 的那个实体，且仍在线。
            //否则（断线后迟到的选择包 / 同 UUID 已换成新会话）一律丢弃，不能把已移除的旧 ServerPlayer 重新塞进世界。
            if (!(player instanceof ServerPlayer queued)
                    || pendingPlayers.get(uuid) != queued
                    || queued.server.getPlayerList().getPlayer(uuid) != queued) {
                Depression.LOGGER.debug("[Depression/Save] drop stale pending-trait entry for {}", uuid);
                continue;
            }
            if (pendingChunkMaps.containsKey(uuid)) {
                //待定期间挂起的区块跟踪请求，先补做一次
                ChunkMap chunkMap = pendingChunkMaps.remove(uuid);
                ServerPlayer pendingPlayer = pendingPlayers.get(uuid);
                if (chunkMap != null && pendingPlayer != null) {
                    chunkMap.move(pendingPlayer);
                }
            }
            if (isPending(player)) {
                ServerLevel serverLevel = pendingLevels.get(uuid);
                ServerPlayer pendingPlayer = pendingPlayers.get(uuid);
                if (serverLevel != null && pendingPlayer != null) {
                    //此时 pendingPlayers 仍保留该项，ServerLevelMixin 会因 isPending 提前返回，从而继续执行原版 addNewPlayer
                    serverLevel.addNewPlayer(pendingPlayer);
                }
                pendingPlayers.remove(uuid);
                pendingLevels.remove(uuid);
            }
        }
    }
    public static void diaryUpdate(ServerPlayer player, ItemStack diary) {
        diaryUpdateMap.put(player.getUUID(), diary);
        DiaryUpdatePacket.sendToPlayer(player);
    }
    public static void removeDiaryUpdate(UUID uuid) { //M4: 退服时清除挂起的日记更新条目, 防止陈旧 ItemStack 引用滞留并在重进后误用
        diaryUpdateMap.remove(uuid);
    }
    public static void receiveDiaryUpdatePacket(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext packetContext) {
        ItemStack diary = diaryUpdateMap.get(packetContext.getPlayer().getUUID());
        if (diary == null) {
            //Duplicate/late/forged packet: the pending entry was already consumed by an earlier packet (removed at the end of this method)
            net.depression.Depression.LOGGER.warn("Received diary update packet from " + packetContext.getPlayer().getName().getString() + " but no pending diary item exists, ignoring");
            return;
        }
        CharSequence rawContent = buf.readCharSequence(buf.readableBytes(), DiaryUpdatePacket.charset);
        java.util.List<Filterable<Component>> pages = new java.util.ArrayList<>();
        StringBuilder pageContent = new StringBuilder();
        for (int i = 0; i < rawContent.length(); ++i) {
            char c = rawContent.charAt(i);
            if (c == '/') {
                pages.add(Filterable.passThrough(Component.literal(pageContent.toString())));
                pageContent = new StringBuilder();
            }
            else {
                pageContent.append(c);
            }
        }
        WrittenBookContent oldContent = diary.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (oldContent != null) {
            pages.addAll(oldContent.pages());
        }
        while (pages.size() > 100) {
            pages.remove(pages.size()-1);
        }
        String title = oldContent != null ? oldContent.title().raw() : "Diary";
        String author = oldContent != null ? oldContent.author() : packetContext.getPlayer().getName().getString();
        int generation = oldContent != null ? oldContent.generation() : 0;
        diary.set(DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(Filterable.passThrough(title), author, generation, pages, false));
        diaryUpdateMap.remove(packetContext.getPlayer().getUUID());
    }
}
