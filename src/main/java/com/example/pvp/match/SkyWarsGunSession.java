package com.example.pvp.match;

import com.example.pvp.mygo.MyGoBulletTrace;
import com.example.pvp.mygo.MyGoGunAccuracy;
import com.example.pvp.mygo.MyGoKnifeRules;
import com.example.pvp.mygo.MyGoLagHistory;
import com.example.pvp.mygo.MyGoRules;
import com.example.pvp.mygo.MyGoScope;
import com.example.pvp.mygo.MyGoUtilities;
import com.example.pvp.mygo.MyGoWeapon;
import com.example.pvp.mygo.MyGoWeaponItemCodec;
import com.example.pvp.mygo.WeaponSession;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * FFA weapon runtime for the independent gun-SkyWars mode.
 *
 * <p>Reason: the SkyWars chest items became real weapons only after a mode
 * owned ammo, cadence, scope and death state. This session deliberately keeps
 * that state separate from MyGO's Dust II rounds and economy while reusing the
 * shared CS weapon math and packet input contract.</p>
 */
public final class SkyWarsGunSession implements WeaponSession {
    private static final class Gun {
        final MyGoWeapon type;
        final MyGoGunAccuracy accuracy;
        ItemStack stack;
        int ammo;
        int reserve;
        int readyAt;
        int reloadAt;

        Gun(ItemStack stack, MyGoWeaponItemCodec.GunData data, Gun previous) {
            this.stack = stack;
            this.type = data.weapon();
            this.accuracy = new MyGoGunAccuracy(this.type);
            this.ammo = data.ammo();
            this.reserve = data.reserve();
            // Replacing an item cancels its pending reload, but must not make
            // an identical gun ready to fire before the old slot's cooldown.
            if (previous != null && previous.type == this.type) this.readyAt = previous.readyAt;
        }

        MyGoWeaponItemCodec.GunData data() {
            return new MyGoWeaponItemCodec.GunData(this.type, this.ammo, this.reserve);
        }

        void cancel() {
            this.reloadAt = 0;
        }
    }

    private static final class Fighter {
        final UUID id;
        /** Gun state follows the inventory slot containing the real gun item. */
        final Map<Integer, Gun> guns = new LinkedHashMap<>();
        double hp = 100;
        boolean alive = true;
        boolean scoped;
        int scopeLevel;
        int scopeUseTick = -100;
        int knifeReady;
        int selected = -1;
        final MyGoScope scope = new MyGoScope();

        Fighter(UUID id) { this.id = id; }
    }

    private final Match match;
    private final Map<UUID, Fighter> fighters = new LinkedHashMap<>();
    private final MyGoLagHistory lagHistory = new MyGoLagHistory();
    private final MyGoUtilities utilities;
    private final Random random;
    private int ticks;
    private boolean started;
    private boolean ended;

    public SkyWarsGunSession(Match match, int seed) {
        this.match = match;
        this.random = new Random(seed * 341873128712L + 0x5EEDL);
        for (ServerPlayerEntity player : match.players()) this.fighters.put(player.getUuid(), new Fighter(player.getUuid()));
        this.utilities = new MyGoUtilities(match.arenaWorld(), this::participants,
                (victim, owner, amount) -> applyDamage(victim, owner, amount, "破片手雷"),
                (victim, owner, amount, kind) -> {
                    if (kind == MyGoUtilities.DamageKind.DECOY) return;
                    applyDamage(victim, owner, amount, kind == MyGoUtilities.DamageKind.FIRE ? "燃烧伤害" : "手雷撞击");
                });
    }

    @Override
    public void start() {
        this.started = true;
        for (ServerPlayerEntity player : participants()) {
            Fighter fighter = fighter(player);
            fighter.hp = 100;
            fighter.alive = true;
            fighter.selected = player.getInventory().selectedSlot;
            player.setHealth(player.getMaxHealth());
            player.changeGameMode(GameMode.SURVIVAL);
            reconcileGuns(player, fighter);
            sync(player, fighter);
        }
    }

