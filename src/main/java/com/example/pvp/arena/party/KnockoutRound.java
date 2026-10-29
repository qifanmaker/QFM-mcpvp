package com.example.pvp.arena.party;

import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 原版 406 Knockout；依据 knockout{,2,3,4,_respawn,_tick,_end} 的三命熔岩击退赛。
 * 修改原因：原包以全服 kills/lives 记分板和原生重生点实现规则，不能直接执行在同服其他比赛上；
 * 此处保留原生玩家攻击、击退 I/II、每 tick 再生 44、300 tick 无伤害发光，以及随机逐人复活。
 * 自查：击杀归属直接采用原生 playerKillCount 所用的 getPrimeAdversary；复用实体时清除跨命攻击者和无敌帧；
 * 熔岩检测为脚部方块而非笼统越界，普通摔落伤害保留；所有状态和掉落物均限定本回合。
 */
public final class KnockoutRound implements PartyRound {
    private static final String STICK_TOKEN = "pvp_party_knockout";
    private static final double[][] STARTS = {
            {-414,42,-109},{-430,45,-124},{-443,43,-109},{-426,42,-96},{-429,45,-110},
            {-439,44,-119},{-418,42,-99},{-416,42,-117},{-439,43,-101},{-428,42,-102}
    };
    private static final double[][] RETURNS = {
            {-414,43,-103},{-414,42,-113},{-427,46,-124},{-438,44,-119},{-442,43,-108},
            {-438,43,-100},{-425,43,-96},{-427,45,-109},{-431,45,-110}
    };
    private final String token = UUID.randomUUID().toString();
    private final Map<UUID, Integer> lives = new HashMap<>();
    private final Map<UUID, Integer> inactive = new HashMap<>();
    private final Map<UUID, Integer> respawning = new HashMap<>();
    private final Map<UUID, Integer> protection = new HashMap<>();
    private final Set<UUID> activity = new HashSet<>();
    private final Set<UUID> upgraded = new HashSet<>();
    private final Map<UUID, ItemStack[]> sticks = new HashMap<>();
    private ServerWorld world;
    private Box bounds;
    private int eliminated;
    private boolean finished;

    @Override public void stage(PartyRoundContext context) throws IOException {
        context.sourceAnchor(new BlockPos(-429,45,-110));
        context.geometry("knockout");
        PartyMapBlueprint upper = PartyMapBlueprint.load("knockout_upper");
        BlockPos translation = context.sourceBlock(-31,11,48);
        upper.forEachPosition(translation, point -> context.stage(point, Blocks.AIR.getDefaultState()));
        upper.forEachTranslated(translation, context::stage);
        this.world = context.world();
        this.bounds = new Box(context.source(-467,33,-149), context.source(-389,63,-69));
        context.timeLimit(0);
        int slot = 0;
        for (ServerPlayerEntity player : context.players()) {
            UUID uuid = player.getUuid();
            this.lives.put(uuid, 3);
            this.inactive.put(uuid, 0);
            context.spawn(uuid, position(context, STARTS[slot++]));
        }
    }

    @Override public void start(PartyRoundContext context) {
        for (ServerPlayerEntity player : context.alive()) {
            context.score(player.getUuid(), context.initialPlayerCount());
            this.resetLife(player);
            this.sticks.put(player.getUuid(), new ItemStack[]{this.stick(player, 1), this.stick(player, 2)});
            this.giveStick(player);
        }
        context.announce("§d击退擂台：每人 3 条命，击落其他玩家！首次击杀升级为击退 II 木棍。"
                + "\n§e最后存活者获胜；15 秒没有造成或受到伤害会发光。");
    }

