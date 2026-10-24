package com.example.pvp.mygo;

import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.mixin.DisplayEntityInvoker;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
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
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 借鉴 Counter-Strike 的三种投掷物角色，使用原版展示实体和服务端碰撞实现。
 * 不调用破坏方块的原版爆炸；烟幕使用不可碰撞的实体幕片，关闭粒子也不能直接透视。
 */
public final class MyGoUtilities {
    public enum Type {
        HE("破片手雷", 300, 3, 72001, 1), FLASH("闪光弹", 200, 4, 72002, 2), SMOKE("烟雾弹", 300, 6, 72003, 1);
        public final String label;
        public final int price, slot, model, maximum;
        Type(String label, int price, int slot, int model, int maximum) {
            this.label = label; this.price = price; this.slot = slot; this.model = model; this.maximum = maximum;
        }
        public static Type parse(String text) {
            try { return valueOf(text.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { return null; }
        }
        public ItemStack stack(int count) {
            ItemStack stack = new ItemStack(Items.SLIME_BALL, Math.max(1, count));
            stack.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(model));
            stack.set(DataComponentTypes.CUSTOM_NAME, net.minecraft.text.Text.literal("§e" + label + " · 右键投掷"));
            return stack;
        }
    }
    @FunctionalInterface public interface Damage { void apply(ServerPlayerEntity victim, ServerPlayerEntity owner, double amount); }
    private static final class Thrown {
        final ServerPlayerEntity owner;
        final Type type;
        final DisplayEntity.ItemDisplayEntity display;
        final int fuse;
        Vec3d position, velocity;
        Thrown(ServerPlayerEntity owner, Type type, DisplayEntity.ItemDisplayEntity display, int tick) {
            this.owner = owner; this.type = type; this.display = display; this.fuse = tick + 30;
            this.position = owner.getEyePos().add(owner.getRotationVec(1).multiply(.35));
            this.velocity = owner.getRotationVec(1).multiply(.72).add(0, .12, 0);
        }
    }
    private record Smoke(Vec3d position, int expires, List<DisplayEntity.ItemDisplayEntity> screens) { }
    private final ArenaWorld world;
    private final Supplier<List<ServerPlayerEntity>> players;
    private final Damage damage;
    private final List<Thrown> thrown = new ArrayList<>();
    private final List<Smoke> smokes = new ArrayList<>();

    public MyGoUtilities(ArenaWorld world, Supplier<List<ServerPlayerEntity>> players, Damage damage) {
        this.world = world; this.players = players; this.damage = damage;
    }
    public void launch(ServerPlayerEntity player, Type type, int tick) {
        DisplayEntity.ItemDisplayEntity display = display(type.stack(1), player.getEyePos());
        this.thrown.add(new Thrown(player, type, display, tick));
        sound(player.getEyePos(), "throw", .7F);
    }
    public void tick(int tick) {
        for (Thrown grenade : List.copyOf(this.thrown)) {
            Vec3d next = grenade.position.add(grenade.velocity);
            var hit = this.world.raycast(new RaycastContext(grenade.position, next, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, grenade.owner));
            if (hit.getType() != HitResult.Type.MISS) {
                Vec3d normal = Vec3d.of(hit.getSide().getVector());
                grenade.position = hit.getPos().add(normal.multiply(.03));
                grenade.velocity = grenade.velocity.subtract(normal.multiply(2 * grenade.velocity.dotProduct(normal))).multiply(.42);
            } else grenade.position = next;
            grenade.velocity = grenade.velocity.multiply(.97).add(0, -.035, 0);
            grenade.display.refreshPositionAndAngles(grenade.position.x, grenade.position.y, grenade.position.z, tick * 18, 0);
            if (tick >= grenade.fuse) {
                grenade.display.discard(); this.thrown.remove(grenade); detonate(grenade, tick);
            }
        }
        for (Smoke smoke : List.copyOf(this.smokes)) {
            if (tick >= smoke.expires) {
                smoke.screens.forEach(Entity::discard); this.smokes.remove(smoke); continue;
            }
            if (tick % 5 != 0) continue;
            this.world.spawnParticles(ParticleTypes.CLOUD, smoke.position.x, smoke.position.y + 1.5, smoke.position.z, 20, 2.5, 1, 2.5, .005);
            for (ServerPlayerEntity p : this.players.get()) if (p.getPos().squaredDistanceTo(smoke.position) < 12.25)
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 20, 0, false, false, false));
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
            this.smokes.add(new Smoke(position, tick + 240, screens)); sound(position, "smoke", 1);
            return;
        }
        sound(position, grenade.type == Type.HE ? "explode" : "flash", 1);
        this.world.spawnParticles(grenade.type == Type.HE ? ParticleTypes.EXPLOSION : ParticleTypes.FLASH,
                position.x, position.y, position.z, 1, 0, 0, 0, 0);
        for (ServerPlayerEntity target : this.players.get()) {
            double distance = target.getEyePos().distanceTo(position);
            if (distance > (grenade.type == Type.HE ? 6 : 14)) continue;
            var wall = this.world.raycast(new RaycastContext(position, target.getEyePos(), RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, target));
            if (wall.getType() != HitResult.Type.MISS && wall.getPos().squaredDistanceTo(target.getEyePos()) > .1) continue;
            if (grenade.type == Type.HE) this.damage.apply(target, grenade.owner, Math.max(0, 80 * (1 - distance / 6)));
            else {
                Vec3d toward = position.subtract(target.getEyePos()).normalize();
                boolean facing = target.getRotationVec(1).dotProduct(toward) > .15;
                int duration = (int) Math.max(10, (facing ? 80 : 25) * (1 - distance / 18));
                target.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, duration, 0, false, false, false));
                target.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, duration, 0, false, false, false));
            }
        }
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
    public boolean owns(Entity entity) { return this.thrown.stream().anyMatch(g -> g.display == entity)
            || this.smokes.stream().anyMatch(s -> s.screens.contains(entity)); }
    public void clear() {
        this.thrown.forEach(g -> g.display.discard()); this.thrown.clear();
        this.smokes.forEach(s -> s.screens.forEach(Entity::discard)); this.smokes.clear();
    }
}
