package net.depression.network;

import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.depression.Depression;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public class PTSDOnsetPacket {
    public static final ResourceLocation PTSD_ONSET_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "ptsd_onset_packet");
    public static final ResourceLocation PHOTISM_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "photism_packet");
    public static void sendToPlayer(ServerPlayer player, int onsetLevel, double distance) {
        RegistryFriendlyByteBuf ptsdBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        ptsdBuf.writeInt(onsetLevel);
        ptsdBuf.writeDouble(distance);
        NetworkManager.sendToPlayer(player, PTSD_ONSET_PACKET, ptsdBuf);
    }

    public static void sendPhotismPacket(ServerPlayer player, String id) {
        RegistryFriendlyByteBuf photismBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        photismBuf.writeUtf(id);
        NetworkManager.sendToPlayer(player, PHOTISM_PACKET, photismBuf);
    }
}
