package com.example.pvp.practice;

import com.example.pvp.kit.Kit;
import com.example.pvp.kit.KitApplicator;
import com.mojang.authlib.GameProfile;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A practice opponent needs the same combat rules as a real player: a monster's
 * attack goal cannot reproduce attack cooldowns, shields, charged bows or kit
 * inventory. This isolated player entity therefore uses vanilla player actions
 * and physics, with delayed observations and imperfect, bounded input instead
 * of directly assigning damage or teleporting to follow its opponent.
 */
public final class PracticeBot {
    private final MinecraftServer server;
    private final ServerWorld world;
    private final ServerPlayerEntity opponent;
    private final BotDifficulty difficulty;
    private final BotPlayer entity;
    private Vec3d observedPos;
    private Vec3d observedVelocity = Vec3d.ZERO;
    private float observedCooldown;
    private boolean observedBlocking;
    private boolean observedRanged;
    private int observeTicks;
    private int actionTicks;
    private int strafeTicks;
    private int strafeDirection = 1;
    private int sprintResetTicks;
    private int jumpTicks;
    private int healRetryTicks;
    private int rodCooldown;
    private int rodTicks;
    private int bucketCooldown;
    private int handChangedAtAge = Integer.MIN_VALUE;
    private int waterTicks;
    private BlockPos waterSource;
    private double yawError;
    private double pitchError;
    private boolean criticalPlanned;
    private boolean spawned;
    private boolean discarded;
    private String stateLabel = "等待开战";

