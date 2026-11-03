package net.depression.network;

import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.depression.Depression;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;

public class MentalTraitPacket {
    public static final ResourceLocation MENTAL_TRAIT_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "mental_trait_packet");
    public static void sendToPlayer(ServerPlayer player) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        NetworkManager.sendToPlayer(player, MENTAL_TRAIT_PACKET, buf);
    }

    public static void sendToServer(String id) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeCharSequence(id, StandardCharsets.UTF_8);
        NetworkManager.sendToServer(MENTAL_TRAIT_PACKET, buf);
    }
}
