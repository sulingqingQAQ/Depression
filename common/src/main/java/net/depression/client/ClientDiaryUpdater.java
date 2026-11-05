package net.depression.client;


import dev.architectury.networking.NetworkManager;
import net.depression.item.DiaryItem;
import net.depression.item.ModItems;
import net.depression.mixin.client.FontAccess;
import net.depression.network.DiaryUpdatePacket;
import net.depression.util.Tools;
import net.minecraft.client.Minecraft;
import net.minecraft.client.StringSplitter;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.Filterable;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.Level;

import java.text.SimpleDateFormat;
import java.util.Random;


public class ClientDiaryUpdater {
    public static DiaryItem diaryItem;
    public static Level level;
    public static Player player;
    public static InteractionHand interactionHand;
    public static ItemStack curDiaryItem;
    public static ItemStack writeStack;
    public static int writeSlot;
    private static final long WRITE_TIMEOUT_NANOS = 5_000_000_000L;
    private static long writeStartedAtNanos;
    public static Random random = new Random();
    public static final ResourceLocation writeSound = ResourceLocation.parse("assets/diary_sound/write_diary");

    public static void clear() {
        diaryItem = null;
        level = null;
        player = null;
        interactionHand = null;
        curDiaryItem = null;
        writeStack = null;
        writeStartedAtNanos = 0L;
    }

    /**
     * 死亡重生专用：只保留排队中的日记弹屏（curDiaryItem），其余收包/写入快照照常清空。
     * 不能整体跳过 clear()——diaryItem/level/player/interactionHand/writeStack/writeSlot 描述的是"这一次右键写日记"的会话，
     * 重生后旧的世界引用与手/槽位快照都已失效，留着会让迟到的 DiaryUpdatePacket 用旧快照做校验、甚至写进已不在手里的书。
     */
    public static void clearKeepingQueuedDiary() {
        ItemStack queuedDiaryItem = curDiaryItem;
        clear();
        curDiaryItem = queuedDiaryItem;
    }

    public static void setInfo(DiaryItem diaryItem, Level level, Player player, InteractionHand interactionHand, ItemStack diaryStack) {
        long now = System.nanoTime();
        //A single diary write may have only one in-flight server response; ignore repeated use events.
        if (ClientDiaryUpdater.diaryItem != null && now - writeStartedAtNanos < WRITE_TIMEOUT_NANOS) {
            return;
        }
        ClientDiaryUpdater.diaryItem = diaryItem;
        ClientDiaryUpdater.level = level;
        ClientDiaryUpdater.player = player;
        ClientDiaryUpdater.interactionHand = interactionHand;
        ClientDiaryUpdater.writeStack = diaryStack; //快照右键时的日记栈：引用作收包快速路径，item 类型作校验参照
        ClientDiaryUpdater.writeSlot = interactionHand == InteractionHand.MAIN_HAND ? player.getInventory().selected : Inventory.SLOT_OFFHAND; //主手记选定槽位（防切格）；副手记固定槽位40（手即槽位）
        writeStartedAtNanos = now;
    }

