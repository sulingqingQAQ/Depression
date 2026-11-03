package net.depression.network;

import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.depression.Depression;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;

public class PlaySoundPacket {
    public static final ResourceLocation PLAY_SOUND_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "play_sound_packet");
    public static void sendToServer(String id) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeCharSequence(id, StandardCharsets.UTF_8);
        NetworkManager.sendToServer(PLAY_SOUND_PACKET, buf);
    }

    public static void sendToPLayer(String id, ServerPlayer player) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeCharSequence(id, StandardCharsets.UTF_8);
        NetworkManager.sendToPlayer(player, PLAY_SOUND_PACKET, buf);
    }
}
