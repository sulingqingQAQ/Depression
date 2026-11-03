package net.depression.network;

import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.depression.Depression;
import net.depression.mental.MentalStatus;
import net.depression.server.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public class MentalStatusPacket {
    public static final ResourceLocation EMOTION_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "emotion_packet");
    public static final ResourceLocation MENTAL_HEALTH_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "mental_health_packet");
    public static void sendToPlayer(ServerPlayer player, MentalStatus mentalStatus) {
        RegistryFriendlyByteBuf emotionBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        emotionBuf.writeDouble(mentalStatus.emotionValue);
        emotionBuf.writeBoolean(mentalStatus.combatCountdown > 0 || Registry.playerEventMap.containsKey(player.getUUID())); //是否处于战斗状态
        RegistryFriendlyByteBuf healthBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        healthBuf.writeDouble(mentalStatus.mentalHealthValue);
        healthBuf.writeInt(mentalStatus.getMentalHealthId());
        NetworkManager.sendToPlayer(player, EMOTION_PACKET, emotionBuf);
        NetworkManager.sendToPlayer(player, MENTAL_HEALTH_PACKET, healthBuf);
    }

}
