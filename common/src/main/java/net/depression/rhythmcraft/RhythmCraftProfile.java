package net.depression.rhythmcraft;

import dev.architectury.networking.NetworkManager;
import net.depression.network.RhythmCraftPacket;
import net.depression.server.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class RhythmCraftProfile {
    public HashMap<String, ArrayList<Integer>> chartScores = new HashMap<>();
    public SongSortType sortType = SongSortType.DIFFICULTY;
    public int difficulty = 1;
    public int index = 0;
    public void newScore(String id, int difficulty, int score) {
        if (difficulty < 0) { //负下标无法扩容，直接忽略（正常谱面难度取自 Chart.difficulty，为 0..5）
            return;
        }
        ArrayList<Integer> scores = chartScores.get(id);
        //先确认目标难度已有成绩再比较：同歌低难度结算后 scores 只扩到低难度，直接 get 高难度会越界
        if (scores != null && scores.size() > difficulty && scores.get(difficulty) >= score) {
            return;
        }
        if (scores == null) {
            scores = new ArrayList<>();
            chartScores.put(id, scores);
        }
        while (scores.size() <= difficulty) { //确保数组足够长
            scores.add(0);
        }
        scores.set(difficulty, score);
    }
    public void readNbt(CompoundTag tag) {
        CompoundTag profile = tag.getCompound("rc_profile");

        CompoundTag scores = profile.getCompound("scores");
        for (String key : scores.getAllKeys()) {
            ArrayList<Integer> list = new ArrayList<>();
            for (int i : scores.getIntArray(key)) {
                list.add(i);
            }
            chartScores.put(key, list);
        }
        if (profile.contains("initial_sort_type")) {
            sortType = SongSortType.valueOf(profile.getString("initial_sort_type"));
        }
        if (profile.contains("initial_difficulty")) {
            difficulty = profile.getInt("initial_difficulty");
        }
        if (profile.contains("initial_index")) {
            index = profile.getInt("initial_index");
        }
    }

    public void writeNbt(CompoundTag tag) {
        CompoundTag profile = new CompoundTag();

        CompoundTag scores = new CompoundTag();
        for (Map.Entry<String, ArrayList<Integer>> entry : chartScores.entrySet()) {
            scores.putIntArray(entry.getKey(), entry.getValue());
        }
        profile.put("scores", scores);

        profile.putString("initial_sort_type", sortType.name());
        profile.putInt("initial_difficulty", difficulty);
        profile.putInt("initial_index", index);

        tag.put("rc_profile", profile);
    }

    public static int getRankNumber(Integer score) {
        if (score == 1000000) {
            return 7;
        }
        if (score >= 950000) {
            return 6;
        }
        if (score >= 900000) {
            return 5;
        }
        if (score >= 800000) {
            return 4;
        }
        if (score >= 600000) {
            return 3;
        }
        if (score >= 300000) {
            return 2;
        }
        if (score > 0) {
            return 1;
        }
        return 0;
    }

    public static RhythmCraftProfile getProfileByServerPlayer(Player player) {
        if (!(player instanceof ServerPlayer)) {
            throw new IllegalArgumentException("Player is not a ServerPlayer");
        }
        UUID uuid = player.getUUID();
        RhythmCraftProfile profile = Registry.profileMap.get(uuid);
        if (profile == null) {
            profile = new RhythmCraftProfile();
            Registry.profileMap.put(uuid, profile);
        }
        return profile;
    }
    public static void onReceiveRequest(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext packetContext) {
        ServerPlayer player = (ServerPlayer) packetContext.getPlayer();
        RhythmCraftProfile profile = getProfileByServerPlayer(player);
        RhythmCraftPacket.sendProfileS2C(profile, player);
    }
    public static void onReceiveProfileUpdate(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext packetContext) {
        Player player = packetContext.getPlayer();
        RhythmCraftProfile profile = getProfileByServerPlayer(player);
        int stringSize = buf.readInt();
        ProfileDataType type = ProfileDataType.valueOf(buf.readCharSequence(stringSize, StandardCharsets.UTF_8).toString());
        switch (type) {
            case SORT_TYPE:
                profile.sortType = SongSortType.valueOf(buf.readCharSequence(buf.readableBytes(), StandardCharsets.UTF_8).toString());
                break;
            case DIFFICULTY:
                profile.difficulty = buf.readInt();
                break;
            case INDEX:
                profile.index = buf.readInt();
                break;
            case CHART_SCORE:
                stringSize = buf.readInt();
                String songId = buf.readCharSequence(stringSize, StandardCharsets.UTF_8).toString();
                int difficulty = buf.readInt();
                int score = buf.readInt();
                profile.newScore(songId, difficulty, score);
                break;
        }
    }
}
