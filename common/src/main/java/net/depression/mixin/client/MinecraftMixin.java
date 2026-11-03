package net.depression.mixin.client;

import net.depression.client.DepressionClient;
import net.depression.client.rhythmcraft.ClientPlayingChart;
import net.depression.rhythmcraft.RCMinecart;
import net.depression.screen.rhythmcraft.RCLoadingScreen;
import net.depression.screen.rhythmcraft.RCSelectionScreen;
import net.depression.util.OggStreamPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Shadow @Nullable public Screen screen;
    @Shadow private static Minecraft instance;

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void onSetScreen(Screen screen, CallbackInfo ci) {
        if (screen != null) {
            if (this.screen instanceof RCLoadingScreen) { //防止谱面加载页面被原版页面覆盖
                ci.cancel();
            }
            if (this.screen instanceof RCSelectionScreen) {
                if (!(screen instanceof RCLoadingScreen)) {
                    ci.cancel();
                }
            }
        }
    }
    //1.21.1 起原 clearLevel(Screen) 拆成 disconnect(Screen, boolean)（退出世界）和 clearClientLevel(Screen)（重新进入配置阶段）
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("HEAD"))
    private void onDisconnect(Screen screen, boolean keepResourcePacks, CallbackInfo ci) {
        OggStreamPlayer oggStreamPlayer = DepressionClient.oggStreamPlayer;
        oggStreamPlayer.stop();
    }
    @Inject(method = "clearClientLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"))
    private void onClearLevel(Screen screen, CallbackInfo ci) {
        OggStreamPlayer oggStreamPlayer = DepressionClient.oggStreamPlayer;
        oggStreamPlayer.stop();
    }
}
