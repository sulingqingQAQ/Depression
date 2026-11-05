package net.depression.mental;

import net.depression.Depression;
import net.depression.effect.EffectHolders;
import net.depression.effect.ModEffects;
import net.depression.network.ActionbarHintPacket;
import net.depression.network.MentalStatusPacket;
import net.depression.server.Registry;
import net.depression.util.Tools;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MentalStatus {
    public static double EMOTION_STABILIZE_RATE;
    public static double MENTAL_HEALTH_CHANGE_RATE;
    public static double PTSD_DAMAGE_RATE;
    public static double PTSD_DISPERSE_RATE;
    public static double FOOD_HEAL_RATE;
    public static int BOREDOM_DECREASE_TICK;
    public static boolean IS_RANDOM_CHOOSE_TRAIT;
    public static String DEFAULT_MENTAL_TRAIT;
    public static HashMap<String, String> nearbyHealBlockType = new HashMap<>(); //精神治疗光环方块-类型
    public static HashMap<String, Double> nearbyHealBlockValue = new HashMap<>(); //精神治疗光环方块-治疗值
    public static HashMap<String, Integer> nearbyHealBlockRadius = new HashMap<>(); //精神治疗光环方块-作用半径
    public static HashMap<String, Double> lootHealItem = new HashMap<>(); //loot了会开心的物品
    public static HashMap<String, Double> foodHealValue = new HashMap<>(); //食用食物治疗值
    public static int radiusMaxValue; //精神治疗光环方块-最大的半径
    public static HashMap<String, Double> breakHealBlock = new HashMap<>(); //挖了会开心的方块
    public static HashMap<String, Double> killHealEntity = new HashMap<>(); //杀了会开心的实体
    public static HashMap<String, Double> smeltHealItem = new HashMap<>(); //熔炼了会开心的物品
    public static HashMap<String, Double> healAdvancement = new HashMap<>(); //获得成就会开心
    private final ConcurrentHashMap<String, Integer> boredom = new ConcurrentHashMap<>(); //无聊值
    public final ConcurrentHashMap<String, Double> PTSD = new ConcurrentHashMap<>(); //PTSD值
    public final PTSDManager ptsdManager = new PTSDManager(this, PTSD);
    private final ConcurrentHashMap<String, Long> PTSDTimeBuffer = new ConcurrentHashMap<>(); //PTSD时刻缓冲区（存储造成PTSD的那一个tick）
    private final ConcurrentHashMap<String, Double> PTSDValueBuffer = new ConcurrentHashMap<>(); //PTSD值缓冲区（存储造成PTSD的值）
    public final HashSet<String> playerPTSDSet = new HashSet<>(); //玩家PTSD集合
    public double emotionValue; //情绪值（-20~20），实际上是精神健康值的导数。
    public double mentalHealthValue = 100; //精神健康值（0~100）
    public MentalTrait mentalTrait;
    public int combatCountdown = 0;

    public MentalIllness mentalIllness;
    private ServerPlayer player;
    public long tickCount = -1;
    private int ptsdScanCursor;
    //光环扫描统一交给一条共享的后台线程执行：原来每个玩家各 new 一个 CachedThreadPool 且从不关停，线程数随玩家累积
    private static final ExecutorService HEAL_BLOCK_SCAN_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "depression-heal-block-scan");
        thread.setDaemon(true);
        return thread;
    });
    //上一次提交的扫描还没结束时跳过本次提交，避免任务在队列里堆积
    private final AtomicBoolean healBlockScanPending = new AtomicBoolean(false);
    //主线程抓取、后台线程只读的扫描快照（含已加载区块引用）
    private volatile HealBlockScanSnapshot healBlockScanSnapshot;
    private AttributeModifier speedModifier;

    private AttributeModifier attributeModifier;
    
    public MentalStatus(ServerPlayer player) {
        this.player = player;
        this.mentalIllness = new MentalIllness(player, this);
    }

    public MentalStatus(ServerPlayer player, MentalTrait mentalTrait) {
        loadMentalTrait(mentalTrait);
        this.player = player;
        this.mentalIllness = new MentalIllness(player, this);
    }

    public void loadMentalTrait(MentalTrait mentalTrait) {
        this.mentalTrait = mentalTrait;
        if (mentalTrait.initialMentalHealthValue != null) {
            this.mentalHealthValue = mentalTrait.initialMentalHealthValue;
            this.mentalIllness.mentalHealthLevel = MentalIllness.getMentalHealthLevel(mentalHealthValue);
        }
        if (mentalTrait.initialMentalHealthId != null) {
            this.mentalIllness.mentalHealthId = mentalTrait.initialMentalHealthId;
        }
    }

    public synchronized void triggerHurt(String id) {
        ptsdManager.hurt(id);
    }

    public synchronized void tick(ServerPlayer player) {
        if (mentalTrait == null) {
            return;
        }
        this.player = player;
        ++tickCount;
        //处理无聊值
        if (tickCount % BOREDOM_DECREASE_TICK == 0) {
            for (Map.Entry<String, Integer> entry : boredom.entrySet()) {
                int value = entry.getValue();
                if (value % 4 == 0) {
                    entry.setValue(value - value / 4);
                }
                else {
                    entry.setValue(value - (value / 4 + 1)); //向上取整
                }
                if (entry.getValue() <= 1) { //若无聊值归零，则移除其无聊计数
                    boredom.remove(entry.getKey());
                }
            }
        }
        //处理PTSD缓冲区
        for (Map.Entry<String, Long> entry : PTSDTimeBuffer.entrySet()) {
            if (tickCount - entry.getValue() >= 200L) { //PTSD缓冲区持续200tick后，正式形成PTSD
                String string = entry.getKey();
                Double damage = PTSDValueBuffer.get(string);
                if (damage == null) {
                    PTSDTimeBuffer.remove(string);
                    continue;
                }
                if (!string.equals(player.getDisplayName().getString())) { //如果伤害源不是玩家自己才计入
                    Double originValue = PTSD.get(string);
                    if (originValue == null) {
                        originValue = 0d;
                        EntityType.byString(string).ifPresentOrElse(
                                entityType -> ActionbarHintPacket.sendPTSDFormPacket(player, entityType.getDescription()),
                                () -> {
                                    if (playerPTSDSet.contains(string)) {
                                        ActionbarHintPacket.sendPTSDFormPacket(player, Component.literal(string));
                                    } else {
                                        ActionbarHintPacket.sendPTSDFormPacket(player, Component.translatable("message.depression.damagesource." + string));
                                    }
                                });
                    }
                    PTSD.put(string, Math.min(originValue + damage, PTSDManager.PTSD_MAX_VALUE)); //保证PTSD不超过上限（32）
                }
                PTSDTimeBuffer.remove(string);
                PTSDValueBuffer.remove(string);
            }
        }
        if (tickCount % 20L == 0L) {
            //方块光环检测：主线程先抓"已加载区块"快照，扫描本身提交给全局共享的单线程池。
            //原来每个玩家各建一个 CachedThreadPool、永不关停，而且跨线程调用 getChunk 还会阻塞主线程
            this.healBlockScanSnapshot = snapshotHealBlockScan();
            HEAL_BLOCK_SCAN_EXECUTOR.submit(this::runHealBlockScan);
            //睡眠检测
            ServerLevel level = player.serverLevel();
            if (mentalIllness.isInsomnia != null && level.getDayTime() % 24000 < 12010) {
                mentalIllness.isInsomnia = null;
            }
            if (player.isSleeping()) {
                if (mentalIllness.isInsomnia == null) {
                    mentalIllness.setIsInsomnia();
                }
                if (!mentalIllness.isInsomnia) {
                    mentalHeal("sleeping", 1d);
                }
            }
            //PTSD的自然消散；实体扫描每秒轮转处理最多2条，数值衰减仍逐条执行
            List<String> ptsdScanKeys = selectPTSDScanKeys();
            for (Map.Entry<String, Double> entry : PTSD.entrySet()) {
                String key = entry.getKey();
                double value;
                if (entry.getValue() <= PTSDManager.PTSD_1_VALUE) {
                    value = entry.getValue();
                    entry.setValue(value - PTSD_DISPERSE_RATE * (11 - value / 2)); ////PTSD值 -= 自然消散速度 * (7 - PTSD值)
                }
                else { //PTSD > 12 判定玩家是否触发症状
                    value = entry.getValue() - PTSD_DISPERSE_RATE; //PTSD值 -= 自然消散速度
                    entry.setValue(value);
                    EntityType.byString(key).ifPresentOrElse(
                            entityType -> {
                                if (ptsdScanKeys.contains(key)) {
                                    List<? extends Entity> list = level.getEntities(entityType, player.getBoundingBox().inflate(24), this::viewDetect);
                                    for (Entity entity : list) {
                                        ptsdManager.trigger(entity);
                                    }
                                }
                                if (value > PTSDManager.PTSD_4_VALUE) {
                                    ptsdManager.photismId.add(key);
                                }
                                else {
                                    ptsdManager.photismId.remove(key);
                                }
                            }, () -> {
                                if (!ptsdScanKeys.contains(key)) {
                                    return;
                                }
                                for (ServerPlayer serverPlayer : level.players()) {
                                    String name = serverPlayer.getDisplayName().getString();
                                    if (name.equals(key)) {
                                        if (viewDetect(serverPlayer)) {
                                            ptsdManager.trigger(key);
                                        }
                                    }
                                }
                            }
                            );

                    if (value > PTSDManager.PTSD_3_VALUE) {
                        ptsdManager.phonismId.add(key);
                    }
                    else {
                        ptsdManager.phonismId.remove(key);
                    }
                }
                if (entry.getValue() <= 0) { //如果PTSD值归零，则移除PTSD
                    String id = entry.getKey();
                    removePTSD(id);
                }
            }
            //处理精神健康值
            if (!mentalIllness.isMania) { //躁狂期间精神健康值不随情绪改变
                if (emotionValue < 0) {
                    if (mentalIllness.mentalHealthId == 4) {
                        if (player.hasEffect(EffectHolders.resolve(ModEffects.ANTI_DEPRESSION)) && player.hasEffect(EffectHolders.resolve(ModEffects.ANTI_MANIA)) && player.getEffect(EffectHolders.resolve(ModEffects.ANTI_MANIA)).getAmplifier() >= 2) {
                            mentalHealthValue += emotionValue * MENTAL_HEALTH_CHANGE_RATE * mentalTrait.mentalHurtMultiplier / 4;
                        }
                    }
                    else {
                        mentalHealthValue += emotionValue * MENTAL_HEALTH_CHANGE_RATE * mentalTrait.mentalHurtMultiplier;
                    }
                }
                else {
                    mentalHealthValue += emotionValue * MENTAL_HEALTH_CHANGE_RATE;
                }
            }
            mentalHealthValue = Math.max(0d, mentalHealthValue); //保证精神健康值不超过下限
            mentalHealthValue = Math.min(100d, mentalHealthValue); //保证精神健康值不超过上限

            //情绪值自然归零（速度 0.1/s)
            if (mentalIllness.mentalHealthId < 4) {
                if (emotionValue < 0) {
                    double amplifier = 1d;
                    BlockPos respawnPos = player.getRespawnPosition();
                    if (respawnPos != null && Math.sqrt(respawnPos.distToCenterSqr(player.position())) <= 20) {
                        amplifier *= 1.5d;
                    }
                    if (player.level().getBrightness(LightLayer.SKY, player.blockPosition()) >= 13) {
                        amplifier *= 1.5d;
                    }
                    emotionValue += EMOTION_STABILIZE_RATE * Math.abs(emotionValue) / 20d * amplifier;
                    emotionValue = Math.min(0d, emotionValue); //保证情绪值归0
                } else {
                    emotionValue -= EMOTION_STABILIZE_RATE * Math.abs(emotionValue) / 20d;
                    emotionValue = Math.max(0d, emotionValue); //保证情绪值归0
                }
            }

            //更新属性
            AttributeInstance movementSpeed = player.getAttribute(Attributes.MOVEMENT_SPEED);
            AttributeInstance attackDamage = player.getAttribute(Attributes.ATTACK_DAMAGE);
            AttributeInstance attackSpeed = player.getAttribute(Attributes.ATTACK_SPEED);
            if (attributeModifier != null) {
                movementSpeed.removeModifier(speedModifier);
                attackDamage.removeModifier(attributeModifier);
                attackSpeed.removeModifier(attributeModifier);
            }
            double emotionModifier = emotionValue * 1.5d / 100d;
            if (combatCountdown > 0 && emotionModifier < 0) { //如果处于战斗状态且情绪比较负面，则清除速度的负面加成
                speedModifier = new AttributeModifier(ResourceLocation.fromNamespaceAndPath("depression", "speed_modifier"), 0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
            }
            else {
                speedModifier = new AttributeModifier(ResourceLocation.fromNamespaceAndPath("depression", "speed_modifier"), emotionModifier - getMentalHealthModifier(), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
            }
            if (emotionModifier < 0 && !mentalTrait.isBadEmotionLowerCombat) {
                emotionModifier = 0;
            }
            if (emotionModifier > 0 && !mentalTrait.isGoodEmotionHigherCombat) {
                emotionModifier = 0;
            }
            attributeModifier = new AttributeModifier(ResourceLocation.fromNamespaceAndPath("depression", "emotion_modifier"), emotionModifier - getMentalHealthModifier(), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
            movementSpeed.addTransientModifier(speedModifier);
            attackDamage.addTransientModifier(attributeModifier);
            attackSpeed.addTransientModifier(attributeModifier);
            MentalStatusPacket.sendToPlayer(player, this);

            if (combatCountdown > 0) {
                --combatCountdown;
            }

            ptsdManager.tick(player);
        }
        mentalIllness.tick(player);
    }

    private double getMentalHealthModifier() {
        if (mentalIllness.mentalHealthId < 4) {
            return mentalIllness.mentalHealthId / 10d;
        }
        else {
            if (isMania()) {
                return 0;
            }
            else {
                return 0.3d;
            }
        }
    }

    private List<String> selectPTSDScanKeys() {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Double> entry : PTSD.entrySet()) {
            if (entry.getValue() > PTSDManager.PTSD_1_VALUE) {
                keys.add(entry.getKey());
            }
        }
        int size = keys.size();
        if (size <= 2) {
            ptsdScanCursor = 0;
            return keys;
        }
        int start = Math.floorMod(ptsdScanCursor, size);
        List<String> selected = new ArrayList<>(2);
        for (int i = 0; i < 2; ++i) {
            selected.add(keys.get((start + i) % size));
        }
        ptsdScanCursor = (start + 2) % size;
        return selected;
    }

    public synchronized boolean viewDetect(Entity entity) {
        double x = entity.getX() - player.getX();
        double y = entity.getEyeY() - player.getEyeY();
        double z = entity.getZ() - player.getZ();
        double distanceSqr = x * x + y * y + z * z;
        if (distanceSqr > 24d * 24d) {
            return false;
        }
        Vec3 vec3 = player.getViewVector(1.0F).normalize();
        Vec3 vec32 = new Vec3(x, y, z);
        double d = Math.sqrt(distanceSqr);
        vec32 = vec32.normalize();
        double e = vec3.dot(vec32);
        return e > 1.0 - 0.5 / d ? player.hasLineOfSight(entity) : false;
    }

    public synchronized void removePTSD(String id) {
        PTSD.remove(id);
        EntityType.byString(id).ifPresentOrElse(
                entityType -> ActionbarHintPacket.sendPTSDDispersePacket(player, entityType.getDescription()),
                () -> {
                    if (playerPTSDSet.contains(id)) {
                        ActionbarHintPacket.sendPTSDDispersePacket(player, Component.literal(id));
                        playerPTSDSet.remove(id);
                    }
                    else {
                        ActionbarHintPacket.sendPTSDDispersePacket(player, Component.translatable("message.depression.damagesource." + id));
                    }
                });
    }

    public synchronized double mentalHeal(double value) {
        if (getMentalHealthId() == 4) {
            return 0;
        }
        double toReturn = value * mentalHealthValue / 100d;
        emotionValue += toReturn; //情绪值 += 治疗值 * 精神健康值 / 100
        emotionValue = Math.min(20d, emotionValue); //保证情绪值不超过上限
        return toReturn;
    }

    public synchronized double mentalHeal(String string, double value, int count) {
        Integer i = boredom.get(string);
        if (i == null) {
            i = 0;
        }
        boredom.put(string, i + count);
        return mentalHeal((Tools.getHarmonic(i+count) - Tools.getHarmonic(i)) * value);
    }
    public synchronized double mentalHeal(String string, double value) {
        if (boredom.containsKey(string)) {
            boredom.put(string, boredom.get(string) + 1);
        }
        else {
            boredom.put(string, 2);
        }
        value /= boredom.get(string) / 2d; //治疗值 /= 无聊值;
        return mentalHeal(value);
    }

    public synchronized void mentalHurt(double value) {
        if (player.isCreative() || player.isSpectator()) {
            return;
        }
        emotionValue -= value; //情绪值 -= 伤害值
        emotionValue = Math.max(-20d, emotionValue); //保证情绪值不超过下限
    }

    public synchronized void mentalHurt(Component component, double damage) {
        playerPTSDSet.add(component.getString());
        mentalHurt(component.getString(), damage);
    }

    public synchronized void mentalHurt(String string, double damage) {
        if (string == null) {
            return;
        }
        if (!string.equals(player.getDisplayName().getString())) { //如果伤害源不是玩家自己才计入PTSD
            Level level = player.level();
            BlockPos pos = player.blockPosition();
            int brightness = Math.max(level.getBrightness(LightLayer.BLOCK, pos), level.getBrightness(LightLayer.SKY, pos));
            if (brightness <= 7 && mentalTrait.isDarknessAffectEmotion) {
                damage *= 1.3d + (7d - brightness) / 7d * 0.2d;
            }
            if (PTSDTimeBuffer.containsKey(string)) { //如果缓冲区中已经存在PTSD，则更新PTSD缓冲值
                Double originValue = PTSDValueBuffer.get(string);
                if (originValue == null) {
                    originValue = 0d;
                }
                PTSDTimeBuffer.put(string, tickCount); //更新PTSD时刻缓冲区
                PTSDValueBuffer.put(string, originValue + damage); //更新PTSD值缓冲区
            }
            else {
                Double PTSDValue = PTSD.get(string);
                if (PTSDValue != null) { //如果此前有过PTSD且缓冲区没有，则计算为犯了PTSD，PTSD影响情绪
                    mentalHurt(PTSDValue * PTSD_DAMAGE_RATE); //情绪值 -= PTSD值原量 * 0.25
                }
                PTSDTimeBuffer.put(string, tickCount); //加入PTSD时刻缓冲区
                PTSDValueBuffer.put(string, damage); //加入PTSD值缓冲区
            }
        }
        mentalHurt(damage); //保证情绪值不超过下限
    }


    //后台线程的实际执行体：上一次扫描没跑完时直接放弃本次，避免任务堆积
    private void runHealBlockScan() {
        if (!healBlockScanPending.compareAndSet(false, true)) {
            return; //上一次扫描还没结束，本次跳过
        }
        try {
            detectNearbyHealBlock();
        }
        catch (Throwable throwable) {
            Depression.LOGGER.error("Failed to scan nearby heal blocks", throwable);
        }
        finally {
            healBlockScanPending.set(false);
        }
    }

    //光环球扫描需要的主线程快照：区块引用必须在主线程取（ServerChunkCache#getChunkNow 在非主线程恒返回 null）
    private static final class HealBlockScanSnapshot {
        private final Level level;
        private final ServerPlayer player;
        private final int baseX;
        private final int baseY;
        private final int baseZ;
        private final int radius;
        private final int minChunkX;
        private final int minChunkZ;
        private final int chunkSpanZ;
        private final LevelChunk[] loadedChunks;

        private HealBlockScanSnapshot(Level level, ServerPlayer player, BlockPos center, int radius,
                                      int minChunkX, int minChunkZ, int chunkSpanZ, LevelChunk[] loadedChunks) {
            this.level = level;
            this.player = player;
            this.baseX = center.getX();
            this.baseY = center.getY();
            this.baseZ = center.getZ();
            this.radius = radius;
            this.minChunkX = minChunkX;
            this.minChunkZ = minChunkZ;
            this.chunkSpanZ = chunkSpanZ;
            this.loadedChunks = loadedChunks;
        }
    }

    //在主线程抓取本次扫描的快照（只取已加载区块，不触发区块加载），后台线程只读使用
    private HealBlockScanSnapshot snapshotHealBlockScan() {
        ServerPlayer scanningPlayer = player;
        int r = radiusMaxValue;
        if (scanningPlayer == null || r < 0 || scanningPlayer.hasDisconnected()) {
            return null;
        }
        Level level = scanningPlayer.level();
        BlockPos center = scanningPlayer.blockPosition();
        int minChunkX = (center.getX() - r) >> 4;
        int maxChunkX = (center.getX() + r) >> 4;
        int minChunkZ = (center.getZ() - r) >> 4;
        int maxChunkZ = (center.getZ() + r) >> 4;
        int chunkSpanZ = maxChunkZ - minChunkZ + 1;
        LevelChunk[] loadedChunks = new LevelChunk[(maxChunkX - minChunkX + 1) * chunkSpanZ];
        for (int chunkX = minChunkX; chunkX <= maxChunkX; ++chunkX) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; ++chunkZ) {
                loadedChunks[(chunkX - minChunkX) * chunkSpanZ + (chunkZ - minChunkZ)] = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            }
        }
        return new HealBlockScanSnapshot(level, scanningPlayer, center, r, minChunkX, minChunkZ, chunkSpanZ, loadedChunks);
    }

    public void detectNearbyHealBlock() { //检测周围球形半径的治疗光环方块
        final HealBlockScanSnapshot snapshot = this.healBlockScanSnapshot; //主线程写入的快照
        if (snapshot == null) {
            return;
        }
        final ServerPlayer scanningPlayer = snapshot.player;
        final Level level = snapshot.level;
        final int r = snapshot.radius;
        final int baseX = snapshot.baseX;
        final int baseY = snapshot.baseY;
        final int baseZ = snapshot.baseZ;
        final int minChunkX = snapshot.minChunkX;
        final int minChunkZ = snapshot.minChunkZ;
        final int chunkSpanZ = snapshot.chunkSpanZ;
        final LevelChunk[] loadedChunks = snapshot.loadedChunks;

        HashMap<Component, Integer> detectedBlocksCount = new HashMap<>();
        HashMap<String, Double> detectedTypesHealValue = new HashMap<>();
        HashMap<Block, HealBlockInfo> blockInfoCache = new HashMap<>(); //同一次扫描内按方块去重，避免每格重复分配注册名/名称
        double totalHealValue = 0d;

        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos(); //整次扫描复用同一个坐标对象
        for (int x = -r; x <= r; ++x) { // -r <= x <= r
            int yLimit = (int) Math.sqrt(r*r - x*x);
            for (int y = -yLimit; y <= yLimit; ++y) { // -sqrt(r*r - x*x) <= y <= sqrt(r*r - x*x)
                int worldY = baseY + y;
                if (level.isOutsideBuildHeight(worldY)) { //世界高度之外不存在方块，直接跳过
                    continue;
                }
                int zLimit = (int) Math.sqrt(r*r - x*x - y*y);
                for (int z = -zLimit; z <= zLimit; ++z) { // -sqrt(r*r - x*x - y*y) <= z <= sqrt(r*r - x*x - y*y)
                    int worldX = baseX + x;
                    int worldZ = baseZ + z;
                    LevelChunk chunk = loadedChunks[((worldX >> 4) - minChunkX) * chunkSpanZ + ((worldZ >> 4) - minChunkZ)];
                    if (chunk == null) { //该区块尚未加载，跳过
                        continue;
                    }
                    mutablePos.set(worldX, worldY, worldZ);
                    Block block = chunk.getBlockState(mutablePos).getBlock();
                    if (block instanceof FlowerPotBlock) {
                        block = ((FlowerPotBlock) block).getPotted();
                    }
                    HealBlockInfo info = blockInfoCache.get(block);
                    if (info == null) {
                        info = resolveHealBlockInfo(block);
                        blockInfoCache.put(block, info);
                    }
                    if (!info.isHealBlock()) {
                        continue;
                    }
                    int distance = (int) Math.sqrt(x*x + y*y + z*z);
                    if (distance <= info.radius) {
                        int count = detectedBlocksCount.getOrDefault(info.name, 0) + 1;
                        detectedBlocksCount.put(info.name, count);
                        detectedTypesHealValue.put(info.type, detectedTypesHealValue.getOrDefault(info.type, 0d) + info.value / count);
                    }
                }
            }
        }
        for (Map.Entry<String, Double> entry : detectedTypesHealValue.entrySet()) {
            totalHealValue += mentalHeal(entry.getKey(), entry.getValue());
        }

        if (totalHealValue > 0.25d) {
            Component maxHealName = null;
            int mostHealCount = 0;
            for (Map.Entry<Component, Integer> entry : detectedBlocksCount.entrySet()) {
                if (entry.getValue() > mostHealCount) {
                    mostHealCount = entry.getValue();
                    maxHealName = entry.getKey();
                }
            }
            if (maxHealName != null) {
                ActionbarHintPacket.sendNearbyBlockHealPacket(scanningPlayer, maxHealName);
            }
        }
    }

    //方块 -> 治疗信息：注册名、名称组件与治疗值每种方块只解析一次（原来每格都解析一遍）
    private HealBlockInfo resolveHealBlockInfo(Block block) {
        String id = block.arch$registryName().toString();
        Double value = getTypeHealValue(id);
        Integer radius = nearbyHealBlockRadius.get(id);
        if (value == null || radius == null) {
            return HealBlockInfo.NONE;
        }
        String type = nearbyHealBlockType.get(id);
        if (type == null) {
            type = id;
        }
        return new HealBlockInfo(block.getName(), value, radius, type);
    }

    private static final class HealBlockInfo {
        private static final HealBlockInfo NONE = new HealBlockInfo(null, null, null, null);
        private final Component name;
        private final Double value;
        private final Integer radius;
        private final String type;

        private HealBlockInfo(Component name, Double value, Integer radius, String type) {
            this.name = name;
            this.value = value;
            this.radius = radius;
            this.type = type;
        }

        private boolean isHealBlock() {
            return this.value != null && this.radius != null;
        }
    }

    private Double getTypeHealValue(String id) {
        Double value = nearbyHealBlockValue.get(id);
        if (value == null) {
            return null;
        }
        if (nearbyHealBlockType.containsKey(id)) {
            String type = nearbyHealBlockType.get(id);
            if (boredom.containsKey(type)) {
                return value / (boredom.get(type) / 2d);
            }
            else {
                return value;
            }
        }
        if (boredom.containsKey(id)) {
            return value / (boredom.get(id) / 2d);
        }
        else {
            return value;
        }
    }

    public boolean isMania() {
        return mentalIllness.isMania;
    }

    public int getMentalHealthId() {
        return mentalIllness.mentalHealthId;
    }


    public void readNbt(CompoundTag tag) {
        //读取精神特质
        if (tag.contains("mental_trait")) {
            mentalTrait = MentalTrait.mentalTraits.getOrDefault(tag.getString("mental_trait"), new MentalTrait("normal"));
        }
        //读取无聊值
        CompoundTag boredomTag = tag.getCompound("boredom");
        for (String key : boredomTag.getAllKeys()) {
            boredom.put(key, boredomTag.getInt(key));
        }
        //读取PTSD
        CompoundTag ptsd = tag.getCompound("PTSD");
        for (String key : ptsd.getAllKeys()) {
            PTSD.put(key, ptsd.getDouble(key));
        }
        //读取PTSD时刻缓冲区
        CompoundTag ptsdTimeBuffer = tag.getCompound("PTSD_time_buffer");
        for (String key : ptsdTimeBuffer.getAllKeys()) {
            PTSDTimeBuffer.put(key, tickCount - ptsdTimeBuffer.getLong(key)); //读取造成PTSD的时刻而不是时间
        }
        //读取PTSD值缓冲区
        CompoundTag ptsdValueBuffer = tag.getCompound("PTSD_value_buffer");
        for (String key : ptsdValueBuffer.getAllKeys()) {
            PTSDValueBuffer.put(key, ptsdValueBuffer.getDouble(key));
        }
        //读取玩家PTSD缓冲区
        if (tag.contains("player_ptsd_buffer")) {
            ListTag playerPTSDBufferTag = tag.getList("player_ptsd_buffer", 8);
            for (int i = 0; i < playerPTSDBufferTag.size(); ++i) {
                playerPTSDSet.add(playerPTSDBufferTag.getString(i));
            }
        }
        //读取情绪值
        if (tag.contains("emotion_value")) {
            emotionValue = tag.getDouble("emotion_value");
        }
        //读取精神健康值
        if (tag.contains("mental_health_value")) {
            mentalHealthValue = tag.getDouble("mental_health_value");
        }
        //读取战斗状态倒计时
        if (tag.contains("combat_countdown")) {
            combatCountdown = tag.getInt("combat_countdown");
        }
        //读取精神疾病相关内容
        CompoundTag mentalIllnessTag = tag.getCompound("mental_illness");
        mentalIllness.readNbt(mentalIllnessTag);
        //读取PTSD管理相关内容
        CompoundTag ptsdManagerTag = tag.getCompound("ptsd_manager");
        ptsdManager.readNbt(ptsdManagerTag);
    }

    public void writeNbt(CompoundTag tag) {
        //写入精神特质
        if (mentalTrait != null) {
            tag.putString("mental_trait", mentalTrait.id);
        }
        //写入无聊值
        CompoundTag boredomTag = new CompoundTag();
        for (Map.Entry<String, Integer> entry : boredom.entrySet()) {
            boredomTag.putInt(entry.getKey(), entry.getValue());
        }
        tag.put("boredom", boredomTag);
        //写入PTSD
        CompoundTag ptsd = new CompoundTag();
        for (Map.Entry<String, Double> entry : PTSD.entrySet()) {
            ptsd.putDouble(entry.getKey(), entry.getValue());
        }
        tag.put("PTSD", ptsd);
        //写入PTSD时刻缓冲区
        CompoundTag ptsdTimeBuffer = new CompoundTag();
        for (Map.Entry<String, Long> entry : PTSDTimeBuffer.entrySet()) {
            ptsdTimeBuffer.putLong(entry.getKey(), tickCount - entry.getValue()); //存储造成PTSD的时间而不是时刻
        }
        tag.put("PTSD_time_buffer", ptsdTimeBuffer);
        //写入PTSD值缓冲区
        CompoundTag ptsdValueBuffer = new CompoundTag();
        for (Map.Entry<String, Double> entry : PTSDValueBuffer.entrySet()) {
            ptsdValueBuffer.putDouble(entry.getKey(), entry.getValue());
        }
        tag.put("PTSD_value_buffer", ptsdValueBuffer);
        //写入玩家PTSD缓冲区
        if (!playerPTSDSet.isEmpty()) {
            ListTag playerPTSDBufferTag = new ListTag();
            for (String string : playerPTSDSet) {
                playerPTSDBufferTag.add(StringTag.valueOf(string));
            }
            tag.put("player_ptsd_buffer", playerPTSDBufferTag);
        }
        //写入情绪值
        tag.putDouble("emotion_value", emotionValue);
        //写入精神健康值
        tag.putDouble("mental_health_value", mentalHealthValue);
        //写入战斗状态倒计时
        tag.putDouble("combat_countdown", combatCountdown);
        //写入精神疾病相关内容
        CompoundTag mentalIllnessTag = new CompoundTag();
        mentalIllness.writeNbt(mentalIllnessTag);
        tag.put("mental_illness", mentalIllnessTag);
        //写入PTSD管理相关内容
        CompoundTag ptsdManagerTag = new CompoundTag();
        ptsdManager.writeNbt(ptsdManagerTag);
        tag.put("ptsd_manager", ptsdManagerTag);
    }

    public static MentalStatus getMentalStatusByServerPlayer(final Player player) {
        final var uuid = player.getUUID();
        var instance = Registry.mentalStatus.get(uuid);
        if (instance == null && player instanceof ServerPlayer serverPlayer) {
            instance = new MentalStatus(serverPlayer);
            Registry.mentalStatus.put(uuid, instance);
        } else if(!(player instanceof ServerPlayer)) {
            throw new RuntimeException("You are not a ServerPlayer");
        }
        return instance;
    }

    public static double onLoot(Player player, ItemStack itemStack) {
        Item item = itemStack.getItem();
        String id = item.arch$registryName().toString();
        MentalStatus mentalStatus = MentalStatus.getMentalStatusByServerPlayer(player);
        double idHealValue = 0;
        double enchantmentHealValue = 0;
        if (lootHealItem.containsKey(id)) {
            idHealValue = mentalStatus.mentalHeal(id, lootHealItem.get(id), itemStack.getCount());
        }
        if (itemStack.isEnchanted()) { //如果钓到的东西有附魔

            var enchantments = itemStack.getEnchantments();
            for (Holder<Enchantment> holder : enchantments.keySet()) { // 1.21.1: EnchantmentHelper.getEnchantments(stack) → stack.getEnchantments()
                int level = enchantments.getLevel(holder);
                double weight = holder.value().getWeight(); // 1.21.1: isCurse()/getRarity().getWeight() → EnchantmentTags.CURSE / Enchantment.getWeight()
                if (holder.is(EnchantmentTags.CURSE)) {
                    enchantmentHealValue -= level / weight * level;
                }
                else {
                    enchantmentHealValue += level / weight * level;
                }
            }
            if (enchantmentHealValue > 0) {
                mentalStatus.mentalHeal(enchantmentHealValue);
            }
        }
        return idHealValue + enchantmentHealValue;
    }
}
