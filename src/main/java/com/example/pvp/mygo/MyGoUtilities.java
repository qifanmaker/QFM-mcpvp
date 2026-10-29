package com.example.pvp.mygo;

import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.mixin.DisplayEntityInvoker;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.block.BlockState;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 借鉴 Counter-Strike 的投掷物角色，使用原版展示实体和服务端碰撞实现。
 * 不调用破坏方块的原版爆炸；烟幕使用不可碰撞的实体幕片，关闭粒子也不能直接透视。
 */
public final class MyGoUtilities {
    public enum ThrowMode { STRONG, WEAK, MIDDLE }
    public enum DamageKind { IMPACT, FIRE, DECOY }
    public record LaunchInfo(Type type, ThrowMode mode, Vec3d origin, Vec3d velocity, int tick) { }
    public enum Type {
        HE("破片手雷", 300, 3, 72001, 1),
        FLASH("闪光弹", 200, 4, 72002, 2),
        SMOKE("烟雾弹", 300, 6, 72003, 1),
        MOLOTOV("燃烧瓶", 400, 7, 72005, 1),
        INCENDIARY("燃烧弹", 600, 7, 72006, 1),
        DECOY("诱饵弹", 50, 8, 72007, 2);
        public final String label;
        public final int price, slot, model, maximum;
        Type(String label, int price, int slot, int model, int maximum) {
            this.label = label; this.price = price; this.slot = slot; this.model = model; this.maximum = maximum;
        }
        /** T carries the Molotov; CT carries the Incendiary. Other utilities are shared. */
        public boolean availableTo(boolean attackers) {
            return this != INCENDIARY && this != MOLOTOV || attackers == (this == MOLOTOV);
        }
        public boolean fire() { return this == MOLOTOV || this == INCENDIARY; }
        public boolean decoy() { return this == DECOY; }
        public static Type heldAt(Map<Type, Integer> carried, int slot) {
            for (Type type : values()) if (type.slot == slot && carried.getOrDefault(type, 0) > 0) return type;
            return null;
        }
        public static Type parse(String text) {
            try { return valueOf(text.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { return null; }
        }
        public ItemStack stack(int count) {
            Item item = this == MOLOTOV || this == INCENDIARY ? Items.FIRE_CHARGE : Items.SLIME_BALL;
            ItemStack stack = new ItemStack(item, Math.max(1, count));
            stack.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(model));
            stack.set(DataComponentTypes.CUSTOM_NAME, net.minecraft.text.Text.literal("§e" + label + " · 左键高抛 / 右键低抛"));
            return stack;
        }
    }
    @FunctionalInterface public interface Damage { void apply(ServerPlayerEntity victim, ServerPlayerEntity owner, double amount); }
    @FunctionalInterface public interface DirectDamage {
        void apply(ServerPlayerEntity victim, ServerPlayerEntity owner, double amount, DamageKind kind);
    }
    private static final class Thrown {
        final ServerPlayerEntity owner;
        final Type type;
        final DisplayEntity.ItemDisplayEntity display;
        final int fuse, born;
        int restingTicks, lastBounce = -10;
        Vec3d position, velocity;
        Thrown(ServerPlayerEntity owner, Type type, DisplayEntity.ItemDisplayEntity display, int tick,
               Vec3d position, Vec3d velocity) {
            this.owner = owner; this.type = type; this.display = display;
            this.born = tick; this.fuse = tick + MyGoGrenadePhysics.FUSE_TICKS;
            this.position = position; this.velocity = velocity;
        }
    }
    private record Smoke(Vec3d position, int expires, List<DisplayEntity.ItemDisplayEntity> screens) { }
    private record Fire(Vec3d position, int expires, ServerPlayerEntity owner) { }
    private final ArenaWorld world;
    private final Supplier<List<ServerPlayerEntity>> players;
    private final Damage damage;
    private final DirectDamage directDamage;
    private final List<Thrown> thrown = new ArrayList<>();
    private final List<Smoke> smokes = new ArrayList<>();
    private final List<Fire> fires = new ArrayList<>();
    private final Map<UUID, LaunchInfo> launches = new HashMap<>();
    private final Map<UUID, Vec3d> previousPositions = new HashMap<>(), movement = new HashMap<>();