    @Override public void tick(PartyRoundContext context, int elapsed) {
        if (this.finished) return;
        // 原版每 tick 只从重生点随机选择一人，不把多人同刻死亡压成同一个淘汰名次。
        List<ServerPlayerEntity> ready = context.alive().stream()
                .filter(p -> this.respawning.getOrDefault(p.getUuid(), Integer.MAX_VALUE) <= elapsed).toList();
        if (!ready.isEmpty()) this.respawn(context, ready.get(context.random().nextInt(ready.size())), elapsed);
        for (ServerPlayerEntity player : context.alive()) {
            UUID uuid = player.getUuid();
            if (this.respawning.containsKey(uuid)) continue;
            if (this.world.getBlockState(player.getBlockPos()).isOf(Blocks.LAVA)) {
                this.death(context, player, this.world.getDamageSources().genericKill());
                continue;
            }
            this.giveStick(player);
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION,20,44,false,false));
            int idle = this.activity.remove(uuid) ? 0 : this.inactive.merge(uuid, 1, Integer::sum);
            if (idle == 0) this.inactive.put(uuid, 0);
            if (idle >= 300 && !player.hasStatusEffect(StatusEffects.GLOWING)) {
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING,-1,0,false,false));
            }
        }
        Vec3d spectator = context.source(-427.5,45,-109.5);
        for (ServerPlayerEntity player : context.players()) {
            if (context.isOut(player.getUuid()) && player.squaredDistanceTo(spectator) >= 2500) {
                context.teleport(player, spectator);
            }
        }
        // A dropped weapon remains an ordinary native item, but only this round's tagged copies are removed.
        for (ItemEntity item : this.world.getEntitiesByClass(ItemEntity.class, this.bounds, this::ownsEntity)) item.discard();
        if (context.alive().size() <= 1) context.finish();
    }

    @Override public boolean playerCombatEnabled() { return !this.finished; }

    @Override public boolean allowsDamage(PartyRoundContext context, LivingEntity victim, DamageSource source) {
        if (this.finished || !(victim instanceof ServerPlayerEntity player) || !this.active(context, player)) return false;
        if (context.elapsed() < this.protection.getOrDefault(player.getUuid(), 0)
                && !source.isOf(DamageTypes.FALL) && !source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        if (source.getAttacker() instanceof ServerPlayerEntity attacker) {
            return attacker != player && this.active(context, attacker);
        }
        return source.getAttacker() == null;
    }

    @Override public void damaged(PartyRoundContext context, LivingEntity victim, DamageSource source, float taken) {
        if (this.finished || !(victim instanceof ServerPlayerEntity target) || !this.active(context, target)) return;
        if (Math.round(taken * 10) >= 1) this.activity(target);
        if (source.getAttacker() instanceof ServerPlayerEntity attacker && attacker != target && this.active(context, attacker)) {
            if (Math.round(taken * 10) >= 1) this.activity(attacker);
        }
    }

    @Override public void death(PartyRoundContext context, ServerPlayerEntity player) { this.death(context, player, null); }

    @Override public void death(PartyRoundContext context, ServerPlayerEntity player, DamageSource source) {
        if (this.finished || !this.active(context, player)) return;
        // 与 ServerPlayerEntity.onDeath 的 playerKillCount 完全相同，包括熔岩/坠落、攻击者优先级和
        // 原生 attackingPlayer / attacker 各自的过期时机。resetLife 同时清两种引用，防止跨命归属。
        Entity adversary = player.getPrimeAdversary();
        if (adversary instanceof ServerPlayerEntity killer && killer != player && this.lives.containsKey(killer.getUuid())
                && context.players().contains(killer) && this.upgraded.add(killer.getUuid())) {
            for (int slot=0; slot<killer.getInventory().size(); slot++) {
                if (killer.getInventory().getStack(slot).isOf(Items.STICK)) killer.getInventory().setStack(slot,ItemStack.EMPTY);
            }
            if (this.lives.getOrDefault(killer.getUuid(),0) > 0) this.giveStick(killer);
            context.announce("§d" + killer.getName().getString() + " §e已获得击退 II 木棍！");
        }
        this.resetLife(player);
        player.getInventory().clear();
        player.changeGameMode(GameMode.SPECTATOR);
        context.teleport(player, context.source(-395.5,38,-79.5));
        this.respawning.put(player.getUuid(), context.elapsed()+1);
    }

    private void respawn(PartyRoundContext context, ServerPlayerEntity player, int elapsed) {
        UUID uuid = player.getUuid();
        this.respawning.remove(uuid);
        int remaining = this.lives.merge(uuid, -1, Integer::sum);
        context.teleport(player, position(context, RETURNS[context.random().nextInt(RETURNS.length)]));
        if (remaining == 0) {
            context.score(uuid, ++this.eliminated);
            player.removeStatusEffect(StatusEffects.REGENERATION);
            player.removeStatusEffect(StatusEffects.GLOWING);
            context.eliminate(player, "§c三条命已用完，正在观看本场比赛。");
            return;
        }
        player.changeGameMode(GameMode.ADVENTURE);
        this.resetLife(player);
        this.protection.put(uuid, elapsed+60);
        this.giveStick(player);
        player.sendMessage(Text.literal("§e剩余生命：§c" + remaining), true);
    }

    private boolean active(PartyRoundContext context, ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        return player.getWorld() == this.world && this.lives.getOrDefault(uuid,0) > 0
                && !context.isOut(uuid) && !this.respawning.containsKey(uuid);
    }

    private void resetLife(ServerPlayerEntity player) {
        player.setHealth(player.getMaxHealth());
        player.setFireTicks(0);
        player.fallDistance = 0;
        player.timeUntilRegen = 0;
        player.hurtTime = 0;
        player.setAttacker(null);
        player.setAttacking(null);
    }

    private void activity(ServerPlayerEntity player) {
        this.inactive.put(player.getUuid(), 0);
        this.activity.add(player.getUuid());
        player.removeStatusEffect(StatusEffects.GLOWING);
    }

    private ItemStack stick(ServerPlayerEntity player, int level) {
        ItemStack stack = new ItemStack(Items.STICK);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§d§lKnockout Stick"));
        NbtCompound tag = new NbtCompound();
        tag.putString(STICK_TOKEN, this.token);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        player.getRegistryManager().get(RegistryKeys.ENCHANTMENT).getEntry(Enchantments.KNOCKBACK)
                .ifPresent(enchantment -> stack.addEnchantment(enchantment,level));
        return stack;
    }

    private void giveStick(ServerPlayerEntity player) {
        ItemStack[] weapons = this.sticks.get(player.getUuid());
        if (weapons == null) return;
        ItemStack weapon = weapons[this.upgraded.contains(player.getUuid()) ? 1 : 0];
        if (!ItemStack.areItemsAndComponentsEqual(player.getInventory().getStack(0), weapon)
                || player.getInventory().getStack(0).getCount() != 1) {
            player.getInventory().setStack(0, weapon.copy());
            player.currentScreenHandler.sendContentUpdates();
        }
    }

    @Override public boolean ownsEntity(Entity entity) {
        // 标签已唯一绑定本回合，不能再用场景高度裁掉从上层平台抛出的木棍，否则离场时会残留。
        if (this.finished || entity.getWorld() != this.world || !(entity instanceof ItemEntity item)) return false;
        NbtComponent data = item.getStack().get(DataComponentTypes.CUSTOM_DATA);
        return item.getStack().isOf(Items.STICK) && data != null && this.token.equals(data.copyNbt().getString(STICK_TOKEN));
    }

    @Override public void entityLoaded(PartyRoundContext context, Entity entity) { if (this.ownsEntity(entity)) entity.discard(); }

    @Override public void leave(PartyRoundContext context, ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        this.lives.remove(uuid);
        this.inactive.remove(uuid);
        this.respawning.remove(uuid);
        this.protection.remove(uuid);
        this.activity.remove(uuid);
        this.sticks.remove(uuid);
        this.upgraded.remove(uuid);
        this.clearEffects(player);
    }

    @Override public void finish(PartyRoundContext context) { this.cleanup(context); }
    @Override public void close(PartyRoundContext context) { this.cleanup(context); }

    private void cleanup(PartyRoundContext context) {
        if (this.finished) return;
        for (ItemEntity item : this.world.getEntitiesByClass(ItemEntity.class, this.bounds, this::ownsEntity)) item.discard();
        this.finished = true;
        this.respawning.clear();
        this.activity.clear();
        this.protection.clear();
        context.players().forEach(this::clearEffects);
    }

    private void clearEffects(ServerPlayerEntity player) {
        player.removeStatusEffect(StatusEffects.REGENERATION);
        player.removeStatusEffect(StatusEffects.GLOWING);
        player.setAttacker(null);
        player.setAttacking(null);
    }

    @Override public String scoreDescription(PartyRoundContext context, UUID player) {
        return this.lives.getOrDefault(player,0) + " 条命";
    }

    @Override public String status(PartyRoundContext context) { return "三条命 · 存活 " + context.alive().size() + " 人"; }

    private static Vec3d position(PartyRoundContext context, double[] point) {
        return context.source(point[0]+.5,point[1],point[2]+.5);
    }
}