    @Override
    public void tick() {
        if (this.ended || !this.started || this.match.getState() != MatchState.ACTIVE) return;
        this.ticks++;
        List<ServerPlayerEntity> players = participants();
        this.lagHistory.capture(this.ticks, players);
        for (ServerPlayerEntity player : players) {
            Fighter fighter = fighter(player);
            if (fighter == null || !fighter.alive) continue;
            onSelectedSlotChanged(player);
            reconcileGuns(player, fighter);
            collectInventory(player, fighter);
            syncGunItems(player, fighter);
            Gun gun = selectedGun(player, fighter);
            if (gun != null) {
                gun.accuracy.update(this.ticks, false, player.isSneaking(), player.isOnGround(), false,
                        Math.clamp(player.getVelocity().horizontalLength() / .215, 0, 1), player.getVelocity().y);
                if (gun.reloadAt > 0 && this.ticks >= gun.reloadAt) finishReload(player, fighter, gun);
                applyCameraRecovery(player, fighter, gun);
            }
            if (this.ticks % 5 == 0) sync(player, fighter);
        }
        this.utilities.tick(this.ticks);
    }

    @Override
    public void onAttackInput(ServerPlayerEntity player, Hand hand) {
        Fighter fighter = fighter(player);
        if (!valid(fighter, player) || hand != Hand.MAIN_HAND) return;
        MyGoUtilities.Type utility = utilityAtSlot(player);
        if (utility != null) {
            throwUtility(player, utility, MyGoUtilities.ThrowMode.STRONG);
            return;
        }
        Gun gun = selectedGun(player, fighter);
        if (gun == null) return;
        // Only real swing packets authorize shots. Vanilla sends them every tick
        // while holding attack on a block, but sends no air-target release packet.
        // Guessing a hold after either one click or a double-click wastes ammo.
        if (gun.reloadAt == 0 && this.ticks >= gun.readyAt) {
            if (gun.ammo <= 0) reload(player);
            else fire(player, fighter, gun);
        }
    }

    @Override
    public void onSelectedSlotChanged(ServerPlayerEntity player) {
        Fighter fighter = fighter(player);
        if (fighter == null || fighter.selected == player.getInventory().selectedSlot) return;
        // Switching away and back must not resume old fire/reload requests.
        for (Gun gun : fighter.guns.values()) gun.cancel();
        closeScope(player, fighter);
        fighter.selected = player.getInventory().selectedSlot;
        sync(player, fighter);
    }

    @Override
    public void reload(ServerPlayerEntity player) {
        Fighter fighter = fighter(player);
        if (!valid(fighter, player)) return;
        Gun gun = selectedGun(player, fighter);
        if (gun == null || gun.reloadAt > 0 || gun.ammo >= gun.type.magazine || gun.reserve <= 0) return;
        closeScope(player, fighter);
        gun.cancel();
        gun.reloadAt = this.ticks + gun.type.reloadTicks;
        actionBar(player, "§e正在换弹");
        sync(player, fighter);
    }

    private void finishReload(ServerPlayerEntity player, Fighter fighter, Gun gun) {
        int load = Math.min(gun.type.magazine - gun.ammo, gun.reserve);
        gun.ammo += load;
        gun.reserve -= load;
        gun.reloadAt = 0;
        syncGunItem(player, player.getInventory().selectedSlot, gun);
        sync(player, fighter);
    }

    @Override
    public ActionResult attack(ServerPlayerEntity player, Entity entity) {
        Fighter attacker = fighter(player);
        if (!valid(attacker, player) || !MyGoWeaponItemCodec.isKnife(player.getMainHandStack())) return ActionResult.FAIL;
        if (!(entity instanceof ServerPlayerEntity victim) || victim == player) return ActionResult.FAIL;
        Fighter target = fighter(victim);
        if (!valid(target, victim) || player.squaredDistanceTo(victim) > 9 || !player.canSee(victim)) return ActionResult.FAIL;
        if (this.ticks < attacker.knifeReady) return ActionResult.FAIL;
        attacker.knifeReady = this.ticks + 10;
        MyGoRules.Damage damage = MyGoKnifeRules.damage(false, false, true, 0);
        applyDamage(victim, player, damage.health(), "战术刀");
        return ActionResult.SUCCESS;
    }

