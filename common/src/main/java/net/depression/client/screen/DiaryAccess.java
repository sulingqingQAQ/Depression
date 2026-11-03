package net.depression.client.screen;

import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.world.item.ItemStack;

public final class DiaryAccess {
    // 1.21.1: BookViewScreen.BookAccess 已改为 final record，无法继承。
    // 日记本打开时经由此处创建 BookAccess 并记录实例，Mixin 以同一性判定是否为日记界面。
    public static BookViewScreen.BookAccess current;

    private DiaryAccess() {
    }

    public static BookViewScreen.BookAccess fromStack(ItemStack stack) {
        BookViewScreen.BookAccess access = BookViewScreen.BookAccess.fromItem(stack);
        if (access == null) {
            access = BookViewScreen.EMPTY_ACCESS;
        }
        current = access;
        return access;
    }

    public static boolean isDiary(BookViewScreen.BookAccess access) {
        return access != null && access == current;
    }
}
