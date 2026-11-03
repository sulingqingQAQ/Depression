package net.depression.item;

import net.depression.client.ClientActionbarHint;
import net.depression.client.ClientDiaryUpdater;
import net.depression.server.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.WrittenBookItem;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

public class DiaryItem extends WrittenBookItem {

    public DiaryItem(Properties properties) {
        super(properties);
    }

    private static long getLastWriteTime(ItemStack stack) {
        CustomData custom = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        if (custom.contains("last_write_time")) {
            return custom.copyTag().getLong("last_write_time");
        }
        return -1L;
    }

    private static void setLastWriteTime(ItemStack stack, long time) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putLong("last_write_time", time));
    }

    private static void ensureBookContent(ItemStack stack, Player player) {
        WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) {
            WrittenBookContent created = new WrittenBookContent(
                    Filterable.passThrough(Component.translatable("item.depression.diary").getString()),
                    player.getName().getString(),
                    0,
                    new ArrayList<>(),
                    false);
            stack.set(DataComponents.WRITTEN_BOOK_CONTENT, created);
        }
    }

    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand interactionHand) {
        ItemStack itemStack = player.getItemInHand(interactionHand);
        long lastWrite = getLastWriteTime(itemStack);
        long todayTime = level.getDayTime() % 24000L;
        if (lastWrite < 0) { //如果这本日记没有写过
            if (todayTime >= 12544L) { //如果已经到了晚上，则为日记加上Tag并更新日记
                ensureBookContent(itemStack, player);
                setLastWriteTime(itemStack, level.getDayTime());
                if (!level.isClientSide()) {
                    Registry.diaryUpdate((ServerPlayer) player, itemStack);
                }
                else {
                    ClientDiaryUpdater.setInfo(this, level, player, interactionHand);
                }
            }
            else { //如果还没到晚上，则发送提示
                if (level.isClientSide()) {
                    ClientActionbarHint.displayTranslatable(ClientActionbarHint.diaryUnwrittenHint);
                }
            }
        }
        else { //如果这本日记写过
            if (todayTime >= 12544L && level.getDayTime() - lastWrite >= 12544) { //如果已经到了晚上且距离上次写的时间 >= 12544 tick，则为日记加上Tag并更新日记
                setLastWriteTime(itemStack, level.getDayTime());
                if (!level.isClientSide()) {
                    Registry.diaryUpdate((ServerPlayer) player, itemStack);
                }
                else {
                    ClientDiaryUpdater.setInfo(this, level, player, interactionHand);
                }
            }
            else { //如果还没到晚上，则打开日记
                if (level.isClientSide()) {
                    return super.use(level, player, interactionHand);
                }
            }
        }
        return InteractionResultHolder.pass(itemStack);
    }
    @Override
    public boolean isFoil(ItemStack itemStack) {
        return itemStack.isEnchanted();
    }
}
