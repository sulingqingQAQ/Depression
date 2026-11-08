package net.depression.network;

import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.depression.Depression;
import net.depression.rhythmcraft.PlayingChart;
import net.depression.rhythmcraft.ProfileDataType;
import net.depression.rhythmcraft.RhythmCraftProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class RhythmCraftPacket {
    public static final ResourceLocation READ_CHART_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "rc_read_chart_packet");
    public static final ResourceLocation READY_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "rc_ready_packet");
    public static final ResourceLocation PLAY_SONG_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "rc_play_song_packet");
    public static final ResourceLocation PROFILE_REQUEST_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "rc_profile_request_packet");
    public static final ResourceLocation PROFILE_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "rc_profile_packet");
    //NeoForge 21.1 的 payload 注册表只按 id 判重、不分方向，同一个 id 不能既 C2S 又 S2C，所以客户端→服务端单独用一个 id
    public static final ResourceLocation PROFILE_C2S_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "rc_profile_c2s_packet");
    public static final ResourceLocation ACCEPT_EDIT_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "rc_accept_edit_packet");
    public static final ResourceLocation NOTE_CHANGE_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "rc_note_change_packet");
    public static final ResourceLocation PAUSE_CHANGE_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "rc_pause_packet");
    public static final ResourceLocation PROGRESS_CHANGE_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "rc_progress_change_packet");
    public static final ResourceLocation GAMEPLAY_CHANGE_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "rc_gameplay_change_packet");
    public static final ResourceLocation SPACE_CHANGE_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "rc_space_change_packet");
    public static final ResourceLocation TIME_CHANGE_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "rc_time_change_packet");
    public static final ResourceLocation GAME_END_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "rc_game_end_packet");
    public static final ResourceLocation LOAD_BACK_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "rc_load_back_packet");
    /** 服务端收尾通知（S2C）：客户端据此 abortLocal() 摘掉加载 / 结算屏并丢弃本地局（§4.8） */
    public static final ResourceLocation SESSION_END_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "rc_session_end_packet");
    /**
     * 线格式里 {@code PlayingChart.EndReason.RESTORE_FAILED} 的码值（=12）。客户端只按码判断，
     * 不加载服务端局类；改动枚举顺序时必须同步这里。
     */
    public static final int SESSION_END_REASON_RESTORE_FAILED = 12;

    public static void sendReadChart(String songId, int difficulty, boolean isEditMode) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeInt(songId.length());
        buf.writeCharSequence(songId, StandardCharsets.UTF_8);
        buf.writeInt(difficulty);
        buf.writeBoolean(isEditMode);
        NetworkManager.sendToServer(READ_CHART_PACKET, buf);
    }
    public static void sendReady() {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        NetworkManager.sendToServer(READY_PACKET, buf);
    }
    public static void sendPlaySong(ServerPlayer player, String id) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeCharSequence(id, StandardCharsets.UTF_8);
        NetworkManager.sendToPlayer(player, PLAY_SONG_PACKET, buf);
    }
    public static void sendProfileRequest() {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        NetworkManager.sendToServer(PROFILE_REQUEST_PACKET, buf);
    }
    public static void sendProfileS2C(RhythmCraftProfile profile, ServerPlayer player) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeInt(profile.sortType.name().length());
        buf.writeCharSequence(profile.sortType.name(), StandardCharsets.UTF_8);
        buf.writeInt(profile.difficulty);
        buf.writeInt(profile.index);
        for (String key : profile.chartScores.keySet()) {
            buf.writeInt(key.length());
            buf.writeCharSequence(key, StandardCharsets.UTF_8);
            ArrayList<Integer> scores = profile.chartScores.get(key);
            buf.writeInt(scores.size());
            for (int score : scores) {
                buf.writeInt(score);
            }
        }
        NetworkManager.sendToPlayer(player, PROFILE_PACKET, buf);
    }
    public static void sendProfileUpdateC2S(RhythmCraftProfile profile, ProfileDataType type) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeInt(type.name().length());
        buf.writeCharSequence(type.name(), StandardCharsets.UTF_8);
        switch (type) {
            case SORT_TYPE:
                buf.writeInt(profile.sortType.name().length());
                buf.writeCharSequence(profile.sortType.name(), StandardCharsets.UTF_8);
                break;
            case DIFFICULTY:
                buf.writeInt(profile.difficulty);
                break;
            case INDEX:
                buf.writeInt(profile.index);
                break;
        }
        NetworkManager.sendToServer(PROFILE_C2S_PACKET, buf);
    }
    public static void sendAcceptEdit(ServerPlayer player) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        NetworkManager.sendToPlayer(player, ACCEPT_EDIT_PACKET, buf);
    }

    public static void sendNoteChange(ServerPlayer player, BlockPos pos, boolean isAdd) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeBlockPos(pos);
        buf.writeBoolean(isAdd);
        NetworkManager.sendToPlayer(player, NOTE_CHANGE_PACKET, buf);
    }
    public static void sendPauseChange(long tick) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeLong(tick);
        NetworkManager.sendToServer(PAUSE_CHANGE_PACKET, buf);
    }
    public static void sendProgressChange(long tick) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeLong(tick);
        NetworkManager.sendToServer(PROGRESS_CHANGE_PACKET, buf);
    }
    public static void sendTimeChange(ServerPlayer player, long tick) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeLong(tick);
        NetworkManager.sendToPlayer(player, TIME_CHANGE_PACKET, buf);
    }
    public static void sendGameplayChange(ServerPlayer player, int score, int combo) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeInt(score);
        buf.writeInt(combo);
        NetworkManager.sendToPlayer(player, GAMEPLAY_CHANGE_PACKET, buf);
    }

    public static void sendGameEnd(ServerPlayer player, int score, int hits, int prevBest) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeInt(score);
        buf.writeInt(hits);
        buf.writeInt(prevBest);
        NetworkManager.sendToPlayer(player, GAME_END_PACKET, buf);
    }

    public static void sendLoadBack() {
        NetworkManager.sendToServer(LOAD_BACK_PACKET, new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY));
    }

    /** 收尾通知：告诉客户端这一局已经（或已经无法）收尾，本地局对象应当丢弃。 */
    public static void sendSessionEnd(ServerPlayer player, PlayingChart.EndReason reason) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeInt(reason.code);
        NetworkManager.sendToPlayer(player, SESSION_END_PACKET, buf);
    }

    /**
     * 安全包装（rt69-03）：吞掉一切发送异常、绝不 disconnect、不重试。
     * 用于失败分支（RESTORE_FAILED）——那一行必须在 {@code endSession} 早退之前发出去，
     * 否则客户端会停在 RCLoadingScreen / GameEndScreen 等一个永远不会来的收尾包。
     */
    public static void sendSessionEndQuietly(ServerPlayer player, PlayingChart.EndReason reason) {
        if (player == null) {
            return;
        }
        try {
            sendSessionEnd(player, reason);
        } catch (Exception e) {
            Depression.LOGGER.debug("Failed to send rhythm session end packet to {}", player.getUUID(), e);
        }
    }

    public static void sendSpaceChange(ServerPlayer player, int space) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeInt(space);
        NetworkManager.sendToPlayer(player, SPACE_CHANGE_PACKET, buf);
    }
}