    @Override
    public ActionResult useItem(ServerPlayerEntity player, Hand hand) {
        Fighter fighter = fighter(player);
        if (!valid(fighter, player) || hand != Hand.MAIN_HAND) return ActionResult.FAIL;
        MyGoUtilities.Type utility = utilityAtSlot(player);
        if (utility != null) {
            throwUtility(player, utility, MyGoUtilities.ThrowMode.WEAK);
            return ActionResult.CONSUME;
        }
        Gun gun = selectedGun(player, fighter);
        if (gun == null) {
            ItemStack held = player.getStackInHand(hand);
            return MyGoWeaponItemCodec.isGun(held) || MyGoWeaponItemCodec.isKnife(held)
                    ? ActionResult.FAIL : ActionResult.PASS;
        }
        if (gun.type.scopeLevels() == 0) return ActionResult.FAIL;
        int interval = this.ticks - fighter.scopeUseTick;
        fighter.scopeUseTick = this.ticks;
        if (interval > 5) {
            fighter.scopeLevel = (fighter.scopeLevel + 1) % (gun.type.scopeLevels() + 1);
            fighter.scoped = fighter.scopeLevel > 0;
            if (fighter.scoped) fighter.scope.update(player, fighter.scopeLevel, gun.type.scopeFov(fighter.scopeLevel));
            else fighter.scope.reset(player);
            sync(player, fighter);
        }
        return ActionResult.CONSUME;
    }

