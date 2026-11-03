package net.depression.network;

import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.depression.Depression;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public class CloseEyePacket {
    public static final ResourceLocation CLOSE_EYE_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "close_eye_packet");
    public static void sendToPlayer(ServerPlayer player) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        NetworkManager.sendToPlayer(player, CLOSE_EYE_PACKET, buf);
    }
}
