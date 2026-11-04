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

public class ClientLifecycleEventListener {
    public static void onClientLevelLoad(ClientLevel clientLevel) {
        if (Minecraft.getInstance().level == null) {
            ClientMentalStatus clientMentalStatus = DepressionClient.clientMentalStatus;
            clientMentalStatus.reset();
            ClientPTSDManager ptsdManager = clientMentalStatus.ptsdManager;
            ptsdManager.clear();
            ClientActionbarHint clientActionbarHint = DepressionClient.clientActionbarHint;
            clientActionbarHint.clear();
            ClientDiaryUpdater.clear();
        }
        // 死亡/重生与维度切换都会触发 CLIENT_LEVEL_LOAD（重建 ClientLevel），
        // 无论是否换档都清掉日记待开屏状态，避免旧书在新场景弹出
        ClientDiaryUpdater.clear();
    }

    public static void onClientStopping(Minecraft minecraft) {
        OggStreamPlayer player = DepressionClient.oggStreamPlayer;
        player.stop();
    }
}