    @Override
    public ActionResult useBlock(ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        var block = this.match.arenaWorld().getBlockState(pos).getBlock();
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.ENDER_CHEST
                || block == Blocks.BARREL || block == Blocks.SHULKER_BOX) return ActionResult.PASS;
        return useItem(player, hand);
    }

    @Override
    public ActionResult attackBlock(ServerPlayerEntity player, BlockPos pos) {
        Fighter fighter = fighter(player);
        if (fighter != null && MyGoWeaponItemCodec.isGun(player.getMainHandStack())) return ActionResult.FAIL;
        return ActionResult.PASS;
    }

    @Override
    public boolean drop(ServerPlayerEntity player) {
        Fighter fighter = fighter(player);
        if (!valid(fighter, player)) return true;
        int slot = player.getInventory().selectedSlot;
        MyGoUtilities.Type utility = utilityAtSlot(player);
        if (utility != null) {
            ItemStack held = player.getInventory().getStack(slot);
            held.decrement(1);
            if (held.isEmpty()) player.getInventory().setStack(slot, ItemStack.EMPTY);
            drop(player, utility.stack(1));
            sync(player, fighter);
            return true;
        }
        Gun gun = selectedGun(player, fighter);
        if (gun == null || !MyGoWeaponItemCodec.isGun(player.getInventory().getStack(slot))) return false;
        drop(player, MyGoWeaponItemCodec.encodeGun(gun.type, gun.ammo, gun.reserve));
        player.getInventory().setStack(slot, ItemStack.EMPTY);
        fighter.guns.remove(slot);
        closeScope(player, fighter);
        sync(player, fighter);
        return true;
    }

    private void drop(ServerPlayerEntity player, ItemStack stack) {
        ItemEntity item = new ItemEntity(match.arenaWorld(), player.getX(), player.getY() + .5, player.getZ(), stack);
        item.setVelocity((this.random.nextDouble() - .5) * .2, .2, (this.random.nextDouble() - .5) * .2);
        match.arenaWorld().spawnEntity(item);
    }

    private void throwUtility(ServerPlayerEntity player, MyGoUtilities.Type type,
                              MyGoUtilities.ThrowMode mode) {
        int slot = player.getInventory().selectedSlot;
        ItemStack held = player.getInventory().getStack(slot);
        if (utilityType(held) != type) return;
        held.decrement(1);
        if (held.isEmpty()) player.getInventory().setStack(slot, ItemStack.EMPTY);
        this.utilities.launch(player, type, mode, this.ticks);
        player.currentScreenHandler.sendContentUpdates();
    }

    @Override
    public boolean allowsDamage(LivingEntity victim, net.minecraft.entity.damage.DamageSource source) {
        if (!(victim instanceof ServerPlayerEntity)) return true;
        Entity attacker = source.getAttacker();
        return !(attacker instanceof ServerPlayerEntity || attacker instanceof net.minecraft.entity.projectile.ProjectileEntity);
    }

    @Override
    public void onDeath(ServerPlayerEntity player) {
        Fighter fighter = fighter(player);
        if (fighter == null) return;
        closeScope(player, fighter);
        fighter.alive = false;
        fighter.guns.clear();
    }

    @Override
    public void onEnd() {
        this.ended = true;
        for (ServerPlayerEntity player : participants()) {
            Fighter fighter = fighter(player);
            if (fighter != null) closeScope(player, fighter);
        }
        this.utilities.clear();
        this.lagHistory.clear();
    }

    private void fire(ServerPlayerEntity player, Fighter fighter, Gun gun) {
        if (gun.ammo <= 0 || gun.reloadAt > 0 || this.ticks < gun.readyAt) return;
        gun.ammo--;
        MyGoGunAccuracy.Kick recoil = gun.accuracy.fired(this.ticks, false);
        gun.readyAt = this.ticks + Math.max(1, gun.type.cycleTicks);
        Map<ServerPlayerEntity, MyGoRules.Damage> hits = new LinkedHashMap<>();
        Set<UUID> headshots = new LinkedHashSet<>();
        List<ServerPlayerEntity> enemies = participants().stream()
                .filter(candidate -> candidate != player && valid(fighter(candidate), candidate)).toList();
        for (int pellet = 0; pellet < gun.type.pellets(); pellet++) {
            MyGoGunAccuracy.Offset offset = gun.accuracy.sample(this.random);
            Vec3d direction = Vec3d.fromPolar(player.getPitch() + (float) offset.pitchDegrees(),
                    player.getYaw() + (float) offset.yawDegrees());
            MyGoBulletTrace.Result trace = MyGoBulletTrace.trace(player, gun.type, player.getEyePos(), direction,
                    enemies, this.lagHistory, this.ticks);
            for (MyGoBulletTrace.Hit hit : trace.hits()) {
                Fighter target = fighter(hit.player());
                MyGoRules.Damage previous = hits.getOrDefault(hit.player(), new MyGoRules.Damage(0, 0));
                MyGoRules.Damage damage = MyGoRules.damageWithArmor(gun.type, hit.distance(), hit.part(),
                        0, false, hit.damageScale());
                hits.put(hit.player(), new MyGoRules.Damage(previous.health() + damage.health(), 0));
                if (hit.part() == MyGoRules.Part.HEAD) headshots.add(hit.player().getUuid());
            }
        }
        for (Map.Entry<ServerPlayerEntity, MyGoRules.Damage> entry : hits.entrySet()) {
            MyGoRules.Damage resolved = MyGoRules.roundBulletDamage(entry.getValue());
            applyDamage(entry.getKey(), player, resolved.health(), gun.type.label
                    + (headshots.contains(entry.getKey().getUuid()) ? " 爆头" : ""));
        }
        applyCameraKick(player, gun, recoil);
        syncGunItem(player, player.getInventory().selectedSlot, gun);
        sync(player, fighter);
    }

    private void applyCameraKick(ServerPlayerEntity player, Gun gun, MyGoGunAccuracy.Kick recoil) {
        float yaw = (float) recoil.yawDegrees();
        float kick = (float) recoil.pitchDegrees();
        float pitch = net.minecraft.util.math.MathHelper.clamp(player.getPitch() - kick, -89.9F, 89.9F);
        float pitchDelta = pitch - player.getPitch();
        player.setYaw(player.getYaw() + yaw);
        player.setPitch(pitch);
        gun.accuracy.recordCameraKick(this.ticks, yaw, -pitchDelta);
        player.networkHandler.sendPacket(new PlayerPositionLookS2CPacket(0, 0, 0, yaw, pitchDelta,
                EnumSet.allOf(PositionFlag.class), -1));
    }

    private void applyCameraRecovery(ServerPlayerEntity player, Fighter fighter, Gun gun) {
        MyGoGunAccuracy.Kick recovery = gun.accuracy.recoverCamera(this.ticks);
        if (Math.abs(recovery.yawDegrees()) < 1e-5 && Math.abs(recovery.pitchDegrees()) < 1e-5) return;
        float yaw = (float) recovery.yawDegrees();
        float pitchDelta = (float) -recovery.pitchDegrees();
        player.setYaw(player.getYaw() + yaw);
        player.setPitch(net.minecraft.util.math.MathHelper.clamp(player.getPitch() + pitchDelta, -89.9F, 89.9F));
        player.networkHandler.sendPacket(new PlayerPositionLookS2CPacket(0, 0, 0, yaw, pitchDelta,
                EnumSet.allOf(PositionFlag.class), -1));
    }

    private void applyDamage(ServerPlayerEntity victim, ServerPlayerEntity attacker, double amount, String reason) {
        Fighter target = fighter(victim);
        if (target == null || !target.alive || amount <= 0) return;
        float armor = victim.getArmor();
        float toughness = (float) victim.getAttributeValue(EntityAttributes.GENERIC_ARMOR_TOUGHNESS);
        float protectedDamage = DamageUtil.getDamageLeft(victim, (float) amount,
                victim.getDamageSources().generic(), armor, toughness);
        target.hp = Math.max(0, target.hp - Math.floor(protectedDamage * 5));
        victim.setHealth((float) Math.max(.01, victim.getMaxHealth() * target.hp / 100.0));
        if (target.hp <= 0) {
            sync(victim, target);
            target.alive = false;
            match.eliminate(victim, EliminationCause.DEATH,
                    Text.literal("§7" + victim.getName().getString() + " 被 "
                            + (attacker == null ? "淘汰" : attacker.getName().getString()) + " 击杀（" + reason + "）"));
        }
    }

    private void collectInventory(ServerPlayerEntity player, Fighter fighter) {
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;
            MyGoWeaponItemCodec.GunData gun = MyGoWeaponItemCodec.decodeGun(stack);
            if (gun != null) continue;
            MyGoWeaponItemCodec.AmmoData ammo = MyGoWeaponItemCodec.decodeAmmo(stack);
            if (ammo != null) {
                int left = addAmmo(fighter, player.getInventory().selectedSlot, ammo.category(), ammo.count());
                if (left <= 0) player.getInventory().setStack(slot, ItemStack.EMPTY);
                else stack.setCount(left);
                continue;
            }
        }
    }

    private static MyGoUtilities.Type utilityAtSlot(ServerPlayerEntity player) {
        return utilityType(player.getMainHandStack());
    }

    private static MyGoUtilities.Type utilityType(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CustomModelDataComponent model = stack.get(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (model == null) return null;
        for (MyGoUtilities.Type type : MyGoUtilities.Type.values()) {
            boolean fire = type.fire() && stack.isOf(Items.FIRE_CHARGE);
            boolean throwable = !type.fire() && stack.isOf(Items.SLIME_BALL);
            if ((fire || throwable) && model.value() == type.model) return type;
        }
        return null;
    }

    private int addAmmo(Fighter fighter, int selectedSlot, String category, int count) {
        // Picked-up rounds should fill the gun in hand first, rather than
        // disappear into an unrelated backup gun merely indexed earlier.
        Gun selected = fighter.guns.get(selectedSlot);
        int left = addAmmo(selected, category, count);
        for (Gun gun : fighter.guns.values()) {
            if (gun == selected) continue;
            left = addAmmo(gun, category, left);
            if (left <= 0) break;
        }
        return left;
    }

    private static int addAmmo(Gun gun, String category, int count) {
        if (gun == null || !ammoMatches(gun.type, category)) return count;
        int add = Math.min(count, gun.type.reserve - gun.reserve);
        gun.reserve += add;
        return count - add;
    }

    private static boolean ammoMatches(MyGoWeapon weapon, String category) {
        if (weapon.shotgun()) return category.equals("霰弹枪");
        if (weapon.sniper()) return category.equals("狙击枪");
        if (weapon.smg()) return category.equals("冲锋枪");
        if (weapon.pistol()) return category.equals("手枪");
        if (weapon.category().equals("机枪")) return category.equals("机枪");
        return category.equals("步枪");
    }

    private void sync(ServerPlayerEntity player, Fighter fighter) {
        if (!fighter.alive || this.ended) return;
        reconcileGuns(player, fighter);
        syncGunItems(player, fighter);
        if (fighter.scoped) {
            ItemStack scope = new ItemStack(Items.CARVED_PUMPKIN);
            scope.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(71010));
            scope.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§7MyGO 瞄准镜"));
            player.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, scope);
        } else if (isScopeItem(player.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD))) {
            player.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, ItemStack.EMPTY);
        }
        player.currentScreenHandler.sendContentUpdates();
    }

    private void closeScope(ServerPlayerEntity player, Fighter fighter) {
        fighter.scoped = false;
        fighter.scopeLevel = 0;
        fighter.scope.reset(player);
        if (isScopeItem(player.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD))) {
            player.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, ItemStack.EMPTY);
        }
    }

    /** Rebuild the slot-to-runtime index after normal inventory clicks or moves. */
    private void reconcileGuns(ServerPlayerEntity player, Fighter fighter) {
        Set<Integer> present = new LinkedHashSet<>();
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            MyGoWeaponItemCodec.GunData data = MyGoWeaponItemCodec.decodeGun(stack);
            if (data == null) continue;
            present.add(slot);
            Gun gun = fighter.guns.get(slot);
            // Inventory moves can replace a gun with an identical type/count.
            // Ammo travels in the item; pending input must never travel by slot.
            if (gun == null || gun.stack != stack || gun.type != data.weapon()) {
                fighter.guns.put(slot, new Gun(stack, data, gun));
            } else if (gun.ammo != data.ammo() || gun.reserve != data.reserve()) {
                gun.ammo = data.ammo();
                gun.reserve = data.reserve();
                gun.cancel();
            }
        }
        fighter.guns.keySet().removeIf(slot -> !present.contains(slot));
    }

    private Gun selectedGun(ServerPlayerEntity player, Fighter fighter) {
        int slot = player.getInventory().selectedSlot;
        ItemStack stack = player.getInventory().getStack(slot);
        MyGoWeaponItemCodec.GunData data = MyGoWeaponItemCodec.decodeGun(stack);
        if (data == null) {
            fighter.guns.remove(slot);
            return null;
        }
        Gun gun = fighter.guns.get(slot);
        if (gun == null || gun.stack != stack || gun.type != data.weapon()) {
            gun = new Gun(stack, data, gun);
            fighter.guns.put(slot, gun);
        } else if (gun.ammo != data.ammo() || gun.reserve != data.reserve()) {
            gun.ammo = data.ammo();
            gun.reserve = data.reserve();
            gun.cancel();
        }
        return gun;
    }

    private void syncGunItems(ServerPlayerEntity player, Fighter fighter) {
        for (var iterator = fighter.guns.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<Integer, Gun> entry = iterator.next();
            int slot = entry.getKey();
            if (slot < 0 || slot >= player.getInventory().size()
                    || entry.getValue().stack != player.getInventory().getStack(slot)) {
                iterator.remove();
                continue;
            }
            Gun gun = entry.getValue();
            gun.stack = MyGoWeaponItemCodec.encodeGun(gun.type, gun.ammo, gun.reserve);
            player.getInventory().setStack(slot, gun.stack);
        }
    }

    private void syncGunItem(ServerPlayerEntity player, int slot, Gun gun) {
        if (slot < 0 || slot >= player.getInventory().size()
                || fighter(player).guns.get(slot) != gun
                || gun.stack != player.getInventory().getStack(slot)) return;
        gun.stack = MyGoWeaponItemCodec.encodeGun(gun.type, gun.ammo, gun.reserve);
        player.getInventory().setStack(slot, gun.stack);
    }

    private static boolean isScopeItem(ItemStack stack) {
        CustomModelDataComponent model = stack.get(DataComponentTypes.CUSTOM_MODEL_DATA);
        return !stack.isEmpty() && stack.isOf(Items.CARVED_PUMPKIN) && model != null && model.value() == 71010;
    }

    private boolean valid(Fighter fighter, ServerPlayerEntity player) {
        return fighter != null && fighter.alive && !this.ended && this.started
                && this.match.getState() == MatchState.ACTIVE && !this.match.isEliminated(player.getUuid());
    }

    private Fighter fighter(ServerPlayerEntity player) { return player == null ? null : this.fighters.get(player.getUuid()); }
    private List<ServerPlayerEntity> participants() {
        List<ServerPlayerEntity> result = new ArrayList<>();
        for (ServerPlayerEntity player : this.match.players()) if (player != null && player.getWorld() == this.match.arenaWorld()) result.add(player);
        return result;
    }
    private void actionBar(ServerPlayerEntity player, String message) { player.sendMessage(Text.literal(message), true); }
}
