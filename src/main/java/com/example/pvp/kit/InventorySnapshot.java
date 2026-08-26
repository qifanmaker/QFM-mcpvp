package com.example.pvp.kit;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 保存并恢复玩家在战斗前的完整状态：背包/护甲/副手/经验/生命/饥饿/效果/游戏模式/位置。
 */
public final class InventorySnapshot {
    private static final int MAIN_SIZE = 36;
    private static final int ARMOR_SIZE = 4;

    private final ItemStack[] main = new ItemStack[MAIN_SIZE];
    private final ItemStack[] armor = new ItemStack[ARMOR_SIZE];
    private final ItemStack offhand;
    private final int selectedSlot;
    private final int experienceLevel;
    private final float experienceProgress;
    private final float health;
    private final int food;
    private final float saturation;
    private final float absorption;
    private final List<StatusEffectInstance> effects;
    private final GameMode gamemode;
    private final RegistryKey<World> dimension;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final int fireTicks;
    private final boolean allowFlying;
    private final boolean flying;
    private final boolean invisible;

    private InventorySnapshot(ServerPlayerEntity player) {
        var inventory = player.getInventory();
        for (int i = 0; i < MAIN_SIZE; i++) {
            this.main[i] = inventory.main.get(i).copy();
        }
        for (int i = 0; i < ARMOR_SIZE; i++) {
            this.armor[i] = inventory.armor.get(i).copy();
        }
        this.offhand = inventory.offHand.get(0).copy();
        this.selectedSlot = inventory.selectedSlot;

        this.experienceLevel = player.experienceLevel;
        this.experienceProgress = player.experienceProgress;
        this.health = player.getHealth();
        this.food = player.getHungerManager().getFoodLevel();
        this.saturation = player.getHungerManager().getSaturationLevel();
        this.absorption = player.getAbsorptionAmount();

        this.effects = new ArrayList<>();
        for (StatusEffectInstance effect : player.getStatusEffects()) {
            this.effects.add(new StatusEffectInstance(effect));
        }

        this.gamemode = player.interactionManager.getGameMode();
        this.dimension = player.getWorld().getRegistryKey();
        this.x = player.getX();
        this.y = player.getY();
        this.z = player.getZ();
        this.yaw = player.getYaw();
        this.pitch = player.getPitch();
        this.fireTicks = player.getFireTicks();
        this.allowFlying = player.getAbilities().allowFlying;
        this.flying = player.getAbilities().flying;
        this.invisible = player.isInvisible();
    }

    public static InventorySnapshot capture(ServerPlayerEntity player) {
        return new InventorySnapshot(player);
    }

    public RegistryKey<World> getDimension() {
        return this.dimension;
    }

    public void restore(ServerPlayerEntity player) {
        // 先恢复游戏模式与位置，再恢复生命/物品
        player.changeGameMode(this.gamemode);
        ServerWorld target = player.getServer().getWorld(this.dimension);
        if (target != null) {
            player.teleport(target, this.x, this.y, this.z, this.yaw, this.pitch);
        }

        var inventory = player.getInventory();
        inventory.clear();
        for (int i = 0; i < MAIN_SIZE; i++) {
            inventory.main.set(i, this.main[i]);
        }
        for (int i = 0; i < ARMOR_SIZE; i++) {
            inventory.armor.set(i, this.armor[i]);
        }
        inventory.offHand.set(0, this.offhand);
        inventory.selectedSlot = this.selectedSlot;

        player.setHealth(Math.max(1f, this.health));
        player.getHungerManager().setFoodLevel(this.food);
        player.getHungerManager().setSaturationLevel(this.saturation);
        player.setAbsorptionAmount(this.absorption);

        player.setExperienceLevel(this.experienceLevel);
        player.setExperiencePoints((int) (this.experienceProgress * player.getNextLevelExperience()));

        player.clearStatusEffects();
        for (StatusEffectInstance effect : this.effects) {
            player.addStatusEffect(new StatusEffectInstance(effect));
        }

        player.setFireTicks(Math.min(this.fireTicks, 20));
        player.fallDistance = 0;
        player.setInvulnerable(false);
        player.setNoGravity(false);
        player.setInvisible(this.invisible); // 幽灵死亡时隐身，赛后还原
        // 恢复飞行能力（幽灵死亡时会开启飞行，赛后必须还原）
        player.getAbilities().allowFlying = this.allowFlying;
        player.getAbilities().flying = this.flying;
        player.sendAbilitiesUpdate();
        player.currentScreenHandler.sendContentUpdates();
    }
}
