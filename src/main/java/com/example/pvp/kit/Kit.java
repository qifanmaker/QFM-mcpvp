package com.example.pvp.kit;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.world.GameMode;

import java.util.List;

/**
 * 一套装备方案：物品、护甲、副手、效果、饥饿与游戏模式。
 */
public final class Kit {
    private final String id;
    private final KitType type;
    private final String displayName;
    private final List<ItemStack> inventory; // 快捷栏（0-8）
    private final List<ItemStack> backpack;  // 主背包（9-35）
    private final ItemStack[] armor; // [0]头盔 [1]胸甲 [2]护腿 [3]靴子
    private final ItemStack offhand;
    private final List<StatusEffectInstance> effects;
    private final int food;
    private final float saturation;
    private final GameMode gamemode;

    private Kit(Builder builder) {
        this.id = builder.id;
        this.type = builder.type;
        this.displayName = builder.displayName;
        this.inventory = List.copyOf(builder.inventory);
        this.backpack = List.copyOf(builder.backpack);
        this.armor = builder.armor.clone();
        this.offhand = builder.offhand;
        this.effects = List.copyOf(builder.effects);
        this.food = builder.food;
        this.saturation = builder.saturation;
        this.gamemode = builder.gamemode;
    }

    public String getId() {
        return this.id;
    }

    public KitType getType() {
        return this.type;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public List<ItemStack> getInventory() {
        return this.inventory;
    }

    public List<ItemStack> getBackpack() {
        return this.backpack;
    }

    public ItemStack[] getArmor() {
        return this.armor;
    }

    public ItemStack getOffhand() {
        return this.offhand;
    }

    public List<StatusEffectInstance> getEffects() {
        return this.effects;
    }

    public int getFood() {
        return this.food;
    }

    public float getSaturation() {
        return this.saturation;
    }

    public GameMode getGamemode() {
        return this.gamemode;
    }

    public static class Builder {
        private final String id;
        private final KitType type;
        private String displayName;
        private final List<ItemStack> inventory = new java.util.ArrayList<>();
        private final List<ItemStack> backpack = new java.util.ArrayList<>();
        private final ItemStack[] armor = new ItemStack[4];
        private ItemStack offhand = ItemStack.EMPTY;
        private final List<StatusEffectInstance> effects = new java.util.ArrayList<>();
        private int food = 20;
        private float saturation = 5f; // 原版初始饱和度，让饥饿在战斗中正常消耗
        private GameMode gamemode = GameMode.ADVENTURE;

        public Builder(String id, KitType type) {
            this.id = id;
            this.type = type;
            this.displayName = type.getDisplayName();
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder addItem(ItemStack stack) {
            this.inventory.add(stack);
            return this;
        }

        public Builder addBackpackItem(ItemStack stack) {
            this.backpack.add(stack);
            return this;
        }

        public Builder armor(ItemStack helmet, ItemStack chest, ItemStack legs, ItemStack boots) {
            this.armor[0] = helmet;
            this.armor[1] = chest;
            this.armor[2] = legs;
            this.armor[3] = boots;
            return this;
        }

        public Builder offhand(ItemStack stack) {
            this.offhand = stack;
            return this;
        }

        public Builder addEffect(StatusEffectInstance effect) {
            this.effects.add(effect);
            return this;
        }

        public Builder food(int food, float saturation) {
            this.food = food;
            this.saturation = saturation;
            return this;
        }

        public Builder gamemode(GameMode gamemode) {
            this.gamemode = gamemode;
            return this;
        }

        public Kit build() {
            return new Kit(this);
        }
    }
}
