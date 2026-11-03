package net.depression.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.depression.Depression;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class MentalTraitButton extends ImageButton {
    public static final ResourceLocation FRAME_LOCATION = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "textures/mental_trait/frame_16.png");
    // 1.21 ImageButton 只剩 WidgetSprites 构造，原版按钮底图改走 sprite（widget/button*），图标自己存
    private static final WidgetSprites BUTTON_SPRITES = new WidgetSprites(
            ResourceLocation.withDefaultNamespace("widget/button"),
            ResourceLocation.withDefaultNamespace("widget/button_disabled"),
            ResourceLocation.withDefaultNamespace("widget/button_highlighted"));
    private static final int ICON_SIZE = 16;
    private final ResourceLocation iconLocation;
    private final MentalTraitSelectionScreen screen;
    private final int baseY;
    private String id;
    private final int height;
    public final int dis;
    public final int disFrame;
    private final int charHalfSize = 4;
    private final int charCenterOffset;
    public MentalTraitButton(MentalTraitSelectionScreen screen, int baseY, String id) {
        super(0, 0, screen.screenHeight - 8, screen.screenHeight / 4, // x  y  长  宽
                // sprites 仅占位（renderWidget 全部自绘，不会用到），沿用 ImageButton 构造
                new WidgetSprites(ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "textures/mental_trait/" + id + ".png"),
                        ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "textures/mental_trait/" + id + ".png")),
                (button) -> ((MentalTraitButton) button).onPressButton(),
                Component.translatable("depression.mental_trait." + id));
        this.iconLocation = ResourceLocation.fromNamespaceAndPath(Depression.MOD_ID, "textures/mental_trait/" + id + ".png");
        this.baseY = baseY;
        this.screen = screen;
        this.width = screen.screenHeight - 8;
        this.height = screen.screenHeight / 4;
        this.dis = (height - 16) / 2;
        this.disFrame = (height - 24) / 2;
        this.id = id;
        this.charCenterOffset = disFrame + 24 + (this.getWidth() - disFrame - 24) / 2;
    }

    public void onPressButton() {
        Minecraft.getInstance().setScreen(new MentalTraitInfoScreen(screen, id, this.iconLocation));
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int i, int j, float f) {
        int screenY = (baseY - screen.getSliderValue()); // 计算按钮在屏幕上的位置
        if (screenY + height > 0 && screenY < screen.screenHeight) { // 只渲染在屏幕内的按钮
            setX(screen.baseX);
            setY(screen.baseY + screenY);
            guiGraphics.setColor(1.0F, 1.0F, 1.0F, this.alpha);
            RenderSystem.enableBlend();
            RenderSystem.enableDepthTest();
            if (screenY < 0) { //如果按钮被上面遮挡
                if (height + screenY > 1) {  //是否要画背景 ==2画的时候会崩溃
                    renderBackgroundSprite(guiGraphics, screen.baseY, -screenY, height + screenY);
                }

                if (screenY + disFrame + 24 > 0) { //是否要画框
                    int frameStart = screenY + disFrame < 0 ? -screenY - disFrame : 0;
                    renderTexture(guiGraphics, FRAME_LOCATION, this.getX() + disFrame, Math.max(this.getY() + disFrame, screen.baseY), 0,
                            frameStart, 24, 24 - frameStart, 24, 24);

                    if (screenY + dis + 16 > 0) {  //是否要画图像
                        int textureStart = screenY + dis < 0 ? -screenY - dis : 0;
                        renderTexture(guiGraphics, this.iconLocation, this.getX() + dis, Math.max(this.getY() + dis, screen.baseY), 0,
                                textureStart, ICON_SIZE, ICON_SIZE - textureStart, ICON_SIZE, ICON_SIZE);

                        if (screenY + (height/2) + charHalfSize > 0) { //是否要画文字
                            guiGraphics.drawCenteredString(Minecraft.getInstance().font, Component.translatable("depression.mental_trait." + id), this.getX() + charCenterOffset, this.getY() + (height / 2) - charHalfSize, 0xFFFFFF);
                        }
                    }
                }
            }
            else if (screenY + height > screen.screenHeight) { //如果按钮被下面遮挡
                if (screen.screenHeight - screenY > 1) { //是否要画背景 ==2画的时候会崩溃
                    renderBackgroundSprite(guiGraphics, screen.baseY + screenY, 0, screen.screenHeight - screenY);
                }

                if (screenY + disFrame < screen.screenHeight) { //是否要画框
                    renderTexture(guiGraphics, FRAME_LOCATION, this.getX() + disFrame, this.getY() + disFrame, 0,
                            0, 24, Math.min(24, screen.screenHeight - screenY - disFrame), 24, 24);

                    if (screenY + dis < screen.screenHeight) {  //是否要画图像
                        renderTexture(guiGraphics, this.iconLocation, this.getX() + 8, this.getY() + 8, 0,
                                0, ICON_SIZE, Math.min(ICON_SIZE, screen.screenHeight - screenY - dis), ICON_SIZE, ICON_SIZE);

                        if (screenY + (height/2) - charHalfSize < screen.screenHeight) { //是否要画文字
                            guiGraphics.drawCenteredString(Minecraft.getInstance().font, Component.translatable("depression.mental_trait." + id), this.getX() + charCenterOffset, this.getY() + (height / 2) - charHalfSize, 0xFFFFFF);
                        }
                    }
                }
            }
            else {
                renderBackgroundSprite(guiGraphics, screen.baseY + screenY, 0, height);
                renderTexture(guiGraphics, FRAME_LOCATION, this.getX() + disFrame, this.getY() + disFrame, 0, 0, 24, 24, 24, 24);
                renderTexture(guiGraphics, this.iconLocation, this.getX() + dis, this.getY() + dis, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
                guiGraphics.drawCenteredString(Minecraft.getInstance().font, Component.translatable("depression.mental_trait." + id), this.getX() + charCenterOffset, this.getY() + (height / 2) - charHalfSize, 0xFFFFFF);
            }
        }
    }
    /** 画原版按钮底图；vOffset/visibleHeight 用来裁掉被上下遮挡的部分（内部是 scissor，不会因高度过小崩溃） */
    private void renderBackgroundSprite(GuiGraphics guiGraphics, int y, int vOffset, int visibleHeight) {
        ResourceLocation sprite = BUTTON_SPRITES.get(this.active, this.isHoveredOrFocused());
        guiGraphics.blitSprite(sprite, this.getWidth(), height, 0, vOffset, this.getX(), y, this.getWidth(), visibleHeight);
    }

    private void renderTexture(GuiGraphics guiGraphics, ResourceLocation location, int x, int y, int u, int v,
                               int width, int visibleHeight, int textureWidth, int textureHeight) {
        guiGraphics.blit(location, x, y, (float) u, (float) v, width, visibleHeight, textureWidth, textureHeight);
    }
}
