package net.depression.listener.client;

import net.depression.client.*;
import net.depression.client.rhythmcraft.ClientPlayingChart;
import net.depression.mental.MentalStatus;
import net.depression.mental.MentalTrait;
import net.depression.mental.PTSDManager;
import net.depression.network.MentalTraitPacket;
import net.depression.screen.MentalTraitSelectionScreen;
import net.depression.util.OggStreamPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

public class ClientLifecycleEventListener {
    public static void onClientLevelLoad(ClientLevel clientLevel) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean joiningLevel = minecraft.level == null;
        if (joiningLevel) {
            ClientMentalStatus clientMentalStatus = DepressionClient.clientMentalStatus;
            clientMentalStatus.reset();
            ClientPTSDManager ptsdManager = clientMentalStatus.ptsdManager;
            ptsdManager.clear();
            ClientActionbarHint clientActionbarHint = DepressionClient.clientActionbarHint;
            clientActionbarHint.clear();
        }
        // CLIENT_LEVEL_LOAD 只在重建 ClientLevel 时触发：换档、活体换维度和跨维度死亡重生会触发，同维度死亡重生不会。
        // 跨维度死亡重生时本事件先于 CLIENT_PLAYER_RESPAWN，且 Minecraft.player 仍是死亡的旧玩家；
        // 此时跳过日记清理，把清或留统一交给 respawn 事件，避免 keep_diary_screen_on_respawn=true 的队列被提前清掉。
        LocalPlayer oldPlayer = minecraft.player;
        if (joiningLevel || oldPlayer == null || !oldPlayer.isDeadOrDying()) {
            //换档/活体换维度：始终整清日记待开屏状态，避免旧书在新场景弹出
            ClientDiaryUpdater.clear();
        }
    }

    public static void onClientPlayerRespawn(LocalPlayer oldPlayer, LocalPlayer newPlayer) {
        if (oldPlayer.isDeadOrDying() && DepressionClient.KEEP_DIARY_SCREEN_ON_RESPAWN) {
            //死亡重生且开关开启：保留排队日记，writeStack/writeSlot/interactionHand 等收包快照仍照常清空
            ClientDiaryUpdater.clearKeepingQueuedDiary();
        }
        else {
            //死亡重生且开关关闭，或活体 respawn/换维度：整清；同维度路径不依赖 CLIENT_LEVEL_LOAD
            ClientDiaryUpdater.clear();
        }
    }

    public static void onClientStopping(Minecraft minecraft) {
        OggStreamPlayer player = DepressionClient.oggStreamPlayer;
        player.stop();
    }
}
