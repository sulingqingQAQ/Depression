package net.depression.network;

import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.depression.Depression;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class ActionbarHintPacket {
    public static final ResourceLocation PTSD_FROM_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "ptsd_form_packet");
    public static final ResourceLocation PTSD_DISPERSE_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "ptsd_disperse_packet");
    public static final ResourceLocation PTSD_REMISSION_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "ptsd_remission_packet");
    public static final ResourceLocation INSOMNIA_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "insomnia_packet");
    public static final ResourceLocation MENTAL_FATIGUE_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "mental_fatigue_packet");
    public static final ResourceLocation NEARBY_BLOCK_HEAL_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "nearby_block_heal_packet");
    public static final ResourceLocation KILL_ENTITY_HEAL_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "kill_entity_heal_packet");
    public static final ResourceLocation BREAK_BLOCK_HEAL_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "break_block_heal_packet");
    public static final ResourceLocation FISH_HEAL_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "fish_heal_packet");
    public static final ResourceLocation FEED_ANIMAL_HEAL_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "feed_animal_heal_packet");
    public static final ResourceLocation PET_HEAL_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "pet_heal_packet");
    public static final ResourceLocation LOOT_HEAL_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "loot_heal_packet");
    public static final ResourceLocation BIPOLAR_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "bipolar_packet");
    public static final ResourceLocation OVERDOSE_PACKET = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "overdose_packet");

    public static final Charset CHARSET = StandardCharsets.UTF_8;

    public static void sendOverdosePacket(ServerPlayer player, int count) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeInt(count);
        NetworkManager.sendToPlayer(player, OVERDOSE_PACKET, buf);
    }
    public static void sendBipolarPacket(ServerPlayer player, boolean isMania) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeBoolean(isMania);
        NetworkManager.sendToPlayer(player, BIPOLAR_PACKET, buf);
    }
    public static void sendFishHealPacket(ServerPlayer player, Component id) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeJsonWithCodec(ComponentSerialization.CODEC, id);
        NetworkManager.sendToPlayer(player, FISH_HEAL_PACKET, buf);
    }
    public static void sendFeedAnimalHealPacket(ServerPlayer player, Component id) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeJsonWithCodec(ComponentSerialization.CODEC, id);
        NetworkManager.sendToPlayer(player, FEED_ANIMAL_HEAL_PACKET, buf);
    }
    public static void sendPetHealPacket(ServerPlayer player, Component id) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeJsonWithCodec(ComponentSerialization.CODEC, id);
        NetworkManager.sendToPlayer(player, PET_HEAL_PACKET, buf);
    }
    public static void sendLootHealPacket(ServerPlayer player) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        NetworkManager.sendToPlayer(player, LOOT_HEAL_PACKET, buf);
    }
    public static void sendNearbyBlockHealPacket(ServerPlayer player, Component id) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeJsonWithCodec(ComponentSerialization.CODEC, id);
        NetworkManager.sendToPlayer(player, NEARBY_BLOCK_HEAL_PACKET, buf);
    }
    public static void sendKillEntityHealPacket(ServerPlayer player, Component id) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeJsonWithCodec(ComponentSerialization.CODEC, id);
        NetworkManager.sendToPlayer(player, KILL_ENTITY_HEAL_PACKET, buf);
    }
    public static void sendBreakBlockHealPacket(ServerPlayer player, Component id) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeJsonWithCodec(ComponentSerialization.CODEC, id);
        NetworkManager.sendToPlayer(player, BREAK_BLOCK_HEAL_PACKET, buf);
    }
    public static void sendPTSDFormPacket(ServerPlayer player, Component id) {
        RegistryFriendlyByteBuf formBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        formBuf.writeJsonWithCodec(ComponentSerialization.CODEC, id);
        NetworkManager.sendToPlayer(player, PTSD_FROM_PACKET, formBuf);
    }
    public static void sendPTSDDispersePacket(ServerPlayer player, Component id) {
        RegistryFriendlyByteBuf disperseBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        disperseBuf.writeJsonWithCodec(ComponentSerialization.CODEC, id);
        NetworkManager.sendToPlayer(player, PTSD_DISPERSE_PACKET, disperseBuf);
    }
    public static void sendPTSDRemissionPacket(ServerPlayer player, Component id) {
        RegistryFriendlyByteBuf remissionBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        remissionBuf.writeJsonWithCodec(ComponentSerialization.CODEC, id);
        NetworkManager.sendToPlayer(player, PTSD_REMISSION_PACKET, remissionBuf);
    }
    public static void sendInsomniaPacket(ServerPlayer player) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        NetworkManager.sendToPlayer(player, INSOMNIA_PACKET, buf);
    }
    public static void sendMentalFatiguePacket(ServerPlayer player) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        NetworkManager.sendToPlayer(player, MENTAL_FATIGUE_PACKET, buf);
    }
}
