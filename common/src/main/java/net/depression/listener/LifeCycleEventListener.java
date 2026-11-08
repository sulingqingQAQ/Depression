package net.depression.listener;

import net.depression.Depression;
import net.depression.client.rhythmcraft.ClientPlayingChart;
import net.depression.item.MedicineItem;
import net.depression.mental.MentalStatus;
import net.depression.world.VillageAdditions;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.Ingredient;


public class LifeCycleEventListener {
    /**
     * 服务器停止（专服 /stop、单人退到标题、换世界）之后的全量清扫。
     * 必须晚于 stopServer 内的 saveAll / removeAll / saveAllChunks（SERVER_STOPPED 在 stopServer 全部完成之后触发），
     * 因此不会抢在任何一次保存之前清内存；单人房主的持久状态也在这里才清（PlayerList.remove RETURN 对房主不清）。
     */
    public static void onServerStopped(MinecraftServer server) {
        net.depression.server.PlayerSaveLifecycle.onServerStopped();
    }

    public static void onServerStart(MinecraftServer server) {
        net.depression.server.Registry.init();
        RegistryAccess registryAccess = server.registryAccess();
        Registry<Item> items = registryAccess.registryOrThrow(Registries.ITEM);
        for (Item item : items) {
            String id = item.arch$registryName().toString();
            try {
                if (MentalStatus.lootHealItem.containsKey(id)) {
                    continue;
                }
                if (item instanceof TieredItem tieredItem) {
                    Tier tier = tieredItem.getTier();
                    // 1.21.1: Tiers 已无 getLevel()，穷举回填旧等级（WOOD 0/STONE 1/IRON 2/DIAMOND 3/GOLD 0/NETHERITE 4），非原版 tier 默认 1
                    int tierLevel = 1;
                    if (tier instanceof Tiers tiers) {
                        tierLevel = switch (tiers) {
                            case WOOD -> 0;
                            case STONE -> 1;
                            case IRON -> 2;
                            case DIAMOND -> 3;
                            case GOLD -> 0;
                            case NETHERITE -> 4;
                        };
                    }
                    double value = 0.1 * Math.pow(tierLevel, 3);
                    MentalStatus.lootHealItem.put(id, value);
                    for (ItemStack material : tier.getRepairIngredient().getItems()) {
                        String materialId = material.getItem().arch$registryName().toString();
                        if (!MentalStatus.lootHealItem.containsKey(materialId)) {
                            MentalStatus.lootHealItem.put(materialId, value);
                        }
                    }
                }
                if (item instanceof ArmorItem armorItem) {
                    double value = 0.4 * armorItem.getDefense() + 0.2 * armorItem.getToughness() + 0.1 * armorItem.getMaterial().value().knockbackResistance(); // 1.21.1: getMaterial() 返回 Holder<ArmorMaterial>
                    MentalStatus.lootHealItem.put(id, value);
                }
                if (item instanceof ProjectileWeaponItem projectileWeaponItem) {
                    Integer maxDamage = projectileWeaponItem.components().get(DataComponents.MAX_DAMAGE); // 1.21.1: getMaxDamage() 已移除，改走组件
                    double damage = maxDamage == null ? 0 : maxDamage; // 组件缺省 0，与旧 getMaxDamage() 行为一致
                    if (damage > 5) {
                        double value = 0.2 * (damage - 5);
                        MentalStatus.lootHealItem.put(id, value);
                    }
                }
            }
            catch (Exception e) {
                Depression.LOGGER.error("Failed to load item " + id);
                e.printStackTrace();
            }
        }
        VillageAdditions.init(registryAccess);
    }
}
