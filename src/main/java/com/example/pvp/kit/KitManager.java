package com.example.pvp.kit;

import com.example.pvp.config.KitConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.potion.Potion;
import net.minecraft.potion.Potions;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 套件管理器：内置三种套件 + 从 kits.json 加载的自定义套件，支持热重载。
 */
public final class KitManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final List<Kit> KITS = new ArrayList<>();

    /** 空岛战争哨兵套件：无物品、生存模式。仅作为占位避免空岛战争流程里 Kit 为 null。 */
    private static Kit skywarsKit;

    /** 战桥哨兵套件：实际装备由 {@link BridgeGear} 按队伍色发放，这里仅作队列占位。 */
    private static Kit bridgeKit;

    /** 幸运之柱哨兵套件：空手开局、生存模式，仅作队列占位。 */
    private static Kit luckyPillarKit;

    /** TNT 跑酷哨兵套件：空手开局、生存模式，仅作队列占位。 */
    private static Kit tntRunKit;

    /** 心跳水立方哨兵套件：空手开局、冒险模式，仅作队列占位。 */
    private static Kit heartbeatKit;

    /** 烫手山芋哨兵套件：空手开局、冒险模式，仅作队列占位。 */
    private static Kit hotPotatoKit;

    /** 起床战争哨兵套件：生存模式，装备由玩法发放。 */
    private static Kit bedWarsKit;

    /** 附魔注册表：服务器启动后才可用，用于给套件物品加附魔。 */
    private static Registry<Enchantment> enchantmentRegistry;

    private KitManager() {
    }

    /** 服务器启动后调用：此时附魔注册表可用，重建套件以应用附魔。 */
    public static void onServerStarted(MinecraftServer server) {
        enchantmentRegistry = server.getRegistryManager().get(RegistryKeys.ENCHANTMENT);
        reload();
    }

    public static void reload() {
        KITS.clear();
        skywarsKit = new Kit.Builder("skywars", KitType.CUSTOM)
                .displayName("空岛战争")
                .food(20, 5f)
                .gamemode(GameMode.SURVIVAL)
                .build();
        bridgeKit = new Kit.Builder("bridge", KitType.CUSTOM)
                .displayName("战桥")
                .food(20, 20f)
                .gamemode(GameMode.SURVIVAL)
                .build();
        luckyPillarKit = new Kit.Builder("luckypillar", KitType.CUSTOM)
                .displayName("幸运之柱")
                .food(20, 5f)
                .gamemode(GameMode.SURVIVAL)
                .build();
        tntRunKit = new Kit.Builder("tntrun", KitType.CUSTOM)
                .displayName("TNT 跑酷")
                .food(20, 5f)
                .gamemode(GameMode.SURVIVAL)
                .build();
        heartbeatKit = new Kit.Builder("heartbeat", KitType.CUSTOM)
                .displayName("心跳水立方")
                .food(20, 5f)
                .gamemode(GameMode.ADVENTURE)
                .build();
        hotPotatoKit = new Kit.Builder("hotpotato", KitType.CUSTOM)
                .displayName("烫手山芋")
                .food(20, 5f)
                .gamemode(GameMode.ADVENTURE)
                .build();
        bedWarsKit = new Kit.Builder("bedwars", KitType.CUSTOM)
                .displayName("起床战争")
                .food(20, 20f)
                .gamemode(GameMode.SURVIVAL)
                .build();
        KITS.add(buildSwordKit());
        KITS.add(buildBowKit());
        KITS.add(buildFullGearKit());
        KITS.add(buildIronPvpKit());
        KITS.add(buildSumoKit());
        KITS.add(buildNoDebuffKit());
        KITS.add(buildGappleKit());
        KITS.add(buildAxeKit());
        KITS.add(buildLegacy18Kit());

        for (KitConfig.CustomKitSpec spec : KitConfig.INSTANCE.kits) {
            Kit kit = buildCustomKit(spec);
            if (kit != null) {
                KITS.add(kit);
            }
        }
        LOGGER.info("[PvP] 已加载 {} 套装备方案", KITS.size());
    }

    public static Kit get(String id) {
        if (id == null) {
            return null;
        }
        for (Kit kit : KITS) {
            if (kit.getId().equalsIgnoreCase(id)) {
                return kit;
            }
        }
        return null;
    }

    public static List<Kit> getKits() {
        return List.copyOf(KITS);
    }

    public static List<String> getKitIds() {
        List<String> ids = new ArrayList<>();
        for (Kit kit : KITS) {
            ids.add(kit.getId());
        }
        return ids;
    }

    /** 空岛战争哨兵套件（不入 KITS 列表，避免出现在套件选择页）。 */
    public static Kit skywarsKit() {
        return skywarsKit;
    }

    /** 战桥哨兵套件（不入 KITS 列表，装备由 {@link BridgeGear} 按队伍色发放）。 */
    public static Kit bridgeKit() {
        return bridgeKit;
    }

    /** 幸运之柱哨兵套件（不入 KITS 列表，实际空手开局）。 */
    public static Kit luckyPillarKit() {
        return luckyPillarKit;
    }

    /** TNT 跑酷哨兵套件（不入 KITS 列表，实际空手开局）。 */
    public static Kit tntRunKit() {
        return tntRunKit;
    }

    /** 心跳水立方哨兵套件（不入 KITS 列表，实际空手开局）。 */
    public static Kit heartbeatKit() {
        return heartbeatKit;
    }

    /** 烫手山芋哨兵套件（不入 KITS 列表，实际空手开局）。 */
    public static Kit hotPotatoKit() {
        return hotPotatoKit;
    }

    /** 起床战争哨兵套件（不入 KITS 列表，装备由玩法发放）。 */
    public static Kit bedWarsKit() {
        return bedWarsKit;
    }

    private static Kit buildSwordKit() {
        return new Kit.Builder("sword", KitType.SWORD)
                .displayName("剑战")
                .addItem(stack(Items.IRON_SWORD))
                .addItem(stack(Items.COOKED_BEEF, 4))
                .build();
    }

    private static Kit buildBowKit() {
        return new Kit.Builder("bow", KitType.BOW)
                .displayName("弓箭")
                .addItem(stack(Items.BOW))
                .addItem(stack(Items.ARROW, 32))
                .addItem(stack(Items.COOKED_BEEF, 4))
                .build();
    }

    private static Kit buildFullGearKit() {
        return new Kit.Builder("full_gear", KitType.FULL_GEAR)
                .displayName("全装备")
                .addItem(stack(Items.DIAMOND_SWORD))
                .addItem(stack(Items.BOW))
                .addItem(stack(Items.ARROW, 32))
                .addItem(stack(Items.COOKED_BEEF, 8))
                .armor(
                        stack(Items.DIAMOND_HELMET),
                        stack(Items.DIAMOND_CHESTPLATE),
                        stack(Items.DIAMOND_LEGGINGS),
                        stack(Items.DIAMOND_BOOTS)
                )
                .build();
    }

    /** 内置「铁套PVP」：铁套 + 剑/斧/盾/弓 + 金胡萝卜 + 岩浆/水 + 耐久III钓鱼竿。 */
    private static Kit buildIronPvpKit() {
        ItemStack rod = new ItemStack(Items.FISHING_ROD);
        if (enchantmentRegistry != null) {
            RegistryEntry<Enchantment> unbreaking = enchantmentRegistry.getEntry(Enchantments.UNBREAKING).orElse(null);
            if (unbreaking != null) {
                rod.addEnchantment(unbreaking, 3);
            }
        }

        return new Kit.Builder("iron_pvp", KitType.CUSTOM)
                .displayName("铁套PVP")
                .addItem(stack(Items.IRON_SWORD))
                .addItem(stack(Items.IRON_AXE))
                .addItem(stack(Items.BOW))
                .addItem(stack(Items.ARROW, 64))
                .addItem(stack(Items.GOLDEN_CARROT, 64))
                .addItem(rod)
                // 桶不能堆叠，岩浆/水各 3 个分开放入背包
                .addBackpackItem(stack(Items.LAVA_BUCKET))
                .addBackpackItem(stack(Items.LAVA_BUCKET))
                .addBackpackItem(stack(Items.LAVA_BUCKET))
                .addBackpackItem(stack(Items.WATER_BUCKET))
                .addBackpackItem(stack(Items.WATER_BUCKET))
                .addBackpackItem(stack(Items.WATER_BUCKET))
                .offhand(stack(Items.SHIELD))
                .armor(
                        stack(Items.IRON_HELMET),
                        stack(Items.IRON_CHESTPLATE),
                        stack(Items.IRON_LEGGINGS),
                        stack(Items.IRON_BOOTS)
                )
                .addEffect(new StatusEffectInstance(StatusEffects.SPEED, 6000, 0))
                .food(20, 5f)
                .gamemode(GameMode.ADVENTURE)
                .build();
    }

    /** 相扑专用套件：击退 I 棍 + 末影珍珠一组 + 速度 II。 */
    private static Kit buildSumoKit() {
        ItemStack stick = new ItemStack(Items.STICK);
        if (enchantmentRegistry != null) {
            RegistryEntry<Enchantment> knockback = enchantmentRegistry.getEntry(Enchantments.KNOCKBACK).orElse(null);
            if (knockback != null) {
                stick.addEnchantment(knockback, 1);
            }
        }
        return new Kit.Builder("sumo", KitType.CUSTOM)
                .displayName("相扑")
                .addItem(stick)
                .addItem(stack(Items.ENDER_PEARL, 16))
                .addEffect(new StatusEffectInstance(StatusEffects.SPEED, 6000, 1))
                .food(20, 5f)
                .gamemode(GameMode.ADVENTURE)
                .build();
    }

    /** NoDebuff：速度 II + 跳跃 II + 治疗 II 喷溅（塞满一叠）+ 金苹果（经典药水 PvP）。 */
    private static Kit buildNoDebuffKit() {
        ItemStack healing = potion(Items.SPLASH_POTION, Potions.STRONG_HEALING);
        if (!healing.isEmpty()) {
            healing.setCount(16);
        }
        ItemStack speed = potion(Items.POTION, Potions.STRONG_SWIFTNESS);
        ItemStack jump = potion(Items.POTION, Potions.STRONG_LEAPING);

        Kit.Builder builder = new Kit.Builder("no_debuff", KitType.CUSTOM)
                .displayName("NoDebuff")
                .addItem(stack(Items.IRON_SWORD))
                .addItem(stack(Items.GOLDEN_APPLE, 8));
        if (!healing.isEmpty()) {
            builder.addItem(healing);
        }
        if (!speed.isEmpty()) {
            builder.addItem(speed);
        }
        if (!jump.isEmpty()) {
            builder.addItem(jump);
        }
        return builder.food(20, 5f).gamemode(GameMode.ADVENTURE).build();
    }

    /** 创建药水物品（药水注册表未就绪时返回空栈，避免崩溃）。 */
    private static ItemStack potion(Item item, RegistryEntry<Potion> potion) {
        if (potion == null) {
            return ItemStack.EMPTY;
        }
        return PotionContentsComponent.createStack(item, potion);
    }

    /** 金苹果：剑 + 金苹果。 */
    private static Kit buildGappleKit() {
        return new Kit.Builder("gapple", KitType.CUSTOM)
                .displayName("金苹果")
                .addItem(stack(Items.IRON_SWORD))
                .addItem(stack(Items.GOLDEN_APPLE, 8))
                .food(20, 5f)
                .gamemode(GameMode.ADVENTURE)
                .build();
    }

    /** 1.8 经典：钻石剑 + 速度 II + 金苹果（配合 1.8 无冷却模式）。 */
    private static Kit buildLegacy18Kit() {
        return new Kit.Builder("legacy_1_8", KitType.CUSTOM)
                .displayName("1.8 经典")
                .addItem(stack(Items.DIAMOND_SWORD))
                .addItem(stack(Items.GOLDEN_APPLE, 8))
                .addEffect(new StatusEffectInstance(StatusEffects.SPEED, 6000, 1))
                .food(20, 5f)
                .gamemode(GameMode.ADVENTURE)
                .build();
    }

    /** 斧战：斧 + 盾 + 金苹果。 */
    private static Kit buildAxeKit() {
        return new Kit.Builder("axe", KitType.CUSTOM)
                .displayName("斧战")
                .addItem(stack(Items.IRON_AXE))
                .addItem(stack(Items.GOLDEN_APPLE, 8))
                .offhand(stack(Items.SHIELD))
                .food(20, 5f)
                .gamemode(GameMode.ADVENTURE)
                .build();
    }

    private static Kit buildCustomKit(KitConfig.CustomKitSpec spec) {
        if (spec == null || spec.name == null || spec.name.isBlank()) {
            return null;
        }
        Kit.Builder builder = new Kit.Builder(spec.name.toLowerCase().replace(' ', '_'), KitType.CUSTOM)
                .displayName(spec.name);

        int placed = 0;
        for (KitConfig.ItemSpec itemSpec : spec.items) {
            if (itemSpec == null || itemSpec.id == null) {
                continue;
            }
            Item item = Registries.ITEM.get(Identifier.tryParse(itemSpec.id));
            if (item == Items.AIR) {
                LOGGER.warn("[PvP] 套件 {} 包含无效物品: {}", spec.name, itemSpec.id);
                continue;
            }
            int count = Math.max(1, Math.min(64, itemSpec.count));
            ItemStack stack = new ItemStack(item, count);
            applyEnchantments(stack, itemSpec);
            builder.addItem(stack);
            if (++placed >= 9) {
                break; // 只放入主手与前8格快捷栏
            }
        }

        for (KitConfig.ItemSpec itemSpec : spec.backpack) {
            if (itemSpec == null || itemSpec.id == null) {
                continue;
            }
            Item item = Registries.ITEM.get(Identifier.tryParse(itemSpec.id));
            if (item == Items.AIR) {
                LOGGER.warn("[PvP] 套件 {} 包含无效物品: {}", spec.name, itemSpec.id);
                continue;
            }
            int count = Math.max(1, Math.min(64, itemSpec.count));
            ItemStack stack = new ItemStack(item, count);
            applyEnchantments(stack, itemSpec);
            builder.addBackpackItem(stack);
        }

        if (!spec.armor.isEmpty()) {
            ItemStack[] armor = new ItemStack[4];
            int idx = 0;
            for (KitConfig.ItemSpec itemSpec : spec.armor) {
                if (itemSpec == null || itemSpec.id == null || idx >= 4) {
                    break;
                }
                Item item = Registries.ITEM.get(Identifier.tryParse(itemSpec.id));
                if (item == Items.AIR) {
                    continue;
                }
                armor[idx++] = new ItemStack(item, Math.max(1, Math.min(64, itemSpec.count)));
            }
            builder.armor(armor[0], armor[1], armor[2], armor[3]);
        }

        for (KitConfig.EffectSpec effectSpec : spec.effects) {
            if (effectSpec == null || effectSpec.effect == null) {
                continue;
            }
            RegistryEntry<net.minecraft.entity.effect.StatusEffect> entry = Registries.STATUS_EFFECT
                    .getEntry(RegistryKey.of(RegistryKeys.STATUS_EFFECT, Identifier.tryParse(effectSpec.effect)))
                    .orElse(null);
            if (entry != null) {
                builder.addEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                        entry, Math.max(1, effectSpec.duration), Math.max(0, effectSpec.amplifier)));
            } else {
                LOGGER.warn("[PvP] 套件 {} 包含无效效果: {}", spec.name, effectSpec.effect);
            }
        }

        if (spec.food != null) {
            builder.food(Math.max(0, Math.min(20, spec.food)), spec.saturation == null ? 10f : Math.max(0, spec.saturation));
        }
        if (spec.gamemode != null) {
            GameMode mode = GameMode.byName(spec.gamemode, GameMode.ADVENTURE);
            builder.gamemode(mode);
        }

        return builder.build();
    }

    /** 应用物品配置中的附魔（{"components":{"enchantments":{"levels":{...}}}}）。 */
    private static void applyEnchantments(ItemStack stack, KitConfig.ItemSpec itemSpec) {
        if (itemSpec == null || itemSpec.components == null || itemSpec.components.enchantments == null
                || itemSpec.components.enchantments.levels == null) {
            return;
        }
        for (Map.Entry<String, Integer> entry : itemSpec.components.enchantments.levels.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            RegistryKey<Enchantment> key = RegistryKey.of(RegistryKeys.ENCHANTMENT, Identifier.tryParse(entry.getKey()));
            RegistryEntry<Enchantment> enchantment = enchantmentRegistry == null ? null : enchantmentRegistry.getEntry(key).orElse(null);
            if (enchantment != null) {
                stack.addEnchantment(enchantment, entry.getValue());
            } else {
                LOGGER.warn("[PvP] 忽略无效附魔: {}", entry.getKey());
            }
        }
    }

    private static ItemStack stack(Item item) {
        return new ItemStack(item);
    }

    private static ItemStack stack(Item item, int count) {
        return new ItemStack(item, count);
    }
}
