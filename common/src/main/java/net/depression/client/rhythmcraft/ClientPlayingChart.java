package net.depression.client.rhythmcraft;

import dev.architectury.networking.NetworkManager;
import net.depression.Depression;
import net.depression.client.DepressionClient;
import net.depression.mixin.rhythmcraft.EntityAccessor;
import net.depression.network.RhythmCraftPacket;
import net.depression.rhythmcraft.Chart;
import net.depression.rhythmcraft.Song;
import net.depression.screen.OffsetButton;
import net.depression.screen.PosOrigin;
import net.depression.screen.rhythmcraft.GameEndScreen;
import net.depression.screen.rhythmcraft.RCLoadingScreen;
import net.depression.screen.rhythmcraft.RCSelectionScreen;
import net.depression.screen.rhythmcraft.SongProgressSlider;
import net.depression.util.OggStreamPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.level.ChunkPos;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ClientPlayingChart {
    public static final ResourceLocation PAUSE = ResourceLocation.fromNamespaceAndPath("depression", "textures/rc_screen/modifying/pause.png");
    public static final ResourceLocation PLAYING = ResourceLocation.fromNamespaceAndPath("depression", "textures/rc_screen/modifying/playing.png");
    public static final ResourceLocation FORWARD = ResourceLocation.fromNamespaceAndPath("depression", "textures/rc_screen/modifying/forward.png");
    public static final ResourceLocation BACKWARD = ResourceLocation.fromNamespaceAndPath("depression", "textures/rc_screen/modifying/backward.png");
    public SongProgressSlider songProgressSlider;
    public OffsetButton pauseButton, forwardButton, backwardButton;
    public long spaceInTicks = 1;
    public ConcurrentSkipListMap<BlockPos, Shulker> highlightedNotes;
    public boolean isEditMode;
    public Chart chart;
    public Song song;
    public Comparator<? super BlockPos> comparator;
    public boolean isPlaying;
    /** C：谱面是否已结束（收到服务端 GAME_END），客户端据此停止幻影音符渲染并丢弃谱面对象 */
    public boolean isEnded;
    public AtomicInteger score;
    public AtomicInteger combo;
    public BlockPos frontBlockPos;
    public BlockPos backBlockPos;
    public LerpingBossEvent progressBar;
    public boolean isTimeFreeze;

    public ArrayList<BossEvent.BossBarColor> colors = new ArrayList<>(Arrays.asList(
            BossEvent.BossBarColor.WHITE,
            BossEvent.BossBarColor.GREEN,
            BossEvent.BossBarColor.YELLOW,
            BossEvent.BossBarColor.RED,
            BossEvent.BossBarColor.PINK,
            BossEvent.BossBarColor.PURPLE
    ));

    public ArrayList<String> difficultyNames = new ArrayList<>(Arrays.asList(
            "Default",
            "Easy",
            "Normal",
            "Hard",
            "Insane",
            "Extreme"
    ));

    public ClientPlayingChart(Song song, int difficulty, boolean isEditMode) {
        this.isEditMode = isEditMode;
        this.song = song;
        this.chart = song.charts.get(difficulty);
        this.comparator = chart.notes.comparator();
        score = new AtomicInteger(0);
        combo = new AtomicInteger(0);
        highlightedNotes = new ConcurrentSkipListMap<>(comparator);

        if (!isEditMode) {
            progressBar = new LerpingBossEvent(Mth.createInsecureUUID(), Component.literal(song.name + " - " + difficultyNames.get(difficulty) + " Lv." + chart.level), 0f,
                    colors.get(chart.difficulty), BossEvent.BossBarOverlay.PROGRESS, false, false, false);
        }
    }


    public void onChunkLoad(ChunkPos chunkPos) {
        BlockPos min = chart.getMinBoundary(chunkPos);
        BlockPos max = chart.getMaxBoundary(chunkPos);
        for (BlockPos note : chart.notes.subSet(min, false, max, false)) {
            renderNote(note);
        }
    }

    public void onChunkUnload(ChunkPos chunkPos) {
        BlockPos min = chart.getMinBoundary(chunkPos);
        BlockPos max = chart.getMaxBoundary(chunkPos);
        for (BlockPos note : chart.notes.subSet(min, true, max, true)) {
            Shulker shulker = highlightedNotes.remove(note);
            if (shulker != null) {
                shulker.setRemoved(Entity.RemovalReason.DISCARDED);
            }
        }
    }

    public void renderNote(BlockPos pos) {
        if (highlightedNotes.containsKey(pos)) {
            return;
        }
        EntityType.byString("minecraft:shulker").ifPresent(entityType -> {
            Shulker shulker = new Shulker((EntityType<? extends Shulker>) entityType, Minecraft.getInstance().level);
            shulker.moveTo(pos, 0, 0);
            shulker.setInvisible(true);
            ((EntityAccessor) shulker).invokeSetSharedFlag(6, true);
            highlightedNotes.put(pos, shulker);
        });
    }

    public static void receivePlaySongPacket(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext packetContext) {
        ClientPlayingChart playingChart = DepressionClient.playingChart;
        if (playingChart == null) { //服务端已收尾（SESSION_END）后迟到的包
            return;
        }
        playingChart.isPlaying = true;
        try {
            if (playingChart.isEditMode) {
                playingChart.songProgressSlider = new SongProgressSlider(0.1, 0, 0.8, DepressionClient.oggStreamPlayer);
                playingChart.backwardButton = new OffsetButton(PosOrigin.MID, -32, PosOrigin.ZERO, 24, 20, 20, 1, () -> BACKWARD, true,
                        button -> {
                            OggStreamPlayer oggStreamPlayer = DepressionClient.oggStreamPlayer;
                            long newTime = Math.round(oggStreamPlayer.getElapsedTimeInSeconds() * 20) - playingChart.spaceInTicks;
                            newTime = Math.max(0, newTime);
                            oggStreamPlayer.seek(newTime);
                            RhythmCraftPacket.sendProgressChange(newTime);
                },
                        (button, guiGraphics) -> {
                            Font font = Minecraft.getInstance().font;
                            String text = "-" + playingChart.spaceInTicks + " ticks";
                            guiGraphics.drawString(font, text, button.getX() - font.width(text), button.getY(), RCSelectionScreen.white);
                        });
                playingChart.forwardButton = new OffsetButton(PosOrigin.MID, 12, PosOrigin.ZERO, 24, 20, 20, 1, () -> FORWARD, true,
                        button -> {
                            OggStreamPlayer oggStreamPlayer = DepressionClient.oggStreamPlayer;
                            if (!oggStreamPlayer.isForwarding) {
                                long newTime = Math.round(oggStreamPlayer.getElapsedTimeInSeconds() * 20) + playingChart.spaceInTicks;
                                newTime = Math.min((long) (oggStreamPlayer.getDurationInSeconds() * 20d), newTime);
                                oggStreamPlayer.forward(playingChart.spaceInTicks);
                                RhythmCraftPacket.sendProgressChange(newTime);
                            }
                },
                        (button, guiGraphics) -> {
                            guiGraphics.drawString(Minecraft.getInstance().font, "+" + playingChart.spaceInTicks + " ticks", button.getX() + 20, button.getY(), RCSelectionScreen.white);
                        });
                playingChart.pauseButton = new OffsetButton(PosOrigin.MID, -10, PosOrigin.ZERO, 24, 20, 20, 1, () -> DepressionClient.oggStreamPlayer.isPaused ? PAUSE : PLAYING, true,
                        button -> {
                            OggStreamPlayer oggStreamPlayer = DepressionClient.oggStreamPlayer;
                            long newTime = Math.round(oggStreamPlayer.getElapsedTimeInSeconds() * 20);

                            //Depression.LOGGER.info("newTime: " + newTime);

                            RhythmCraftPacket.sendPauseChange(newTime);
                            if (oggStreamPlayer.isPaused) {
                                oggStreamPlayer.isSpacePaused = false;
                                oggStreamPlayer.resume();
                                oggStreamPlayer.seek(newTime);
                            }
                            else {
                                oggStreamPlayer.isSpacePaused = true;
                                oggStreamPlayer.pause();
                                oggStreamPlayer.seek(newTime);
                            }
                });
            }
        }
        catch (UnsupportedAudioFileException | IOException e) {
            throw new RuntimeException(e);
        }
        DepressionClient.oggStreamPlayer.play();
    }
    public static void receiveNoteChangePacket(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext packetContext) {
        BlockPos pos = buf.readBlockPos();
        boolean isAdd = buf.readBoolean();
        ClientPlayingChart playingChart = DepressionClient.playingChart;
        if (playingChart == null) { //收尾后迟到的包（M35）
            return;
        }
        if (isAdd) {
            playingChart.renderNote(pos);
        }
        else {
            Shulker shulker = playingChart.highlightedNotes.remove(pos);
            if (shulker != null) {
                shulker.setRemoved(Entity.RemovalReason.DISCARDED);
            }
        }
    }
    public static void receiveGameplayChangePacket(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext packetContext) {
        ClientPlayingChart playingChart = DepressionClient.playingChart;
        if (playingChart == null) {
            return;
        }
        playingChart.score.set(buf.readInt());
        playingChart.combo.set(buf.readInt());
    }
    public static void receiveTimeChangePacket(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext packetContext) {
        ClientPlayingChart playingChart = DepressionClient.playingChart;
        long time = buf.readLong();
        if (playingChart == null) {
            return;
        }
        Minecraft.getInstance().execute(() -> {
            if (DepressionClient.playingChart != playingChart) { //排队期间已被收尾
                return;
            }
            playingChart.isTimeFreeze = false;
            if (Minecraft.getInstance().level != null) {
                Minecraft.getInstance().level.setDayTime(time);
            }
            playingChart.isTimeFreeze = true;
        });
    }
    public static void receiveGameEndPacket(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext packetContext) {
        ClientPlayingChart playingChart = DepressionClient.playingChart;
        if (playingChart == null) {
            return;
        }
        playingChart.isPlaying = false;
        playingChart.isEnded = true; //C：与服务端结束同步，客户端 tick 看到后丢弃谱面对象
        int score = buf.readInt();
        int hits = buf.readInt();
        int prevScore = buf.readInt();
        String id = playingChart.song.id;
        int difficulty = playingChart.chart.difficulty;
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
           minecraft.setScreen(new GameEndScreen(playingChart, score, hits, prevScore));
        });
        DepressionClient.rcProfile.newScore(id, difficulty, score);
    }

    public static void receiveSpaceChangePacket(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext packetContext) {
        ClientPlayingChart playingChart = DepressionClient.playingChart;
        int space = buf.readInt();
        if (playingChart == null) {
            return;
        }
        playingChart.spaceInTicks = space;
    }

    /**
     * 服务端收尾通知（§4.8）：这一局已经（或已经无法）收尾，本地局对象必须丢弃。
     * RESTORE_FAILED 额外给一行聊天提示（rt69-03：服务端在恢复失败当刻就发，客户端不能停在加载 / 结算屏）。
     */
    public static void receiveSessionEndPacket(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext packetContext) {
        int reasonCode = buf.readInt();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            abortLocal();
            if (reasonCode == RhythmCraftPacket.SESSION_END_REASON_RESTORE_FAILED && minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatableWithFallback("message.depression.rc_restore_failed",
                                "Rhythm chart could not be restored; returning to your world"), false);
            }
        });
    }

    /**
     * 客户端本地摘局：移除全部高亮 Shulker、丢弃 playingChart、停 Ogg、关掉加载 / 结算屏。
     * 幂等；只在客户端主线程调用（收包路径统一先 {@code Minecraft.execute}）。
     */
    public static void abortLocal() {
        ClientPlayingChart playingChart = DepressionClient.playingChart;
        if (playingChart != null && playingChart.highlightedNotes != null) {
            for (Shulker shulker : playingChart.highlightedNotes.values()) {
                if (shulker != null) {
                    shulker.setRemoved(Entity.RemovalReason.DISCARDED);
                }
            }
            playingChart.highlightedNotes.clear();
        }
        DepressionClient.playingChart = null;
        try {
            DepressionClient.oggStreamPlayer.stop();
        } catch (Exception e) {
            Depression.LOGGER.debug("Failed to stop ogg player while aborting rhythm chart", e);
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof RCLoadingScreen || minecraft.screen instanceof GameEndScreen) {
            minecraft.setScreen(null);
        }
    }
}
