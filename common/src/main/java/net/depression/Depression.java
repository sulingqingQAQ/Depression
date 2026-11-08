package net.depression;

import com.mojang.logging.LogUtils;
import dev.architectury.event.events.common.*;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import net.depression.block.ModBlocks;
import net.depression.block.entity.ModBannerPatterns;
import net.depression.config.ServerConfig;
import net.depression.effect.ModEffects;
import net.depression.item.ModCreativeTabs;
import net.depression.item.ModItems;
import net.depression.key.KeyMappings;
import net.depression.listener.*;
import net.depression.mental.PTSDManager;
import net.depression.network.*;
import net.depression.rhythmcraft.PlayingChart;
import net.depression.rhythmcraft.ResourceReader;
import net.depression.rhythmcraft.ResourceWriter;
import net.depression.rhythmcraft.RhythmCraftProfile;
import net.depression.server.Registry;
import net.depression.sound.ModSounds;
import net.depression.util.Tools;
import net.depression.world.VillageAdditions;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import javax.sound.sampled.spi.AudioFileReader;
import java.util.ServiceLoader;

public final class Depression {
    public static final String MOD_ID = "depression";
    public static final String MOD_VERSION = "0.2.3+1.21.1";

    public static final Logger LOGGER = LogUtils.getLogger();

    public static void init() {
        ModBannerPatterns.register();
        ModEffects.register(); //必须先注册效果（因为物品使用了效果），否则物品注册时会报错
        ModBlocks.register();
        ModItems.register();
        ModCreativeTabs.register();
        ModSounds.register(); //必须先注册音效（因为心理医生使用了音效）
        VillageAdditions.register();

        NetworkManager.registerReceiver(NetworkManager.Side.C2S,
                MentalTraitPacket.MENTAL_TRAIT_C2S_PACKET, Registry::receiveMentalTraitPacket);
        NetworkManager.registerReceiver(NetworkManager.Side.C2S,
                DiaryUpdatePacket.DIARY_UPDATE_C2S_PACKET, Registry::receiveDiaryUpdatePacket);
        NetworkManager.registerReceiver(NetworkManager.Side.C2S,
                PlaySoundPacket.PLAY_SOUND_PACKET, PTSDManager::receivePlaySoundPacket);
        NetworkManager.registerReceiver(NetworkManager.Side.C2S,
                RhythmCraftPacket.PROFILE_REQUEST_PACKET, RhythmCraftProfile::onReceiveRequest);
        NetworkManager.registerReceiver(NetworkManager.Side.C2S,
                RhythmCraftPacket.PROFILE_C2S_PACKET, RhythmCraftProfile::onReceiveProfileUpdate);
        NetworkManager.registerReceiver(NetworkManager.Side.C2S,
                RhythmCraftPacket.READ_CHART_PACKET, PlayingChart::onReceiveReadChart);
        NetworkManager.registerReceiver(NetworkManager.Side.C2S,
                RhythmCraftPacket.READY_PACKET, PlayingChart::onReceiveReady);
        NetworkManager.registerReceiver(NetworkManager.Side.C2S,
                RhythmCraftPacket.LOAD_BACK_PACKET, PlayingChart::onReceiveLoadBack);
        NetworkManager.registerReceiver(NetworkManager.Side.C2S,
                RhythmCraftPacket.PAUSE_CHANGE_PACKET, PlayingChart::onReceivePauseChange);
        NetworkManager.registerReceiver(NetworkManager.Side.C2S,
                RhythmCraftPacket.PROGRESS_CHANGE_PACKET, PlayingChart::onReceiveProgressChange);
        //Architectury 13：S2C 在客户端由 DepressionClient 登记接收；专用服务端必须单独登记类型，否则第一次 sendToPlayer 就会空指针
        if (Platform.getEnvironment() == Env.SERVER) {
            for (ResourceLocation id : new ResourceLocation[] {
                    MentalStatusPacket.EMOTION_PACKET, MentalStatusPacket.MENTAL_HEALTH_PACKET,
                    ActionbarHintPacket.OVERDOSE_PACKET, ActionbarHintPacket.BIPOLAR_PACKET,
                    ActionbarHintPacket.NEARBY_BLOCK_HEAL_PACKET, ActionbarHintPacket.BREAK_BLOCK_HEAL_PACKET,
                    ActionbarHintPacket.KILL_ENTITY_HEAL_PACKET, ActionbarHintPacket.FISH_HEAL_PACKET,
                    ActionbarHintPacket.FEED_ANIMAL_HEAL_PACKET, ActionbarHintPacket.PET_HEAL_PACKET,
                    ActionbarHintPacket.LOOT_HEAL_PACKET, ActionbarHintPacket.PTSD_FROM_PACKET,
                    ActionbarHintPacket.PTSD_DISPERSE_PACKET, ActionbarHintPacket.PTSD_REMISSION_PACKET,
                    ActionbarHintPacket.INSOMNIA_PACKET, ActionbarHintPacket.MENTAL_FATIGUE_PACKET,
                    CloseEyePacket.CLOSE_EYE_PACKET, PTSDOnsetPacket.PTSD_ONSET_PACKET,
                    DiaryUpdatePacket.DIARY_UPDATE_PACKET, PTSDOnsetPacket.PHOTISM_PACKET,
                    MentalTraitPacket.MENTAL_TRAIT_PACKET, RhythmCraftPacket.PROFILE_PACKET,
                    RhythmCraftPacket.PLAY_SONG_PACKET, RhythmCraftPacket.ACCEPT_EDIT_PACKET,
                    RhythmCraftPacket.NOTE_CHANGE_PACKET, RhythmCraftPacket.GAMEPLAY_CHANGE_PACKET,
                    RhythmCraftPacket.TIME_CHANGE_PACKET, RhythmCraftPacket.GAME_END_PACKET,
                    RhythmCraftPacket.SPACE_CHANGE_PACKET}) {
                NetworkManager.registerS2CPayloadType(id);
            }
        }

        CommandRegistrationEvent.EVENT.register(CommandRegistrationListener::registerCommands);
        PlayerEvent.SMELT_ITEM.register(PlayerEventListener::onSmeltItem);
        PlayerEvent.ATTACK_ENTITY.register(PlayerEventListener::onAttackEntity);
        PlayerEvent.PLAYER_ADVANCEMENT.register(PlayerEventListener::onPlayerAdvancement);
        BlockEvent.PLACE.register(BlockEventListener::onBlockPlace);
        BlockEvent.BREAK.register(BlockEventListener::onBlockBreak);
        EntityEvent.LIVING_DEATH.register(EntityEventListener::onEntityDeath);
        EntityEvent.LIVING_CHECK_SPAWN.register(EntityEventListener::onEntityCheckSpawn);
        LifecycleEvent.SERVER_BEFORE_START.register(LifeCycleEventListener::onServerStart);
        LifecycleEvent.SERVER_STOPPED.register(LifeCycleEventListener::onServerStopped);
        TickEvent.SERVER_POST.register(TickEventListener::onServerTick);
        TickEvent.SERVER_LEVEL_POST.register(TickEventListener::onServerLevelTick);
        InteractionEvent.RIGHT_CLICK_BLOCK.register(InteractionEventListener::onRightClickBlock);
        ServerConfig.load();
        ResourceWriter.write();
        ResourceReader.read();
        Tools.init();
    }
}
