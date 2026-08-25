package com.example.pvp.kit;

import com.example.pvp.config.KitConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 套件管理器：内置三种套件 + 从 kits.json 加载的自定义套件，支持热重载。
 */
public final class KitManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final List<Kit> KITS = new ArrayList<>();

    private KitManager() {
    }

    public static void reload() {
        KITS.clear();
        KITS.add(buildSwordKit());
        KITS.add(buildBowKit());
        KITS.add(buildFullGearKit());

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
            builder.addItem(new ItemStack(item, count));
            if (++placed >= 9) {
                break; // 只放入主手与前8格快捷栏
            }
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

    private static ItemStack stack(Item item) {
        return new ItemStack(item);
    }

    private static ItemStack stack(Item item, int count) {
        return new ItemStack(item, count);
    }
}
