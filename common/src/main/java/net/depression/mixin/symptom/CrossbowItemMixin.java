package net.depression.mixin.symptom;

import net.depression.mental.MentalStatus;
import net.depression.server.Registry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CrossbowItem.class)
public abstract class CrossbowItemMixin {
    //1.21.1 起 onCrossbowShot 被并入 performShooting，射出后触发 SHOT_CROSSBOW 的位置等同于原 onCrossbowShot
    @Inject(method = "performShooting", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/advancements/critereon/ShotCrossbowTrigger;trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;)V"))
    private void onCrossbowShot(Level level, LivingEntity livingEntity, InteractionHand hand, ItemStack itemStack, float velocity, float inaccuracy, LivingEntity target, CallbackInfo ci) {
        if (livingEntity instanceof ServerPlayer) {
            ServerPlayer player = (ServerPlayer) livingEntity;
            if (player.isCreative()) {
                return;
            }
            MentalStatus mentalStatus = MentalStatus.getMentalStatusByServerPlayer(player);
            mentalStatus.mentalIllness.trigMentalFatigue();
        }
    }
}
