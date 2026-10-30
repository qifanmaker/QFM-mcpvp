package com.example.pvp.arena.party;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.UnbreakableComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 原包 409 / 428 的现代战斗规则，移除对全服 damage_dealt、kills 与 team 的依赖。
 * 依据 meat_slap{,_start,_tick,_tag} 和 oitq{,_rules,_start,_tick,_end}：
 * 传肉实际阈值是受伤统计 65（6.5 点），持肉 ticks 越少越好；OITQ 是 90 秒击杀赛，
 * 每次击杀额外给一支箭、死亡只等下一 tick 重置并随机复活，而非淘汰或凭文案等待数秒。
 */
public final class PartyCombatRound implements PartyRound {
    private static final double[][] MEAT_STARTS = {
            {-108,38,-292},{-108,39,-276},{-120,38,-277},{-121,39,-290},{-104,38,-299},
            {-101,38,-266},{-113,38,-272},{-127,38,-295},{-114,38,-262},{-96,41,-282}
    };
    private static final double[][] MEAT_RETURNS = {
            {-113,38,-245},{-119,39,-250},{-104,40,-250},{-113,38,-256},{-123,38,-260},
            {-125,38,-271},{-114,38,-273},{-99,38,-262},{-97,40,-279},{-106,38,-286},
            {-113,34,-280},{-116,32,-294},{-122,29,-303},{-112,38.5,-284},{-134,39,-287},
            {-109,38,-310},{-105,37,-296}
    };
    private static final double[][] SHOT_STARTS = {
            {112,21,-41},{125,23,-29},{138,21,-41},{125,23,-53},{137,21,-52},
            {113,21,-52},{113,21,-30},{137,21,-30},{125,19,-30},{125,19,-52}
    };
    private static final float[] SHOT_YAWS = {-90,180,90,0,45,-45,-135,135,180,0};
    private static final double[][] SHOT_RETURNS = {
            {125,19,-53},{125,19,-29},{125,19,-41},{116,19,-41},{134,19,-41},
            {130,19,-48},{120,19,-48},{120,19,-34},{130,19,-34},{138,21,-35},
            {138,21,-47},{112,21,-47},{112,21,-35},{120,22,-29},{130,22,-29},
            {130,22,-53},{120,22,-53},{112,21,-41},{125,19,-53},{138,21,-41},
            {125,24,-47},{120,24,-41},{125,24,-35},{130,24,-41}
    };
    private final PartyMinigame game;
    private final Set<UUID> playerIds = new HashSet<>();
    private final Map<UUID, PersistentProjectileEntity> arrows = new HashMap<>();
    private final Map<UUID, Integer> respawning = new HashMap<>();
    private record Hit(UUID attacker, int tick) { }
    private final Map<UUID, Hit> lastHits = new HashMap<>();
    private final Map<UUID, Integer> respawnProtection = new HashMap<>();
    private UUID holder;
    private net.minecraft.world.World world;
    private BlockPos center;
    private boolean finished;

    public PartyCombatRound(PartyMinigame game) {
        if (game != PartyMinigame.MEAT_SLAP && game != PartyMinigame.ONE_SHOT_WONDER) {
            throw new IllegalArgumentException("Not a Party combat game: " + game);
        }
        this.game = game;
    }

    private boolean meat() { return this.game == PartyMinigame.MEAT_SLAP; }

    @Override public void stage(PartyRoundContext context) throws IOException {
        context.sourceAnchor(this.meat() ? new BlockPos(-113,38,-284) : new BlockPos(125,21,-41));
        context.geometry(this.meat() ? "meat_slap" : "one_shot_wonder");
        context.timeLimit(this.meat() ? 2400 : 1800);
        this.world = context.world();
        this.center = context.center();
        context.players().forEach(player -> this.playerIds.add(player.getUuid()));
        for (UUID player : context.playerIds()) {
            context.spawn(player, position(context, (this.meat() ? MEAT_STARTS : SHOT_STARTS)[context.slot(player)]));
        }
    }

