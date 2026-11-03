package net.depression.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

public class VerticalSliderButton extends AbstractSliderButton {
    private static final ResourceLocation SLIDER_LOCATION = ResourceLocation.withDefaultNamespace("textures/gui/slider.png");
    private int pixelValue; //滑块滑动的像素值
    private int screenValue; //滑块滑动的屏幕值
    private final int maxScreenValue;
    private final int sliderHeight;
    private final int halfSliderHeight;
    public VerticalSliderButton(int i, int j, int k, int l, Component component, double d, int maxScreenValue) {
        super(i, j, k, l, component, d);
        this.sliderHeight = height * height / maxScreenValue;
        this.maxScreenValue = maxScreenValue - height;
        this.halfSliderHeight = sliderHeight / 2;
    }
    @Override
    protected void updateMessage() {}
    @Override
    protected void applyValue() {
        pixelValue = (int) (value * (this.height - sliderHeight));
        screenValue = (int) (value * maxScreenValue);
    }
    public int getScreenValue() {
        return screenValue;
    }
    private int getTextureY() {
        // 1.21.1: AbstractSliderButton#getTextureY 已移除，按原版三态贴图还原：未激活 0 / 常态 20 / 悬停聚焦 40
        if (!this.active) {
            return 0;
        }
        return this.isHoveredOrFocused() ? 40 : 20;
    }
    private int getHandleTextureY() {
        if (!this.active) {
            return 0;
        }
        return this.isHoveredOrFocused() ? 40 : 20;
    }
    @Override
    public void renderWidget(GuiGraphics guiGraphics, int i, int j, float f) {
        Minecraft minecraft = Minecraft.getInstance();
        guiGraphics.setColor(1.0F, 1.0F, 1.0F, this.alpha);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        guiGraphics.blit(SLIDER_LOCATION, this.getX(), this.getY(), 0, getTextureY(), this.getWidth(), this.getHeight());
        guiGraphics.blit(SLIDER_LOCATION, this.getX() , this.getY() + pixelValue, 0, getHandleTextureY(), 8, sliderHeight);
        guiGraphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        int k = this.active ? 16777215 : 10526880;
        this.renderScrollingString(guiGraphics, minecraft.font, 2, k | Mth.ceil(this.alpha * 255.0F) << 24);
    }
    @Override
    public void onClick(double mouseX, double mouseY) {
        this.setValueFromMouse(mouseY);
    }
    @Override
    public void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        this.setValueFromMouse(mouseY);
    }
    @Override
    public void onRelease(double mouseX, double mouseY) {
        super.onRelease(mouseX, mouseY);
    }
    public void mouseScrolled(double delta) {
        this.value = Math.max(0, Math.min(this.value + (delta > 0 ? -0.1 : 0.1), 1));
        this.applyValue();
    }

    private void setValueFromMouse(double mouseY) {
        double sliderMin = this.getY() + this.halfSliderHeight;
        double sliderMax = this.getY() + this.height - this.halfSliderHeight;
        value = (mouseY - sliderMin) / (sliderMax - sliderMin);
        value = Math.max(0, Math.min(value, 1));
        this.applyValue();
    }
}