    public static void receiveDiaryUpdatePacket(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext packetContext) {
        if (ClientDiaryUpdater.diaryItem == null || Minecraft.getInstance().level == null) {
            return;
        }
        player = Minecraft.getInstance().player;
        level.playSeededSound(player, player.getX(), player.getY(), player.getZ(), SoundEvents.VILLAGER_WORK_CARTOGRAPHER, SoundSource.PLAYERS, 1.0F, 1.0F, random.nextLong());
        //解析文本
        CharSequence rawContent = buf.readCharSequence(buf.readableBytes(), DiaryUpdatePacket.charset);
        StringBuilder content;
        boolean isMDD = false;
        if (DepressionClient.clientMentalStatus.mentalHealthId != 3) {
            SimpleDateFormat dateFormat = new SimpleDateFormat(Component.translatable("diary.depression.date_format").getString());
            content = new StringBuilder(dateFormat.format(Tools.getGameDate(Minecraft.getInstance().level.getDayTime())) + "\n");
        }
        else {
            isMDD = true;
            content = new StringBuilder();
        }
        boolean isTranslatable = false;
        String key = "";
        for (int i = 0; i < rawContent.length(); ++i) {
            char c = rawContent.charAt(i);
            if (isTranslatable) { //如果正在扫描可翻译文本
                if (c == '\'') { //如果遇到了结束符则结束可翻译模式，将翻译文本加入content
                    content.append(Component.translatable(key).getString());
                    isTranslatable = false;
                }
                else { //否则继续扫描
                    key += c;
                }
            }
            else { //如果不在扫描可翻译文本
                if (c == '\'') { //如果遇到了开始符则开始翻译模式
                    isTranslatable = true;
                    key = "";
                }
                else { //否则直接加入content
                    content.append(c);
                }
            }
        }
        //将content分页
        String text;
        if (isMDD) { //如果是重度，需要加入日期
            text = Tools.textDateFormat(content.toString(), Minecraft.getInstance().level.getDayTime());
        }
        else {
            text = content.toString();
        }
        //读取空格数量
        int spaceCount = 0;
        for (char c : text.toCharArray()) {
            if (c == ' ') {
                ++spaceCount;
            }
        }
        boolean isLatin = Component.translatable("diary.depression.is_latin").getString().equals("true");

        //收包校验：引用一致直接放行；引用不同（常见为服务端 SetSlot 重同步重建实例）降级走槽位+类型校验，不符才丢弃（不写、不合并、不回发）
        if (interactionHand == null || writeStack == null) { //clear 后的迟到包：快照已清，丢弃（interactionHand 即 setInfo 时的手，非空即手对得上）
            return;
        }
        ItemStack itemStack = player.getItemInHand(interactionHand);
        if (itemStack != writeStack) { //引用不等：不单独据此丢弃（防 SetSlot 换实例误杀合法包），降级校验
            if (interactionHand == InteractionHand.MAIN_HAND && player.getInventory().selected != writeSlot) { //主手切格：选定槽已不是右键时快照槽（副手手即槽位，无需此判）
                return;
            }
            if (!itemStack.is(ModItems.DIARY.get())) { //该手当前栈已不是日记物品（切手/换成其他物品）
                return;
            }
        }
        java.util.List<Filterable<Component>> newPages = new java.util.ArrayList<>();
        StringBuilder pageBuilder = new StringBuilder();
        StringBuilder sentToServerContent = new StringBuilder();
        StringSplitter splitter = ((FontAccess) Minecraft.getInstance().font).getSplitter();
        int index;
        int line = 0;
        while (!text.isEmpty()) {
            if (isLatin) {
                index = splitter.findLineBreak(text, 114, Style.EMPTY);
            }
            else {
                index = splitter.formattedIndexByWidth(text, 114, Style.EMPTY);
                for (int i = 0; i < index; ++i) { //寻找是否有换行符
                    if (text.charAt(i) == '\n') {
                        index = i + 1;
                        break;
                    }
                }
            }
            if (index < text.length() && text.charAt(index) == '\n') { //如果断的位置正好是换行符
                ++index;
            }
            pageBuilder.append(text, 0, index);
            text = text.substring(index);
            if (++line % 14 == 0) {
                String pageText = pageBuilder.toString();
                newPages.add(Filterable.passThrough(Component.literal(pageText)));
                sentToServerContent.append(pageText);
                sentToServerContent.append('/');
                pageBuilder = new StringBuilder();
            }
        }
        if (line % 14 != 0) { //如果最后一页不满14行
            String pageText = pageBuilder.toString();
            newPages.add(Filterable.passThrough(Component.literal(pageText)));
            sentToServerContent.append(pageText);
            sentToServerContent.append('/');
        }

        //加入新的内容：新页在前，旧页在后
        WrittenBookContent oldContent = itemStack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        java.util.List<Filterable<Component>> merged = new java.util.ArrayList<>(newPages);
        String title = Component.translatable("item.depression.diary").getString();
        String author = player.getName().getString();
        int generation = 0;
        if (oldContent != null) {
            merged.addAll(oldContent.pages());
            title = oldContent.title().raw();
            author = oldContent.author();
            generation = oldContent.generation();
        }
        itemStack.set(DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(Filterable.passThrough(title), author, generation, merged, false));
        //打开日记界面
        curDiaryItem = itemStack;
        //向服务器发送更新
        DiaryUpdatePacket.sendToServer(sentToServerContent.toString());
        ClientDiaryUpdater.diaryItem = null;
        writeStartedAtNanos = 0L;
    }
}