    public MyGoUtilities(ArenaWorld world, Supplier<List<ServerPlayerEntity>> players, Damage damage) {
        this(world, players, damage, (victim, owner, amount, kind) -> { });
    }

    public MyGoUtilities(ArenaWorld world, Supplier<List<ServerPlayerEntity>> players,
                         Damage damage, DirectDamage directDamage) {
        this.world = world; this.players = players; this.damage = damage; this.directDamage = directDamage;
    }
    public void launch(ServerPlayerEntity player, Type type, int tick) {
        launch(player, type, ThrowMode.WEAK, tick);
    }
    public void launch(ServerPlayerEntity player, Type type, ThrowMode mode, int tick) {
        launch(player, type, mode, tick, null);
    }

    /** Optional decoy sound key identifies the gun the decoy should imitate. */
    public void launch(ServerPlayerEntity player, Type type, ThrowMode mode, int tick, String decoySoundKey) {
        // 原版强/弱投掷不是同一速度；出手点也必须先检查掩体，避免贴墙投穿墙。
        boolean strong = mode == ThrowMode.STRONG;
        Vec3d eye = player.getEyePos();
        Vec3d origin = eye.add(0, strong ? 0 : -.3, 0).add(player.getRotationVec(1).multiply(.4));
        var wall = this.world.raycast(new RaycastContext(eye, origin, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, player));
        if (wall.getType() != HitResult.Type.MISS) origin = wall.getPos().add(Vec3d.of(wall.getSide().getVector()).multiply(.06));
        Vec3d previous = this.previousPositions.get(player.getUuid());
        Vec3d inherited = previous == null ? Vec3d.ZERO : player.getPos().subtract(previous);
        if (inherited.lengthSquared() > 6.25) inherited = Vec3d.ZERO;
        else if (inherited.lengthSquared() < 1.0e-8) inherited = this.movement.getOrDefault(player.getUuid(), player.getVelocity());
        if (inherited.lengthSquared() > 6.25) inherited = Vec3d.ZERO;
        double strength = mode == ThrowMode.STRONG ? 1 : mode == ThrowMode.MIDDLE ? .6 : .3;
        Vec3d velocity = MyGoGrenadePhysics.launchVelocity(player.getPitch(), player.getYaw(), strength, inherited);
        DisplayEntity.ItemDisplayEntity display = display(type.stack(1), origin);
        this.thrown.add(new Thrown(player, type, display, tick, origin, velocity));
        this.launches.put(player.getUuid(), new LaunchInfo(type, mode, origin, velocity, tick));
        sound(player.getEyePos(), "grenade.pin", .55F);
        sound(player.getEyePos(), type.decoy() && decoySoundKey != null ? "decoy.throw." + decoySoundKey : "throw", .7F);
    }
    public void tick(int tick) {
        for (ServerPlayerEntity player : this.players.get()) {
            Vec3d old = this.previousPositions.put(player.getUuid(), player.getPos());
            Vec3d delta = old == null ? Vec3d.ZERO : player.getPos().subtract(old);
            this.movement.put(player.getUuid(), delta.lengthSquared() > 6.25 ? Vec3d.ZERO : delta);
        }
        for (Thrown grenade : List.copyOf(this.thrown)) {
            advance(grenade, tick);
            grenade.display.refreshPositionAndAngles(grenade.position.x, grenade.position.y, grenade.position.z, tick * 18, 0);
            boolean smokeReady = grenade.type == Type.SMOKE && grenade.restingTicks >= 4;
            if (tick >= grenade.fuse && (grenade.type != Type.SMOKE || smokeReady)) {
                grenade.display.discard(); this.thrown.remove(grenade); detonate(grenade, tick);
            } else if (tick - grenade.born >= MyGoGrenadePhysics.MAX_FLIGHT_TICKS) {
                // 掉进虚空的烟弹仅清除，不凭空在半空生成永久幕片。
                grenade.display.discard(); this.thrown.remove(grenade);
            }
        }
        for (Smoke smoke : List.copyOf(this.smokes)) {
            if (tick >= smoke.expires) {
                smoke.screens.forEach(Entity::discard); this.smokes.remove(smoke); sound(smoke.position, "smoke.end", .5F); continue;
            }
            if (tick % 5 != 0) continue;
            this.world.spawnParticles(ParticleTypes.CLOUD, smoke.position.x, smoke.position.y + 1.5, smoke.position.z, 20, 2.5, 1, 2.5, .005);
            for (ServerPlayerEntity p : this.players.get()) if (p.getPos().squaredDistanceTo(smoke.position)
                    < MyGoGrenadePhysics.SMOKE_RADIUS * MyGoGrenadePhysics.SMOKE_RADIUS && visible(smoke.position, p.getEyePos(), p))
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 6, 0, false, false, false));
        }
        for (Fire fire : List.copyOf(this.fires)) {
            if (tick >= fire.expires) { this.fires.remove(fire); continue; }
            // Smoke extinguishes fire at the same height, matching classic GO's coarse interaction.
            if (this.smokes.stream().anyMatch(smoke -> smoke.position.distanceTo(fire.position) < 2.5)) {
                this.fires.remove(fire); continue;
            }
            if (tick % 5 == 0) {
                this.world.spawnParticles(ParticleTypes.FLAME, fire.position.x, fire.position.y + .05, fire.position.z, 12, 1.1, .1, 1.1, .01);
                for (ServerPlayerEntity p : this.players.get()) {
                    if (p.getPos().distanceTo(fire.position) <= MyGoGrenadePhysics.FIRE_RADIUS && visible(fire.position, p.getEyePos(), p))
                        this.directDamage.apply(p, fire.owner, 8, DamageKind.FIRE);
                }
            }
        }
    }

    private void detonate(Thrown grenade, int tick) {
        Vec3d position = grenade.position;
        if (grenade.type == Type.SMOKE) {
            List<DisplayEntity.ItemDisplayEntity> screens = new ArrayList<>();
            for (Vec3d offset : List.of(new Vec3d(0, 1.5, 0), new Vec3d(-1, 1.5, 0), new Vec3d(1, 1.5, 0),
                    new Vec3d(0, 1.5, -1), new Vec3d(0, 1.5, 1))) {
                ItemStack stack = new ItemStack(Items.SLIME_BALL);
                stack.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(72004));
                var screen = display(stack, position.add(offset));
                var invoker = (DisplayEntityInvoker) screen;
                invoker.pvp$setBillboardMode(DisplayEntity.BillboardMode.CENTER);
                invoker.pvp$setTransformation(new AffineTransformation(new Vector3f(),
                        new Quaternionf(), new Vector3f(7, 7, 7), new Quaternionf()));
                screens.add(screen);
            }
            this.smokes.add(new Smoke(position, tick + MyGoGrenadePhysics.SMOKE_TICKS, screens)); sound(position, "smoke", 1);
            return;
        }
        if (grenade.type.fire()) {
            if (this.smokes.stream().anyMatch(smoke -> smoke.position.distanceTo(position) < 2.5)) {
                sound(position, "fire", .6F);
                return;
            }
            this.fires.add(new Fire(position, tick + MyGoGrenadePhysics.FIRE_TICKS, grenade.owner));
            sound(position, "fire", 1);
            return;
        }
        if (grenade.type.decoy()) {
            sound(position, "decoy.explode", 1);
            this.world.spawnParticles(ParticleTypes.EXPLOSION, position.x, position.y, position.z, 1, 0, 0, 0, 0);
            for (ServerPlayerEntity target : this.players.get()) {
                if (target.getEyePos().distanceTo(position) <= 2.5 && visible(position, target.getEyePos(), target))
                    this.directDamage.apply(target, grenade.owner, 1, DamageKind.DECOY);
            }
            return;
        }
        sound(position, grenade.type == Type.HE ? "explode" : "flash", 1);
        this.world.spawnParticles(grenade.type == Type.HE ? ParticleTypes.EXPLOSION : ParticleTypes.FLASH,
                position.x, position.y, position.z, 1, 0, 0, 0, 0);
        for (ServerPlayerEntity target : this.players.get()) {
            double distance = exposureDistance(target, position);
            if (!Double.isFinite(distance)) continue;
            if (distance > (grenade.type == Type.HE ? MyGoGrenadePhysics.HE_RADIUS : MyGoGrenadePhysics.FLASH_RADIUS)) continue;
            if (grenade.type == Type.HE) this.damage.apply(target, grenade.owner, MyGoGrenadePhysics.heDamage(distance));
            else {
                Vec3d toward = position.subtract(target.getEyePos()).normalize();
                int duration = MyGoGrenadePhysics.flashDurationTicks(distance, target.getRotationVec(1).dotProduct(toward));
                if (duration == 0) continue;
                target.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, duration, 0, false, false, false));
                target.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, duration, 0, false, false, false));
            }
        }
    }
    /** Return the nearest exposed body sample; one blocked sample must not make the whole player immune. */
    private double exposureDistance(ServerPlayerEntity target, Vec3d origin) {
        Vec3d feet = target.getPos().add(0, .15, 0);
        Vec3d torso = target.getPos().add(0, target.isSneaking() ? .85 : 1.05, 0);
        Vec3d head = target.getEyePos();
        double result = Double.POSITIVE_INFINITY;
        for (Vec3d point : List.of(feet, torso, head)) if (visible(origin, point, target))
            result = Math.min(result, origin.distanceTo(point));
        return result;
    }

    private boolean visible(Vec3d from, Vec3d to, Entity ignored) {
        var wall = this.world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, ignored));
        return wall.getType() == HitResult.Type.MISS || wall.getPos().squaredDistanceTo(to) < .04;
    }

    private ServerPlayerEntity hitPlayer(Vec3d from, Vec3d to, ServerPlayerEntity owner) {
        ServerPlayerEntity best = null; double bestDistance = Double.POSITIVE_INFINITY;
        Vec3d delta = to.subtract(from); double len2 = delta.lengthSquared();
        for (ServerPlayerEntity p : this.players.get()) {
            if (p == owner || p.getWorld() != this.world) continue;
            Vec3d relative = p.getPos().add(0, .9, 0).subtract(from);
            double factor = len2 < 1.0e-8 ? 0 : Math.clamp(relative.dotProduct(delta) / len2, 0, 1);
            Vec3d closest = from.add(delta.multiply(factor));
            if (closest.distanceTo(p.getPos().add(0, .9, 0)) <= .65 && factor < bestDistance) { best = p; bestDistance = factor; }
        }
        return best;
    }

    /** 小步连续扫掠并在每次碰撞后消耗剩余位移，防止高速强抛穿过一格墙或反复卡角。 */
    private void advance(Thrown grenade, int tick) {
        boolean supported = supported(grenade);
        if (supported && grenade.velocity.lengthSquared() < MyGoGrenadePhysics.STOP_SPEED * MyGoGrenadePhysics.STOP_SPEED) {
            grenade.velocity = Vec3d.ZERO; grenade.restingTicks++; return;
        }
        grenade.restingTicks = 0;
        int steps = Math.clamp((int) Math.ceil(grenade.velocity.length() / .15), 1, 24);
        double interval = 1.0 / steps;
        for (int step = 0; step < steps; step++) {
            Vec3d before = grenade.velocity;
            grenade.velocity = before.add(0, -MyGoGrenadePhysics.GRAVITY * interval, 0);
            Vec3d motion = before.add(grenade.velocity).multiply(.5 * interval);
            double remaining = interval;
            for (int contact = 0; contact < 3 && motion.lengthSquared() > 1.0e-10; contact++) {
                Vec3d next = grenade.position.add(motion);
                ServerPlayerEntity playerHit = hitPlayer(grenade.position, next, grenade.owner);
                if (playerHit != null) {
                    this.directDamage.apply(playerHit, grenade.owner, 1, DamageKind.IMPACT);
                    grenade.velocity = grenade.velocity.multiply(-.25);
                    grenade.position = next;
                    break;
                }
                var hit = this.world.raycast(new RaycastContext(grenade.position, next, RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.NONE, grenade.owner));
                if (hit.getType() == HitResult.Type.MISS) { grenade.position = next; break; }
                double fraction = Math.clamp(grenade.position.distanceTo(hit.getPos()) / motion.length(), 0, 1);
                Vec3d normal = Vec3d.of(hit.getSide().getVector());
                grenade.position = hit.getPos().add(normal.multiply(.06));
                double speed = grenade.velocity.length();
                grenade.velocity = MyGoGrenadePhysics.reflect(grenade.velocity, normal);
                if (normal.y > .7 && Math.abs(grenade.velocity.y) < .06)
                    grenade.velocity = new Vec3d(grenade.velocity.x * .8, 0, grenade.velocity.z * .8);
                if (speed > .08 && tick - grenade.lastBounce >= 2) {
                    grenade.lastBounce = tick;
                    sound(grenade.position, "grenade.bounce." + bounceMaterial(this.world.getBlockState(hit.getBlockPos())),
                            (float) Math.clamp(speed, .12, .65));
                }
                remaining *= 1 - fraction;
                motion = grenade.velocity.multiply(remaining);
            }
        }
    }
    private boolean supported(Thrown grenade) {
        var hit = this.world.raycast(new RaycastContext(grenade.position, grenade.position.add(0, -.085, 0),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, grenade.owner));
        return hit.getType() != HitResult.Type.MISS && hit.getSide().getVector().getY() > 0;
    }
    private static String bounceMaterial(BlockState state) {
        String name = net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).getPath();
        if (name.contains("iron") || name.contains("copper") || name.contains("anvil") || name.contains("chain")) return "metal";
        if (name.contains("sand") && !name.contains("sandstone") || name.contains("dirt") || name.contains("grass")
                || name.contains("wool") || name.contains("carpet")) return "soft";
        return "hard";
    }
    private DisplayEntity.ItemDisplayEntity display(ItemStack stack, Vec3d point) {
        var display = new DisplayEntity.ItemDisplayEntity(EntityType.ITEM_DISPLAY, this.world);
        display.setItemStack(stack); display.setNoGravity(true);
        display.refreshPositionAndAngles(point.x, point.y, point.z, 0, 0);
        this.world.spawnEntity(display); return display;
    }
    private void sound(Vec3d point, String name, float volume) {
        this.world.playSound(null, point.x, point.y, point.z, SoundEvent.of(Identifier.of("pvp", "mygo." + name)), SoundCategory.PLAYERS, volume, 1);
    }
    public int activeGrenades() { return this.thrown.size(); }
    public int activeSmokes() { return this.smokes.size(); }
    public int activeFires() { return this.fires.size(); }
    public int activeDecoys() { return (int) this.thrown.stream().filter(g -> g.type.decoy()).count(); }
    public LaunchInfo lastLaunch(UUID player) { return this.launches.get(player); }

    /** 人机感知必须尊重烟幕；视觉幕片没有方块碰撞，普通 canSee 无法识别烟雾遮挡。 */
    public boolean blocksVision(Vec3d from, Vec3d to) {
        Vec3d segment = to.subtract(from);
        double lengthSquared = segment.lengthSquared();
        for (Smoke smoke : this.smokes) {
            Vec3d center = smoke.position.add(0, 1.5, 0);
            double t = lengthSquared < 1.0e-9 ? 0 : Math.clamp(center.subtract(from).dotProduct(segment) / lengthSquared, 0, 1);
            if (from.add(segment.multiply(t)).squaredDistanceTo(center)
                    <= MyGoGrenadePhysics.SMOKE_RADIUS * MyGoGrenadePhysics.SMOKE_RADIUS) return true;
        }
        return false;
    }

    /** 替换席位时旧实体不再参赛，回收它尚未引爆的投掷物，避免沿用旧 UUID 跨席位结算。 */
    public void removeOwner(UUID owner) {
        for (Thrown grenade : List.copyOf(this.thrown)) {
            if (grenade.owner.getUuid().equals(owner)) {
                grenade.display.discard();
                this.thrown.remove(grenade);
            }
        }
        this.fires.removeIf(fire -> fire.owner.getUuid().equals(owner));
        this.launches.remove(owner);
        this.previousPositions.remove(owner);
        this.movement.remove(owner);
    }
    public boolean owns(Entity entity) { return this.thrown.stream().anyMatch(g -> g.display == entity)
            || this.smokes.stream().anyMatch(s -> s.screens.contains(entity)); }
    public void clear() {
        this.thrown.forEach(g -> g.display.discard()); this.thrown.clear();
        this.smokes.forEach(s -> s.screens.forEach(Entity::discard)); this.smokes.clear();
        this.fires.clear();
        this.launches.clear(); this.previousPositions.clear(); this.movement.clear();
    }
}