    public PracticeBot(MinecraftServer server, ServerWorld world, ServerPlayerEntity opponent,
                       BotDifficulty difficulty, Kit kit) {
        this.server = server;
        this.world = world;
        this.opponent = opponent;
        this.difficulty = difficulty;
        // Stable per owner: repeated rounds do not create unbounded stat/advancement
        // cache keys, and two players' practice sessions never share an entity UUID.
        UUID uuid = UUID.nameUUIDFromBytes(("pvp-practice-bot:" + opponent.getUuid()).getBytes(StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(uuid, "PvP_Bot_L" + difficulty.getLevel());
        this.entity = new BotPlayer(server, world, profile);
        new BotNetworkHandler(server, this.entity, profile);
        this.entity.setCustomName(Text.literal("人机 · " + difficulty.getDisplayName()));
        this.entity.setCustomNameVisible(true);
        KitApplicator.apply(this.entity, kit);
        this.observeTicks = difficulty.reactionTicks();
    }

    public void spawn(BlockPos pos, float yaw) {
        if (this.spawned || this.discarded) throw new IllegalStateException("Practice bot already spawned or discarded");
        this.entity.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0);
        this.entity.setHeadYaw(yaw);
        this.entity.setBodyYaw(yaw);
        // Player profiles must arrive before the tracked spawn packet or vanilla
        // clients silently ignore the humanoid. Do not add it to PlayerManager:
        // it cannot occupy a real connection, queue, tab-list entry or login slot.
        this.server.getPlayerManager().sendToAll(new PlayerListS2CPacket(EnumSet.of(
                PlayerListS2CPacket.Action.ADD_PLAYER, PlayerListS2CPacket.Action.UPDATE_GAME_MODE,
                PlayerListS2CPacket.Action.UPDATE_DISPLAY_NAME), List.of(this.entity)));
        this.world.onPlayerConnected(this.entity);
        this.spawned = true;
    }

    public static boolean isBot(ServerPlayerEntity player) {
        return player instanceof BotPlayer;
    }

    /** Decisions run on the server thread; the world's entity tick owns physics. */
    public void tick(boolean active) {
        if (!this.spawned || this.discarded || !this.entity.isAlive()) return;
        this.entity.input(0, 0, false);
        if (!active || !this.opponent.isAlive() || this.opponent.getWorld() != this.world) {
            this.entity.clearActiveItem();
            if (this.entity.fishHook != null) this.entity.fishHook.discard();
            this.entity.setSprinting(false);
            this.observedPos = null;
            this.observeTicks = this.difficulty.reactionTicks();
            this.stateLabel = "等待开战";
            return;
        }
        if (this.actionTicks > 0) this.actionTicks--;
        if (this.sprintResetTicks > 0) this.sprintResetTicks--;
        if (this.jumpTicks > 0) this.jumpTicks--;
        if (this.healRetryTicks > 0) this.healRetryTicks--;
        if (this.rodCooldown > 0) this.rodCooldown--;
        if (this.bucketCooldown > 0) this.bucketCooldown--;
        if (this.entity.fishHook != null) this.rodTicks++;
        if (this.waterSource != null) this.waterTicks++;
        if (--this.observeTicks <= 0) observe();
        if (this.observedPos == null) {
            this.stateLabel = "观察对手";
            return;
        }

        double distance = this.entity.getPos().distanceTo(this.observedPos);
        if (tryWater()) return;
        if (this.entity.isUsingItem() && !this.entity.getActiveItem().isOf(Items.BOW)
                && !this.entity.getActiveItem().isOf(Items.SHIELD)) {
            this.stateLabel = this.entity.getActiveItem().isOf(Items.POTION) ? "喝药补给" : "进食恢复";
            face(this.observedPos.add(0, 1.2, 0), false);
            move(distance < 6 ? -1 : 0, 0.2, false);
            return;
        }
        if (this.healRetryTicks == 0 && trySupply(distance)) return;
        if (tryRod(distance)) return;

        int melee = findMelee();
        int bow = find(stack -> stack.getItem() instanceof BowItem && !this.entity.getProjectileType(stack).isEmpty());
        boolean bowFight = bow >= 0 && (melee < 0 || distance > (this.entity.isUsingItem() ? 5.5 : 8.0));
        if (bowFight) {
            fightWithBow(bow, distance);
        } else {
            if (this.entity.isUsingItem() && this.entity.getActiveItem().isOf(Items.BOW)) this.entity.clearActiveItem();
            fightWithMelee(melee, distance);
        }
    }

    private void observe() {
        int period = this.difficulty.reactionTicks();
        this.observeTicks = period;
        // A hidden target is not tracked through walls. The previous visible
        // location remains a search destination, while attacks still need LOS.
        if (!this.entity.canSee(this.opponent)) return;
        Vec3d next = this.opponent.getPos();
        if (this.observedPos != null) {
            Vec3d delta = next.subtract(this.observedPos).multiply(1.0 / period);
            double max = 0.45;
            this.observedVelocity = delta.lengthSquared() > max * max ? delta.normalize().multiply(max) : delta;
        }
        this.observedPos = next;
        this.observedBlocking = this.opponent.isBlocking();
        this.observedRanged = this.opponent.isUsingItem() && this.opponent.getActiveItem().getItem() instanceof BowItem;
        this.observedCooldown = this.opponent.getAttackCooldownProgress(0);
        this.yawError = randomError(this.difficulty.aimErrorDegrees());
        this.pitchError = randomError(this.difficulty.aimErrorDegrees() * 0.6);
        if ((this.strafeTicks -= period) <= 0) {
            this.strafeDirection = this.entity.getRandom().nextBoolean() ? 1 : -1;
            this.strafeTicks = this.difficulty.strafeDurationTicks() + this.entity.getRandom().nextInt(10);
        }
    }

    private void fightWithMelee(int melee, double distance) {
        if (melee >= 0) select(melee);
        face(this.observedPos.add(0, 1.1, 0), false);
        // 武器属性和原版换手冷却在下一个实体 tick 更新；切剑当 tick 攻击可能仍按弓/食物的空手伤害计算。
        // 等待当前武器完全蓄力，不能把 90% 蓄力误当满伤害。
        boolean cooling = this.handChangedAtAge == this.entity.age || this.entity.getAttackCooldownProgress(0.5f) < 1.0f;
        boolean shield = this.difficulty.getLevel() >= 3 && this.entity.getOffHandStack().isOf(Items.SHIELD)
                && !this.entity.getItemCooldownManager().isCoolingDown(Items.SHIELD)
                && !this.observedBlocking && (distance < 3.8 && cooling && this.observedCooldown > 0.75
                || this.observedRanged && distance > 4);
        if (shield) {
            if (!this.entity.isUsingItem()) this.entity.setCurrentHand(Hand.OFF_HAND);
            this.stateLabel = "举盾防守";
        } else {
            if (this.entity.isUsingItem() && this.entity.getActiveItem().isOf(Items.SHIELD)) this.entity.clearActiveItem();
            this.stateLabel = distance > 3 ? "追击接近" : cooling ? "横移控距" : "寻找攻击时机";
        }
        double forward = distance > 2.8 ? 1 : distance < 1.65 ? -0.7 : 0.05;
        if (this.difficulty.getLevel() >= 3 && cooling && distance < 2.7) forward = -0.55;
        double sideways = distance < 6 ? this.strafeDirection * this.difficulty.strafeStrength() : 0;
        boolean sprint = !shield && forward > 0 && this.sprintResetTicks == 0 && !this.criticalPlanned;
        move(forward, sideways, sprint);

        if (!shield && !cooling && this.actionTicks == 0 && this.jumpTicks == 0 && this.entity.isOnGround()
                && distance > 2 && distance < 3.8 && !this.observedBlocking
                && !this.entity.isClimbing() && !this.entity.isTouchingWater()
                && !this.entity.hasStatusEffect(StatusEffects.BLINDNESS) && !this.entity.hasVehicle()
                && this.entity.getRandom().nextDouble() < this.difficulty.critChance()) {
            this.criticalPlanned = true;
            this.jumpTicks = 24;
            this.entity.setSprinting(false);
            this.entity.input(this.entity.forwardSpeed, this.entity.sidewaysSpeed, true);
            // 跳跃输入要到下一次物理 tick 才生效；这里挥剑会在地面普攻，却错误显示为暴击。
            this.stateLabel = "起跳准备暴击";
            return;
        }
        if (this.criticalPlanned && (this.jumpTicks == 0 || this.entity.isOnGround() && this.jumpTicks < 18)) {
            this.criticalPlanned = false;
        }
        if (this.criticalPlanned && (this.entity.getVelocity().y >= 0 || !canCriticalHit())) {
            this.stateLabel = "等待下落暴击";
            return;
        }
        if (shield || cooling || this.actionTicks > 0 || !canHit()) return;

        this.actionTicks = this.difficulty.attackDelayTicks() + this.entity.getRandom().nextInt(3);
        if (this.entity.getRandom().nextDouble() < this.difficulty.hitChance()) {
            boolean critical = canCriticalHit();
            this.entity.attack(this.opponent);
            this.stateLabel = critical ? "跳跃暴击" : this.observedBlocking ? "斧击破盾" : "近战攻击";
        } else {
            this.entity.resetLastAttackedTicks();
            this.stateLabel = "挥空调整";
        }
        // ServerPlayerEntity.swingHand 会重置攻击冷却；先挥手再 attack 会把满蓄力打成约 20% 伤害。
        // 与真人攻击包/挥手包的顺序一致：先让原版结算攻击，再发送挥手动画。
        this.entity.swingHand(Hand.MAIN_HAND);
        this.criticalPlanned = false;
        if (this.difficulty.getLevel() >= 4) this.sprintResetTicks = 2;
    }

    /** 与原版 PlayerEntity.attack 的暴击条件保持一致，仅判断时机，伤害仍由原版计算。 */
    private boolean canCriticalHit() {
        return this.entity.getAttackCooldownProgress(0.5f) > 0.9f && this.entity.fallDistance > 0
                && !this.entity.isOnGround() && !this.entity.isClimbing() && !this.entity.isTouchingWater()
                && !this.entity.hasStatusEffect(StatusEffects.BLINDNESS) && !this.entity.hasVehicle()
                && !this.entity.isSprinting();
    }

    private boolean canHit() {
        if (!this.entity.canSee(this.opponent)) return false;
        Vec3d eye = this.entity.getEyePos();
        Vec3d end = eye.add(this.entity.getRotationVec(1).multiply(BotDifficulty.MELEE_REACH));
        // A real three-block eye ray must intersect the actual hitbox; no larger
        // reach/omnidirectional attack at higher levels and no hits through walls.
        return this.opponent.getBoundingBox().raycast(eye, end).isPresent();
    }

    private void fightWithBow(int slot, double distance) {
        select(slot);
        face(this.observedPos.add(0, 1.0, 0), true);
        this.stateLabel = "拉弓预判";
        move(distance < 7 ? -1 : distance > 15 ? 0.65 : 0,
                this.strafeDirection * this.difficulty.strafeStrength(), false);
        if (!this.entity.isUsingItem()) {
            if (this.actionTicks == 0 && this.entity.canSee(this.opponent)) useMainHand();
            return;
        }
        int drawn = this.entity.getItemUseTime();
        if (drawn >= this.difficulty.bowChargeTicks() && this.entity.canSee(this.opponent)) {
            // Releasing the real bow consumes real arrows and spawns a vanilla
            // projectile. A quick sidestep can still dodge a perfectly aimed shot.
            this.entity.stopUsingItem();
            this.actionTicks = this.difficulty.reactionTicks() + this.entity.getRandom().nextInt(6);
            this.stateLabel = "弓箭射击";
        } else if (drawn > 50) {
            this.entity.clearActiveItem();
        }
    }

    private void face(Vec3d target, boolean ballistic) {
        Vec3d eye = this.entity.getEyePos();
        if (ballistic) {
            double flight = eye.distanceTo(target) / 3.0;
            double prediction = this.difficulty.getLevel() >= 3 ? Math.min(flight, 12) : 0;
            target = target.add(this.observedVelocity.multiply(prediction));
        }
        Vec3d offset = target.subtract(eye);
        double flat = Math.hypot(offset.x, offset.z);
        double yaw = Math.toDegrees(Math.atan2(offset.z, offset.x)) - 90 + this.yawError;
        double pitch = -Math.toDegrees(Math.atan2(offset.y, flat));
        if (ballistic && flat > 0.01) {
            double speed = 3.0 * BowItem.getPullProgress(this.difficulty.bowChargeTicks());
            double v2 = speed * speed;
            double gravity = 0.05;
            double discriminant = v2 * v2 - gravity * (gravity * flat * flat + 2 * offset.y * v2);
            if (discriminant >= 0) pitch = -Math.toDegrees(Math.atan((v2 - Math.sqrt(discriminant)) / (gravity * flat)));
        }
        turn((float) yaw, (float) (pitch + this.pitchError));
    }

    private void turn(float yaw, float pitch) {
        float cap = (float) this.difficulty.maxTurnDegrees();
        this.entity.setYaw(MathHelper.stepUnwrappedAngleTowards(this.entity.getYaw(), yaw, cap));
        this.entity.setPitch(MathHelper.clamp(MathHelper.stepTowards(this.entity.getPitch(), pitch, cap * 0.75f), -89, 89));
        this.entity.setHeadYaw(this.entity.getYaw());
        this.entity.setBodyYaw(this.entity.getYaw());
    }

    private void move(double forward, double sideways, boolean sprint) {
        if (this.entity.isUsingItem()) {
            forward *= 0.2;
            sideways *= 0.2;
            sprint = false;
        }
        double yaw = Math.toRadians(this.entity.getYaw());
        double dx = -Math.sin(yaw) * forward + Math.cos(yaw) * sideways;
        double dz = Math.cos(yaw) * forward + Math.sin(yaw) * sideways;
        BlockPos ahead = BlockPos.ofFloored(this.entity.getX() + dx * 0.8, this.entity.getY() - 0.4, this.entity.getZ() + dz * 0.8);
        if (this.entity.isOnGround() && this.world.getBlockState(ahead).isAir()
                && this.world.getBlockState(ahead.down()).isAir()) {
            forward = 0;
            sideways = 0;
        }
        if (this.world.getBlockState(ahead.up()).isOf(net.minecraft.block.Blocks.LAVA)
                || this.world.getBlockState(ahead.up()).isOf(net.minecraft.block.Blocks.FIRE)) {
            forward = -0.5;
            sideways = -sideways;
        }
        this.entity.setSprinting(sprint && this.entity.getHungerManager().getFoodLevel() > 6);
        this.entity.input((float) forward, (float) sideways,
                this.entity.horizontalCollision && this.entity.isOnGround());
    }

    /**
     * Iron PvP tools need visible preparation and inventory costs too. Bucket
     * actions use exactly the ray that BucketItem will use, including its normal
     * range and collision rules; never place fluid directly under a remote target.
     */
    private boolean tryWater() {
        if (this.difficulty.getLevel() < 3) return false;
        boolean emergency = this.entity.isOnFire() || this.entity.isInLava();
        if (this.entity.isUsingItem() && !emergency) return false;
        if (this.waterSource != null && (!this.world.getBlockState(this.waterSource).isOf(Blocks.WATER)
                || !this.world.getFluidState(this.waterSource).isStill() || this.waterTicks > 100)) {
            this.waterSource = null;
        }
        if (emergency && this.bucketCooldown == 0 && this.entity.isOnGround()) {
            int water = find(stack -> stack.isOf(Items.WATER_BUCKET));
            BlockPos floor = this.entity.getBlockPos().down();
            if (water < 0 || !this.world.getBlockState(floor).isSolidBlock(this.world, floor)) return false;
            if (this.entity.isUsingItem()) this.entity.clearActiveItem();
            select(water);
            this.entity.setSprinting(false);
            lookAt(floor.toCenterPos().add(0, 0.49, 0));
            this.stateLabel = "低头准备水桶灭火";
            BlockHitResult hit = bucketRay(RaycastContext.FluidHandling.NONE);
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(floor) && hit.getSide() == Direction.UP) {
                useMainHand();
                BlockPos source = floor.up();
                if (this.entity.getMainHandStack().isOf(Items.BUCKET) && this.world.getBlockState(source).isOf(Blocks.WATER)) {
                    this.waterSource = source;
                    this.waterTicks = 0;
                    this.stateLabel = "水桶灭火";
                }
                this.bucketCooldown = 40;
                this.actionTicks = this.difficulty.reactionTicks();
            }
            return true;
        }
        if (this.waterSource == null || this.waterTicks < 12 || emergency
                || this.entity.getEyePos().squaredDistanceTo(this.waterSource.toCenterPos()) > 16) return false;
        int empty = find(stack -> stack.isOf(Items.BUCKET));
        if (empty < 0) return false;
        if (this.entity.isUsingItem()) this.entity.clearActiveItem();
        select(empty);
        this.entity.setSprinting(false);
        lookAt(this.waterSource.toCenterPos());
        this.stateLabel = "回收灭火水源";
        BlockHitResult hit = bucketRay(RaycastContext.FluidHandling.SOURCE_ONLY);
        if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(this.waterSource)) {
            useMainHand();
            if (this.entity.getMainHandStack().isOf(Items.WATER_BUCKET)) {
                this.waterSource = null;
                this.bucketCooldown = 40;
            }
        }
        return true;
    }

    private BlockHitResult bucketRay(RaycastContext.FluidHandling fluidHandling) {
        Vec3d eye = this.entity.getEyePos();
        Vec3d end = eye.add(this.entity.getRotationVec(1).multiply(this.entity.getBlockInteractionRange()));
        return this.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, fluidHandling, this.entity));
    }

    private void lookAt(Vec3d point) {
        Vec3d offset = point.subtract(this.entity.getEyePos());
        turn((float) (Math.toDegrees(Math.atan2(offset.z, offset.x)) - 90),
                (float) -Math.toDegrees(Math.atan2(offset.y, Math.hypot(offset.x, offset.z))));
    }

    /** Cast/retract a real rod: the hook's flight, hit detection and durability remain vanilla. */
    private boolean tryRod(double distance) {
        if (this.difficulty.getLevel() < 3 || this.entity.isUsingItem()) return false;
        int rod = find(stack -> stack.isOf(Items.FISHING_ROD));
        if (rod < 0) return false;
        if (this.entity.fishHook != null) {
            select(rod);
            face(this.observedPos.add(0, 1.1, 0), false);
            move(distance > 5 ? 0.6 : 0, this.strafeDirection * this.difficulty.strafeStrength(), false);
            this.stateLabel = "钓竿试探";
            if (this.entity.fishHook.getHookedEntity() != null || this.rodTicks >= 12 || distance < 3.2) {
                useMainHand();
                this.actionTicks = this.difficulty.reactionTicks();
                this.stateLabel = "收竿切回近战";
            }
            return true;
        }
        if (this.rodCooldown > 0 || this.actionTicks > 0 || distance < 4 || distance > 7
                || !this.entity.canSee(this.opponent)) return false;
        Vec3d towardBot = this.entity.getPos().subtract(this.observedPos).normalize();
        if (this.observedVelocity.dotProduct(towardBot) < 0.025 && !this.observedRanged) return false;
        Vec3d target = this.observedPos.add(0, 1.1, 0)
                .add(this.observedVelocity.multiply(Math.min(6, distance)));
        select(rod);
        face(target, false);
        this.entity.setSprinting(false);
        this.stateLabel = "瞄准钓竿";
        double alignment = this.entity.getRotationVec(1).dotProduct(target.subtract(this.entity.getEyePos()).normalize());
        if (alignment >= Math.cos(Math.toRadians(this.difficulty.aimToleranceDegrees()))) {
            useMainHand();
            this.rodTicks = 0;
            this.rodCooldown = 100 - this.difficulty.getLevel() * 10;
            this.actionTicks = this.difficulty.reactionTicks();
        }
        return true;
    }

    private boolean trySupply(double distance) {
        int slot = -1;
        boolean splash = false;
        if (this.entity.getHealth() <= this.difficulty.healingThreshold()) {
            slot = find(stack -> stack.isOf(Items.SPLASH_POTION) && hasEffect(stack, StatusEffects.INSTANT_HEALTH));
            splash = slot >= 0;
            if (slot < 0 && !this.entity.hasStatusEffect(StatusEffects.REGENERATION)) {
                slot = find(stack -> stack.isOf(Items.GOLDEN_APPLE) || stack.isOf(Items.ENCHANTED_GOLDEN_APPLE));
            }
        }
        if (slot < 0 && this.entity.getHungerManager().getFoodLevel() <= 15 && distance > 3.5) {
            slot = find(stack -> stack.contains(DataComponentTypes.FOOD) && !stack.isOf(Items.GOLDEN_APPLE));
        }
        if (slot < 0 && distance > 7 && this.difficulty.getLevel() >= 3) {
            slot = find(stack -> stack.isOf(Items.POTION)
                    && (hasEffect(stack, StatusEffects.SPEED) && !this.entity.hasStatusEffect(StatusEffects.SPEED)
                    || hasEffect(stack, StatusEffects.JUMP_BOOST) && !this.entity.hasStatusEffect(StatusEffects.JUMP_BOOST)));
        }
        if (slot < 0) return false;
        if (this.entity.isUsingItem()) this.entity.clearActiveItem();
        select(slot);
        this.entity.setSprinting(false);
        if (splash) {
            // Turn down at the same bounded speed as aiming: a heal has a visible
            // vulnerable preparation and real splash travel, rather than free HP.
            this.stateLabel = "低头准备泼药";
            turn(this.entity.getYaw(), 86);
            move(distance < 5 ? -0.4 : 0, 0, false);
            if (this.entity.getPitch() < 75) return true;
            useMainHand();
            this.healRetryTicks = 20;
            this.actionTicks = this.difficulty.reactionTicks();
            this.stateLabel = "泼药恢复";
        } else {
            useMainHand();
            this.healRetryTicks = 8;
            this.stateLabel = "撤退补给";
            move(-1, this.strafeDirection * 0.2, false);
        }
        return true;
    }

    private static boolean hasEffect(ItemStack stack, net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.effect.StatusEffect> type) {
        PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
        if (contents == null) return false;
        for (StatusEffectInstance effect : contents.getEffects()) {
            if (effect.getEffectType().equals(type)) return true;
        }
        return false;
    }

    private void useMainHand() {
        this.entity.interactionManager.interactItem(this.entity, this.world, this.entity.getMainHandStack(), Hand.MAIN_HAND);
    }

    private int findMelee() {
        if (this.observedBlocking && this.difficulty.getLevel() >= 3) {
            int axe = find(stack -> stack.getItem() instanceof AxeItem);
            if (axe >= 0) return axe;
        }
        int sword = find(stack -> stack.getItem() instanceof SwordItem);
        return sword >= 0 ? sword : find(stack -> stack.getItem() instanceof AxeItem);
    }

    private int find(Predicate<ItemStack> predicate) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = this.entity.getInventory().getStack(slot);
            if (!stack.isEmpty() && predicate.test(stack)) return slot;
        }
        return -1;
    }

    private void select(int slot) {
        if (slot < 0) return;
        boolean changed = false;
        if (slot >= 9) {
            // Vanilla can only hold hotbar items. Swap an actual backpack stack
            // into the last hotbar slot; never duplicate a consumable or weapon.
            ItemStack stack = this.entity.getInventory().getStack(slot);
            this.entity.getInventory().setStack(slot, this.entity.getInventory().getStack(8));
            this.entity.getInventory().setStack(8, stack);
            slot = 8;
            changed = true;
        }
        if (this.entity.getInventory().selectedSlot != slot) {
            this.entity.clearActiveItem();
            this.entity.getInventory().selectedSlot = slot;
            changed = true;
        }
        if (changed) {
            this.handChangedAtAge = this.entity.age;
            this.entity.resetLastAttackedTicks();
        }
    }

    private double randomError(double size) {
        return (this.entity.getRandom().nextDouble() * 2 - 1) * size;
    }

    public ServerPlayerEntity getEntity() { return this.entity; }
    public String getStateLabel() { return this.stateLabel; }

    public void discard() {
        if (this.discarded) return;
        this.discarded = true;
        this.entity.clearActiveItem();
        this.entity.getInventory().clear();
        this.entity.getTextStream().onDisconnect();
        if (this.spawned) {
            this.world.removePlayer(this.entity, Entity.RemovalReason.DISCARDED);
            this.server.getPlayerManager().sendToAll(new PlayerRemoveS2CPacket(List.of(this.entity.getUuid())));
        }
    }

    /** The actual player tick normally comes from a real connection. */
    private static final class BotPlayer extends ServerPlayerEntity {
        private Vec3d receivedVelocity;

        private BotPlayer(MinecraftServer server, ServerWorld world, GameProfile profile) {
            super(server, world, profile, SyncedClientOptions.createDefault());
        }

        @Override
        public void tick() {
            if (this.receivedVelocity != null) {
                this.setVelocity(this.receivedVelocity);
                this.receivedVelocity = null;
            }
            super.tick();
            Vec3d beforeMovement = this.getPos();
            this.playerTick();
            // ServerPlayerEntity.fall 是空实现，真人由移动包调用 handleFall 累计下落距离。
            // 人机没有移动包，必须按本 tick 实际位移走同一原版路径，否则永远不满足暴击条件。
            Vec3d movement = this.getPos().subtract(beforeMovement);
            this.handleFall(movement.x, movement.y, movement.z, this.isOnGround());
        }

        private void input(float forward, float sideways, boolean jump) {
            this.forwardSpeed = forward;
            this.sidewaysSpeed = sideways;
            this.jumping = jump;
        }

        @Override
        public Text getPlayerListName() { return this.getCustomName(); }
    }

    private static final class BotNetworkHandler extends ServerPlayNetworkHandler {
        private final BotPlayer bot;

        private BotNetworkHandler(MinecraftServer server, BotPlayer player, GameProfile profile) {
            super(server, new ClientConnection(NetworkSide.SERVERBOUND), player, ConnectedClientData.createDefault(profile, false));
            this.bot = player;
        }

        @Override
        public void sendPacket(Packet<?> packet) { receive(packet); }

        @Override
        public void send(Packet<?> packet, PacketCallbacks callbacks) { receive(packet); }

        private void receive(Packet<?> packet) {
            // PlayerEntity.attack restores server-player velocity after sending
            // its knockback packet, expecting a client to apply it. Consume that
            // packet next tick so bots obey exactly the same knockback impulse.
            if (packet instanceof EntityVelocityUpdateS2CPacket velocity && velocity.getEntityId() == this.bot.getId()) {
                this.bot.receivedVelocity = new Vec3d(velocity.getVelocityX(), velocity.getVelocityY(), velocity.getVelocityZ());
            }
            // Other packets have no receiving client and must not accumulate in
            // ClientConnection's unconnected send queue during long sessions.
        }

        @Override
        public boolean isConnectionOpen() { return true; }
    }
}