    @Override public void start(PartyRoundContext context) {
        for (ServerPlayerEntity player : context.players()) {
            context.score(player.getUuid(), this.meat() ? 2400 : 0);
            if (this.meat()) {
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 2420, 22, false, false));
            } else {
                this.loadWeapons(player);
                float yaw = SHOT_YAWS[context.slot(player.getUuid())];
                player.networkHandler.requestTeleport(player.getX(), player.getY(), player.getZ(), yaw, 0);
            }
        }
        if (this.meat() && !context.players().isEmpty()) {
            this.setHolder(context, context.players().get(context.random().nextInt(context.players().size())));
        }
    }

    @Override public void tick(PartyRoundContext context, int elapsed) {
        for (ServerPlayerEntity player : context.players()) {
            UUID uuid = player.getUuid();
            Integer ready = this.respawning.get(uuid);
            if (ready != null) {
                if (elapsed >= ready) {
                    this.respawning.remove(uuid);
                    player.changeGameMode(GameMode.ADVENTURE);
                    player.setHealth(player.getMaxHealth());
                    // 同一玩家实体复用，不能把上一命箭的 99 伤害无敌帧带到新一命。
                    player.timeUntilRegen = 0;
                    player.hurtTime = 0;
                    player.setAttacker(null);
                    this.lastHits.remove(uuid);
                    // 原版重生创建新 ServerPlayerEntity，自带 60 tick join 保护（摔落例外）。
                    this.respawnProtection.put(uuid, elapsed + 60);
                    context.teleport(player, position(context, SHOT_RETURNS[context.random().nextInt(SHOT_RETURNS.length)]));
                    this.loadWeapons(player);
                }
                continue;
            }
            if (this.meat()) {
                if (uuid.equals(this.holder)) {
                    context.addScore(uuid, -1);
                    player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 21, 3, false, false));
                    if (player.getInventory().count(Items.PORKCHOP) == 0) player.getInventory().setStack(0, this.meatStack(player));
                } else {
                    player.removeStatusEffect(StatusEffects.SPEED);
                    for (int slot = 0; slot < player.getInventory().size(); slot++) {
                        if (player.getInventory().getStack(slot).isOf(Items.PORKCHOP)) player.getInventory().setStack(slot, ItemStack.EMPTY);
                    }
                }
                if (!inside(context, player, -139,24,-316,-90,65,-241)) this.death(context, player);
            } else {
                if (player.getInventory().count(Items.IRON_SWORD) == 0) player.getInventory().setStack(0, weapon(Items.IRON_SWORD.getDefaultStack()));
                if (player.getInventory().count(Items.BOW) == 0) player.getInventory().setStack(1, weapon(Items.BOW.getDefaultStack()));
                if (!inside(context, player,108,19,-57,142,42,-25)) {
                    context.teleport(player, position(context, SHOT_RETURNS[context.random().nextInt(SHOT_RETURNS.length)]));
                    this.resetArrows(player);
                }
            }
        }
        this.arrows.values().removeIf(arrow -> {
            boolean remove = arrow.isRemoved() || arrow.age > 400 || arrow.shake > 0
                    || Math.abs(arrow.getX()-context.center().getX()) > 168 || Math.abs(arrow.getZ()-context.center().getZ()) > 168;
            if (remove && !arrow.isRemoved()) arrow.discard();
            return remove;
        });
        if (elapsed % 20 == 0) {
            Box box = new Box(context.center()).expand(80,80,80);
            for (ItemEntity item : context.world().getEntitiesByClass(ItemEntity.class, box, entity -> {
                ItemStack stack = entity.getStack();
                return this.meat() ? stack.isOf(Items.PORKCHOP) : stack.isOf(Items.BOW) || stack.isOf(Items.ARROW) || stack.isOf(Items.IRON_SWORD);
            })) item.discard();
        }
    }

    @Override public boolean allowsNaturalRegeneration() { return this.meat(); }
    @Override public boolean playerCombatEnabled() { return !this.finished; }

    @Override public boolean allowsDamage(PartyRoundContext context, LivingEntity victim, DamageSource source) {
        if (!(victim instanceof ServerPlayerEntity player) || this.respawning.containsKey(player.getUuid())) return false;
        if (!this.meat() && context.elapsed() < this.respawnProtection.getOrDefault(player.getUuid(), 0)
                && !source.isOf(DamageTypes.FALL) && !source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        if (source.getAttacker() instanceof ServerPlayerEntity attacker) {
            return attacker != player && context.players().contains(attacker) && !this.respawning.containsKey(attacker.getUuid());
        }
        return !this.meat() || source.isOf(DamageTypes.OUT_OF_WORLD);
    }

    @Override public void damaged(PartyRoundContext context, LivingEntity victim, DamageSource source, float taken) {
        if (!this.meat() && taken > 0 && victim instanceof ServerPlayerEntity target
                && !this.respawning.containsKey(target.getUuid())
                && source.getAttacker() instanceof ServerPlayerEntity shooter && shooter != target
                && context.players().contains(shooter)) {
            this.lastHits.put(target.getUuid(), new Hit(shooter.getUuid(), context.elapsed()));
        }
        if (!this.meat() || this.finished || !(victim instanceof ServerPlayerEntity tagged)
                || !(source.getAttacker() instanceof ServerPlayerEntity attacker)
                || !attacker.getUuid().equals(this.holder) || attacker == tagged
                || !attacker.getMainHandStack().isOf(Items.PORKCHOP)) return;
        // 与原 damage_taken 的十分之一心统计一致；轻点即使命中也不能交接。
        int statistic = Math.round(taken * 10);
        // 源 tick 在统计 >=60 时调用 tag：先远距离重定位，>=65 才真正换持肉者。
        if (statistic >= 60 && context.elapsed() >= 5) {
            List<Vec3d> candidates = new ArrayList<>();
            for (double[] marker : MEAT_RETURNS) {
                Vec3d point = position(context, marker);
                if (point.squaredDistanceTo(tagged.getPos()) >= 24*24) candidates.add(point);
            }
            if (!candidates.isEmpty()) context.teleport(attacker, candidates.get(context.random().nextInt(candidates.size())));
        }
        if (statistic < 65) {
            attacker.sendMessage(Text.literal("§c必须蓄满力命中才能传出肉！"), true);
            return;
        }
        this.setHolder(context, tagged);
    }

    @Override public boolean ownsEntity(Entity entity) {
        if (this.arrows.containsKey(entity.getUuid())) return true;
        return !this.meat() && !this.finished && entity instanceof ArrowEntity arrow && arrow.getOwner() != null
                && this.playerIds.contains(arrow.getOwner().getUuid()) && entity.getWorld() == this.world
                && Math.abs(entity.getX() - this.center.getX()) <= 168
                && Math.abs(entity.getZ() - this.center.getZ()) <= 168;
    }

    @Override public void entityLoaded(PartyRoundContext context, Entity entity) {
        if (this.ownsEntity(entity) && entity instanceof PersistentProjectileEntity arrow) {
            arrow.setDamage(99);
            arrow.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;
            this.arrows.put(arrow.getUuid(), arrow);
        }
    }

    @Override public void death(PartyRoundContext context, ServerPlayerEntity player) { this.death(context, player, null); }

    @Override public void death(PartyRoundContext context, ServerPlayerEntity player, DamageSource source) {
        if (this.finished || this.respawning.containsKey(player.getUuid())) return;
        player.setHealth(player.getMaxHealth());
        player.setFireTicks(0);
        player.fallDistance = 0;
        if (this.meat()) {
            context.teleport(player, position(context, MEAT_STARTS[context.slot(player.getUuid())]));
            return;
        }
        // 拦截死亡后原实体的 attackingPlayer 会跨命残留；按本条生命的伤害记录计算坠落归属。
        Entity adversary = source == null ? null : source.getAttacker();
        Hit hit = this.lastHits.remove(player.getUuid());
        if (adversary == null && hit != null && context.elapsed() - hit.tick() <= 100) {
            adversary = context.players().stream().filter(p -> p.getUuid().equals(hit.attacker())).findFirst().orElse(null);
        }
        if (adversary instanceof ServerPlayerEntity killer && killer != player && context.players().contains(killer)) {
            context.addScore(killer.getUuid(), 1);
            killer.getInventory().insertStack(new ItemStack(Items.ARROW));
            killer.currentScreenHandler.sendContentUpdates();
        }
        player.getInventory().clear();
        player.changeGameMode(GameMode.SPECTATOR);
        this.respawning.put(player.getUuid(), context.elapsed()+1);
    }

    @Override public ActionResult useItem(PartyRoundContext context, ServerPlayerEntity player, Hand hand) {
        return !this.meat() && player.getStackInHand(hand).isOf(Items.BOW)
                && !this.respawning.containsKey(player.getUuid()) ? ActionResult.PASS : ActionResult.FAIL;
    }

    @Override public ActionResult useBlock(PartyRoundContext context, ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        // 原 OITQ 场景内没有可交互容器/门；PASS 让指向墙面时也能进入原版拉弓流程。
        return this.useItem(context, player, hand);
    }

    @Override public void leave(PartyRoundContext context, ServerPlayerEntity player) {
        this.respawning.remove(player.getUuid());
        this.playerIds.remove(player.getUuid());
        this.lastHits.remove(player.getUuid());
        this.respawnProtection.remove(player.getUuid());
        player.removeStatusEffect(StatusEffects.SPEED);
        player.removeStatusEffect(StatusEffects.REGENERATION);
        if (this.meat() && !this.finished && player.getUuid().equals(this.holder)) {
            List<ServerPlayerEntity> next = context.players().stream().filter(p -> p != player).toList();
            this.holder = null;
            if (!next.isEmpty()) this.setHolder(context, next.get(context.random().nextInt(next.size())));
        }
    }

    @Override public void finish(PartyRoundContext context) { this.cleanup(context); }
    @Override public void close(PartyRoundContext context) { this.cleanup(context); }

    private void cleanup(PartyRoundContext context) {
        this.finished = true;
        this.arrows.values().forEach(Entity::discard);
        this.arrows.clear();
        this.respawning.clear();
        this.lastHits.clear();
        this.respawnProtection.clear();
        for (ServerPlayerEntity player : context.players()) {
            player.removeStatusEffect(StatusEffects.SPEED);
            player.removeStatusEffect(StatusEffects.REGENERATION);
        }
    }

    @Override public String scoreDescription(PartyRoundContext context, UUID player) {
        return this.meat() ? "持肉 " + String.format(java.util.Locale.ROOT,"%.2f",(2400-context.score(player))/20.0) + " 秒"
                : context.score(player) + " 次击杀";
    }

    private void setHolder(PartyRoundContext context, ServerPlayerEntity player) {
        this.holder = player.getUuid();
        for (ServerPlayerEntity other : context.players()) {
            other.getInventory().clear();
            if (other != player) other.removeStatusEffect(StatusEffects.SPEED);
        }
        player.getInventory().setStack(0, this.meatStack(player));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED,21,3,false,false));
        context.announce("§c" + player.getName().getString() + " §e现在持肉！蓄满力命中另一名玩家传出。");
    }

    private ItemStack meatStack(ServerPlayerEntity player) {
        ItemStack stack = new ItemStack(Items.PORKCHOP);
        stack.set(DataComponentTypes.CUSTOM_NAME,Text.literal("§cThe Meat"));
        var registry = player.getRegistryManager().get(RegistryKeys.ENCHANTMENT);
        registry.getEntry(Enchantments.SHARPNESS).ifPresent(entry -> stack.addEnchantment(entry,10));
        return stack;
    }

    private void loadWeapons(ServerPlayerEntity player) {
        player.getInventory().clear();
        player.getInventory().setStack(0,weapon(Items.IRON_SWORD.getDefaultStack()));
        player.getInventory().setStack(1,weapon(Items.BOW.getDefaultStack()));
        player.getInventory().setStack(2,new ItemStack(Items.ARROW));
        player.currentScreenHandler.sendContentUpdates();
    }

    private void resetArrows(ServerPlayerEntity player) {
        for (int slot=0; slot<player.getInventory().size(); slot++) {
            if (player.getInventory().getStack(slot).isOf(Items.ARROW)) player.getInventory().setStack(slot,ItemStack.EMPTY);
        }
        player.getInventory().insertStack(new ItemStack(Items.ARROW));
        player.currentScreenHandler.sendContentUpdates();
    }

    private static ItemStack weapon(ItemStack stack) {
        stack.set(DataComponentTypes.UNBREAKABLE,new UnbreakableComponent(false));
        return stack;
    }

    private static Vec3d position(PartyRoundContext context, double[] point) { return context.source(point[0]+0.5,point[1],point[2]+0.5); }

    private static boolean inside(PartyRoundContext context, Entity entity, int x1,int y1,int z1,int x2,int y2,int z2) {
        Vec3d low=context.source(x1,y1,z1),high=context.source(x2+1,y2+1,z2+1);
        return entity.getWorld()==context.world() && entity.getX()>=low.x && entity.getX()<high.x
                && entity.getY()>=low.y && entity.getY()<high.y && entity.getZ()>=low.z && entity.getZ()<high.z;
    }
}
