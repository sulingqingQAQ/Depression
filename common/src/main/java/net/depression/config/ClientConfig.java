package net.depression.config;

import com.electronwill.nightconfig.core.file.FileConfig;
import dev.architectury.platform.Platform;
import net.depression.client.ClientMentalStatus;
import net.depression.client.DepressionClient;
import net.depression.client.rhythmcraft.ClientPlayingChart;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.util.Mth;

import java.io.File;
import java.io.IOException;

public class ClientConfig {
    public static void load() {
        File folder = new File(Platform.getConfigFolder() + "/depression");
        if (!folder.exists()) {
            folder.mkdirs();
        }
        //读取client-config.toml
        File file = new File(folder, "client-config.toml");
        if (!file.exists()) {
            try {
                file.createNewFile();
            }
            catch (IOException e) {
                e.printStackTrace();
            }
        }
        FileConfig config = FileConfig.of(file);
        config.load();

        if (!config.contains("emotion_display_offset.x")) {
            config.set("emotion_display_offset.x", -8);
        }
        if (!config.contains("emotion_display_offset.y")) {
            config.set("emotion_display_offset.y", -51);
        }
        if (!config.contains("enable_computer")) {
            config.set("enable_computer", false);
        }
        if (!config.contains("rc_highlight_distance")) {
            config.set("rc_highlight_distance", 32);
        }
        //是否在死亡重生后补弹排队中的日记屏（默认 false：维持原行为，死亡/重生时丢弃待开屏）
        //true 时死亡重生只清收包快照，保留 ClientDiaryUpdater.curDiaryItem 队列，重生后由 renderHud 自然弹出
        if (!config.contains("keep_diary_screen_on_respawn")) {
            config.set("keep_diary_screen_on_respawn", false);
        }

        ClientMentalStatus.EMOTION_DISPLAY_OFFSET_X = config.get("emotion_display_offset.x");
        ClientMentalStatus.EMOTION_DISPLAY_OFFSET_Y = config.get("emotion_display_offset.y");
        DepressionClient.ENABLE_COMPUTER = config.get("enable_computer");
        DepressionClient.KEEP_DIARY_SCREEN_ON_RESPAWN = config.get("keep_diary_screen_on_respawn");

        config.save();
        config.close();
    }
}
