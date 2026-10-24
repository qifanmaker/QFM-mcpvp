package com.example.pvp.mygo;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.match.Match;
import com.example.pvp.match.MatchState;
import com.example.pvp.match.PortedGameSession;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 新增 MyGO 经济爆破闭环：原版工具仅承载枪械外观，不能走原版工具伤害、护甲或无敌帧。
 * 编辑前审查：成员、存活、回合、整场胜负分别管理；截止 tick 先于引导进度；所有枪包状态由服务端授权。
 * 装备模型、快捷栏和南瓜镜框只是状态投影，移动或伪造物品不会获得另一把枪或多出弹药。
 */
public final class MyGoSession implements PortedGameSession {
    private enum Phase { BUY, LIVE, POST, FINISHED }
    private static final Identifier SPEED_ID = Identifier.of("pvp", "mygo_speed");
    private static final String[] SHOP_IDS = {"mp5", "ak", "m4", "aw", "vest", "helmet", "kit"};
    private static final int[] SHOP_COSTS = {1250, 2700, 3100, 4750, 650, 350, 400};
    private static final String[] SHOP_NAMES = {"MP5 冲锋枪", "AK 步枪", "M4 步枪", "AW 狙击枪", "防弹衣", "头盔升级", "拆包器"};

    private static final class Gun {
        final MyGoWeapon type;
        int ammo, reserve, ready, reloadAt, lastShot;
        double heat;
        final List<Integer> shots = new ArrayList<>();
        Gun(MyGoWeapon type) { this.type = type; refill(); }
        void refill() { this.ammo = this.type.magazine; this.reserve = this.type.reserve; this.heat = 0; cancel(); }
        void cancel() { this.shots.clear(); this.reloadAt = 0; }
    }
    private static final class Fighter {
        final UUID id;
        final int team;
        int cash = MyGoRules.START_MONEY;
        double hp = 100;
        boolean alive, vest, helmet, kit, scoped;
        Gun primary, pistol = new Gun(MyGoWeapon.P9);
        int selected = -1, aimSince, stoppedAt, knifeReady;
        double speed = Double.NaN;
        Vec3d last = Vec3d.ZERO;
        ItemStack oldHead = ItemStack.EMPTY;
        Fighter(UUID id, int team) { this.id = id; this.team = team; }
        Gun gun(int slot) { return slot == 0 ? this.primary : slot == 1 ? this.pistol : null; }
        void cancel() { if (this.primary != null) this.primary.cancel(); this.pistol.cancel(); }
    }
    private static final class GroundGun {
        final Gun gun;
        final BlockPos pos;
        final int pickupAt;
        final ArmorStandEntity marker;
        GroundGun(Gun gun, BlockPos pos, int pickupAt, ArmorStandEntity marker) {
            this.gun = gun; this.pos = pos; this.pickupAt = pickupAt; this.marker = marker;
        }
    }
    private record Channel(UUID player, BlockPos target, Vec3d origin, int completeAt, boolean planting) { }

    private final Match match;
    private final MyGoArena map;
    private final Map<UUID, Fighter> fighters = new LinkedHashMap<>();
    private final Set<UUID> active = new LinkedHashSet<>();
    private final List<BlockPos> initialSpawns = new ArrayList<>();
    private final List<GroundGun> groundGuns = new ArrayList<>();
    private final List<ArmorStandEntity> labels = new ArrayList<>();
    private final Map<UUID, Entity> ownedEntities = new HashMap<>();
    private final Random random;
    private final int[] scores = new int[2], losses = new int[2];
    private final ServerBossBar timer = new ServerBossBar(Text.literal("MyGO · 购买阶段"), BossBar.Color.BLUE, BossBar.Style.PROGRESS);
    private Phase phase = Phase.BUY;
    private int ticks, deadline, bombPickupAt, round = 1;
    private boolean ended, finalResult, bombPlanted;
    private Integer winningTeam;
    private UUID bombCarrier;
    private BlockPos bombGround, bombPosition;
    private ArmorStandEntity bombMarker;
    private Channel channel;

    public MyGoSession(Match match, ArenaTemplate template, int regionIndex, int seed) {
        this.match = match;
        this.map = new MyGoArena(template, regionIndex, seed, match.players().size() <= 6);
        this.random = new Random(seed * 341873128712L + 1927);
        int half = match.players().size() / 2;
        for (int i = 0; i < match.players().size(); i++) {
            ServerPlayerEntity player = match.players().get(i);
            int team = i < half ? 0 : 1;
            this.fighters.put(player.getUuid(), new Fighter(player.getUuid(), team));
            this.active.add(player.getUuid());
            this.initialSpawns.add(this.map.spawn(team == 0, i % Math.max(1, half)));
        }
    }

    @Override public List<BlockPos> spawnPositions() { return List.copyOf(this.initialSpawns); }
    @Override public void stageMap() { this.map.generate(this.match.arenaWorld()); }

    @Override public void start() {
        for (ServerPlayerEntity player : participants()) {
            Fighter fighter = fighter(player);
            fighter.oldHead = player.getEquippedStack(EquipmentSlot.HEAD).copy();
            this.timer.addPlayer(player);
            player.sendMessage(Text.literal("§6MyGO 经济爆破 · 快速局先赢 7 回合，第 6 回合换边，6:6 平局。"), false);
            player.sendMessage(Text.literal("§e右键开火 · 潜行瞄准 · F 换弹 · Q 丢主枪/C4 · 购买器右键商店。"), false);
            player.sendMessage(Text.literal("§7携 C4 到包点，选择第 6 格，潜行右键安装；防守方同样操作拆包。阵亡后在封闭室等待下一回合。"), false);
        }
        for (int i = 0; i < this.map.sites().size(); i++) label(this.map.sites().get(i).up(3), "§6爆破点 " + (i == 0 ? "A" : "B"));
        beginRound(true);
    }

    @Override public void tick() {
        if (this.ended || this.match.getState() != MatchState.ACTIVE) return;
        this.ticks++;
        for (UUID id : List.copyOf(this.active)) {
            ServerPlayerEntity player = this.match.onlinePlayer(id);
            if (player == null || !this.match.contains(id)) {
                remove(id, player);
                if (this.ended) return;
            }
        }
        if (this.phase == Phase.POST && this.ticks >= this.deadline) { beginRound(false); return; }
        if (this.phase == Phase.BUY && this.ticks >= this.deadline) {
            this.phase = Phase.LIVE;
            this.deadline = this.ticks + MyGoRules.ROUND_TICKS;
            announce("§c开始行动！进攻方安装 C4，防守方阻止或拆除。");
            for (ServerPlayerEntity p : participants()) p.closeHandledScreen();
        }
        // 截止时间优先于引导完成，防止压线拆包和超时安装改变已经产生的胜负。
        if (this.phase == Phase.LIVE) {
            MyGoRules.Outcome timed = MyGoRules.timedOutcome(this.bombPlanted, this.ticks, this.deadline);
            if (timed != null) {
                if (this.bombPlanted) {
                    soundAt(this.bombPosition, "explode", 2);
                    Vec3d blast = Vec3d.ofCenter(this.bombPosition);
                    this.match.arenaWorld().spawnParticles(ParticleTypes.EXPLOSION_EMITTER, blast.x, blast.y, blast.z, 1, 0, 0, 0, 0);
                    // C4 只影响本局虚拟存活和下回合保枪，不调用原版爆炸破坏共享世界。
                    for (ServerPlayerEntity p : participants()) if (fighter(p).alive && near(p, this.bombPosition, 12)) kill(p, null, "C4 爆炸");
                }
                resolve(timed, this.bombPlanted ? "C4 爆炸" : "进攻超时");
                return;
            }
        }
        for (ServerPlayerEntity player : participants()) {
            Fighter fighter = fighter(player);
            player.setFireTicks(0);
            player.fallDistance = 0;
            if (this.ticks % 20 == 0) player.getHungerManager().setFoodLevel(20);
            if (!fighter.alive) {
                if (player.getWorld() != this.match.arenaWorld() || player.squaredDistanceTo(Vec3d.ofCenter(this.map.spectator())) > 16)
                    teleport(player, this.map.spectator(), 0);
                if (this.ticks % 40 == 0) player.sendMessage(Text.literal("§7阵亡，等待下一回合 · 队友：" +
                        this.fighters.values().stream().filter(f -> f.team == fighter.team && this.active.contains(f.id) && f.alive)
                                .map(f -> { ServerPlayerEntity teammate = this.match.onlinePlayer(f.id); return (teammate == null ? "队友" : teammate.getName().getString())
                                        + " " + String.format(Locale.ROOT, "%.0f", f.hp) + "HP"; }).reduce((a, b) -> a + " / " + b).orElse("全部阵亡")), true);
                continue;
            }
            // 物理血条仅映射虚拟 HP；持续覆盖可阻止饱食回血改变枪械模式的 100 HP 规则。
            float displayHealth = (float) Math.max(.01, player.getMaxHealth() * fighter.hp / 100);
            if (Math.abs(player.getHealth() - displayHealth) > .001) player.setHealth(displayHealth);
            if (player.getWorld() != this.match.arenaWorld() || !this.map.inBounds(player.getBlockPos())) {
                if (this.phase == Phase.LIVE) kill(player, null, "离开战场");
                else teleportHome(player);
                continue;
            }
            if (this.phase == Phase.BUY && !this.map.inBuyZone(player.getBlockPos(), attackers(fighter))) teleportHome(player);
            onSelectedSlotChanged(player);
            int selected = player.getInventory().selectedSlot;
            boolean moving = player.getPos().squaredDistanceTo(fighter.last) > .0004;
            if (moving || player.isSprinting()) fighter.stoppedAt = this.ticks;
            fighter.last = player.getPos();
            boolean scope = this.phase == Phase.LIVE && player.isSneaking() && fighter.gun(selected) != null;
            if (scope != fighter.scoped) { fighter.scoped = scope; fighter.aimSince = this.ticks; updateHead(player, fighter); }
            updateSpeed(player, fighter);
            if (this.phase == Phase.LIVE || this.phase == Phase.BUY) tickGun(player, fighter, fighter.gun(selected));
            if (this.ticks % 10 == 0) sendHud(player, fighter);
            // 实际枪械身份存于 Fighter，整理显示背包不会刷新同一物品、不会重启每 tick 挥手动画。
            if (this.ticks % 20 == 0) syncEquipment(player, fighter);
        }
        if (this.phase == Phase.LIVE) {
            checkElimination();
            if (this.phase == Phase.LIVE) { tickChannel(); tickPickups(); }
        }
        if (this.ticks % 10 == 0) updateBar();
    }

    private void beginRound(boolean first) {
        clearRoundEntities();
        this.bombPlanted = false; this.bombPosition = null; this.bombGround = null; this.bombCarrier = null; this.channel = null;
        this.phase = Phase.BUY; this.deadline = this.ticks + MyGoRules.BUY_TICKS;
        boolean halftime = this.round == 7;
        for (ServerPlayerEntity player : participants()) {
            Fighter f = fighter(player);
            if (first || halftime) { f.cash = MyGoRules.START_MONEY; f.primary = null; f.vest = false; f.helmet = false; f.kit = false; }
            if (!f.alive && !first) { f.primary = null; f.vest = false; f.helmet = false; f.kit = false; }
            f.alive = true; f.hp = 100; f.pistol.refill(); if (f.primary != null) f.primary.refill();
            f.scoped = false; f.selected = -1; f.aimSince = this.ticks; f.stoppedAt = this.ticks;
            player.closeHandledScreen(); player.changeGameMode(GameMode.ADVENTURE); player.clearStatusEffects();
            player.setHealth(player.getMaxHealth()); player.setVelocity(Vec3d.ZERO);
            player.getInventory().clear(); player.getInventory().selectedSlot = f.primary == null ? 1 : 0;
            // 回合复位会切到主枪；必须同步客户端，否则画面仍是副枪而服务端实际开主枪。
            player.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(player.getInventory().selectedSlot));
            player.equipStack(EquipmentSlot.CHEST, ItemStack.EMPTY); player.equipStack(EquipmentSlot.LEGS, ItemStack.EMPTY);
            player.equipStack(EquipmentSlot.FEET, ItemStack.EMPTY);
            teleportHome(player); updateHead(player, f);
        }
        if (halftime) { this.losses[0] = 0; this.losses[1] = 0; announce("§e半场换边，现金与装备重置。"); }
        List<Fighter> attack = this.fighters.values().stream().filter(f -> this.active.contains(f.id) && attackers(f)).toList();
        if (!attack.isEmpty()) this.bombCarrier = attack.get(this.random.nextInt(attack.size())).id;
        for (ServerPlayerEntity player : participants()) syncEquipment(player, fighter(player));
        announce("§6第 " + this.round + " 回合 · " + this.scores[0] + ":" + this.scores[1] + " · 购买时间 10 秒");
        for (ServerPlayerEntity player : participants()) player.sendMessage(Text.literal(attackers(fighter(player))
                ? "§c本回合：进攻方" + (player.getUuid().equals(this.bombCarrier) ? " · 你携带 C4" : "") : "§9本回合：防守方"), false);
        updateBar(); checkTeams();
    }

    private void tickGun(ServerPlayerEntity player, Fighter f, Gun gun) {
        if (gun == null) return;
        if (gun.reloadAt > 0 && this.ticks >= gun.reloadAt) {
            int amount = Math.min(gun.type.magazine - gun.ammo, gun.reserve);
            gun.ammo += amount; gun.reserve -= amount; gun.reloadAt = 0;
            syncEquipment(player, f);
        }
        while (!gun.shots.isEmpty() && gun.shots.getFirst() <= this.ticks) {
            gun.shots.removeFirst();
            if (gun.ammo > 0 && f.alive && this.phase == Phase.LIVE) fire(player, f, gun);
        }
    }

    private void trigger(ServerPlayerEntity player, Fighter f, Gun gun) {
        if (this.phase != Phase.LIVE || !f.alive || gun.reloadAt > 0 || this.channel != null && this.channel.player().equals(f.id)) return;
        if (this.ticks < gun.ready) return;
        if (gun.ammo <= 0) { gun.ready = this.ticks + 8; sound(player, "empty", .7F); player.sendMessage(Text.literal("§e弹匣已空，按 F 换弹。"), true); return; }
        gun.ready = this.ticks + gun.type.cycleTicks;
        for (int offset : gun.type.burstOffsets()) gun.shots.add(this.ticks + offset);
        tickGun(player, f, gun);
    }

    private void fire(ServerPlayerEntity player, Fighter f, Gun gun) {
        gun.ammo--;
        gun.heat = Math.max(0, gun.heat - (this.ticks - gun.lastShot) * .045);
        double spread = gun.type == MyGoWeapon.AW ? 5.5 : gun.type == MyGoWeapon.MP5 ? .8 : .55;
        spread += gun.heat;
        if (f.scoped) spread *= gun.type == MyGoWeapon.AW && this.ticks - f.aimSince >= 8 ? .008 : .35;
        if (this.ticks - f.stoppedAt < 3) spread *= gun.type == MyGoWeapon.MP5 ? 2.5 : 5;
        if (!player.isOnGround()) spread += 7;
        if (player.isSprinting()) spread += 5;
        gun.heat = Math.min(1.6, gun.heat + .13); gun.lastShot = this.ticks;
        Vec3d from = player.getEyePos();
        Vec3d direction = Vec3d.fromPolar(player.getPitch() + (float) (this.random.nextGaussian() * spread),
                player.getYaw() + (float) (this.random.nextGaussian() * spread));
        Vec3d max = from.add(direction.multiply(gun.type.maxRange));
        BlockHitResult block = this.match.arenaWorld().raycast(new RaycastContext(from, max,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        Vec3d end = block.getPos();
        double nearest = from.squaredDistanceTo(end);
        ServerPlayerEntity victim = null;
        Vec3d hitPoint = null;
        for (ServerPlayerEntity candidate : participants()) {
            Fighter target = fighter(candidate);
            if (!target.alive || target.team == f.team || candidate.getWorld() != this.match.arenaWorld()) continue;
            Optional<Vec3d> intersection = candidate.getBoundingBox().raycast(from, end);
            if (intersection.isPresent() && from.squaredDistanceTo(intersection.get()) < nearest) {
                nearest = from.squaredDistanceTo(intersection.get()); victim = candidate; hitPoint = intersection.get();
            }
        }
        soundAt(player.getBlockPos(), gun.type.label.toLowerCase(Locale.ROOT), 1);
        Vec3d finish = hitPoint == null ? end : hitPoint;
        Vec3d delta = finish.subtract(from);
        // 单发最多八个示踪包；十人同时开火时避免把命中反馈放大为每秒数万小包。
        int particles = Math.min(8, (int) delta.length() * 2);
        for (int i = 1; i <= particles; i++) {
            Vec3d point = from.add(delta.multiply((double) i / particles));
            this.match.arenaWorld().spawnParticles(ParticleTypes.CRIT, point.x, point.y, point.z, 1, 0, 0, 0, 0);
        }
        if (victim != null) {
            Box box = victim.getBoundingBox();
            double fraction = (hitPoint.y - box.minY) / box.getLengthY();
            MyGoRules.Part part = fraction >= .75 ? MyGoRules.Part.HEAD : fraction <= .38 ? MyGoRules.Part.LEGS : MyGoRules.Part.BODY;
            Fighter target = fighter(victim);
            double amount = MyGoRules.damage(gun.type, Math.sqrt(nearest), part, target.vest, target.helmet);
            damage(victim, player, amount, part == MyGoRules.Part.HEAD ? "爆头" : gun.type.label);
            if (!this.ended) player.sendMessage(Text.literal("§a命中 " + (part == MyGoRules.Part.HEAD ? "头部" : part == MyGoRules.Part.LEGS ? "腿部" : "躯干")
                    + " §f" + String.format(Locale.ROOT, "%.1f", amount)), true);
        }
        if (!this.ended) syncEquipment(player, f);
    }

    private void damage(ServerPlayerEntity victim, ServerPlayerEntity attacker, double amount, String reason) {
        Fighter f = fighter(victim);
        if (f == null || !f.alive || this.phase != Phase.LIVE || amount <= 0) return;
        f.hp = Math.max(0, f.hp - amount);
        if (f.hp <= .0000001) kill(victim, attacker, reason);
        else victim.sendMessage(Text.literal("§c受到 " + String.format(Locale.ROOT, "%.1f", amount) + " 伤害 · "
                + String.format(Locale.ROOT, "%.1f", f.hp) + " HP"), true);
    }

    private void kill(ServerPlayerEntity victim, ServerPlayerEntity attacker, String reason) {
        Fighter f = fighter(victim);
        if (f == null || !f.alive) return;
        f.alive = false; f.hp = 0; f.cancel();
        if (this.channel != null && this.channel.player().equals(f.id)) cancelChannel();
        if (f.id.equals(this.bombCarrier)) dropBomb(victim.getBlockPos());
        if (f.primary != null) { dropGun(f.primary, victim.getBlockPos()); f.primary = null; }
        f.vest = false; f.helmet = false; f.kit = false; f.scoped = false;
        victim.closeHandledScreen(); victim.getInventory().clear(); updateHead(victim, f); removeSpeed(victim, f);
        victim.changeGameMode(GameMode.ADVENTURE); victim.setHealth(victim.getMaxHealth());
        teleport(victim, this.map.spectator(), 0);
        if (attacker != null && fighter(attacker) != null && fighter(attacker).team != f.team) {
            Fighter killer = fighter(attacker); killer.cash = MyGoRules.addMoney(killer.cash, 300);
        }
        announce("§7" + victim.getName().getString() + " 阵亡" + (attacker == null ? "（" + reason + "）"
                : " ← " + attacker.getName().getString() + "（" + reason + "）"));
        // 延迟到会话 tick 末检查队伍，防止一次攻击在死亡装备清理途中触发整场收尾。
    }

    public void reload(ServerPlayerEntity player) {
        Fighter f = fighter(player); if (f == null || !f.alive || this.ended || this.phase == Phase.POST) return;
        Gun gun = f.gun(player.getInventory().selectedSlot);
        if (gun == null || gun.reloadAt > 0 || gun.ammo >= gun.type.magazine || gun.reserve <= 0) return;
        gun.shots.clear(); gun.reloadAt = this.ticks + gun.type.reloadTicks;
        sound(player, "reload", .8F); player.sendMessage(Text.literal("§e正在换弹，切枪会取消。"), true);
    }

    /** 逐个处理选槽包，防止同 tick 来回切枪绕过待发子弹、换弹与安装/拆除取消规则。 */
    public void onSelectedSlotChanged(ServerPlayerEntity player) {
        Fighter f = fighter(player);
        if (f == null || this.ended || player.getInventory().selectedSlot == f.selected) return;
        f.cancel();
        f.selected = player.getInventory().selectedSlot;
        f.aimSince = this.ticks;
        f.scoped = false;
        updateHead(player, f);
        if (this.channel != null && this.channel.player().equals(f.id)) cancelChannel();
    }

    public ActionResult attack(ServerPlayerEntity player, Entity entity) {
        Fighter f = fighter(player);
        if (f == null) return ActionResult.PASS;
        if (!f.alive || this.phase != Phase.LIVE || player.getInventory().selectedSlot != 2 || this.ticks < f.knifeReady) return ActionResult.FAIL;
        f.knifeReady = this.ticks + 12;
        if (entity instanceof ServerPlayerEntity target && fighter(target) != null && fighter(target).team != f.team
                && fighter(target).alive && target.getWorld() == player.getWorld() && player.squaredDistanceTo(target) <= 9
                && player.canSee(target)) damage(target, player, 40, "战术刀");
        return ActionResult.SUCCESS;
    }

    @Override public ActionResult useItem(ServerPlayerEntity player, Hand hand) {
        Fighter f = fighter(player);
        if (f == null) return ActionResult.PASS;
        if (hand != Hand.MAIN_HAND || !f.alive || this.ended) return ActionResult.FAIL;
        int slot = player.getInventory().selectedSlot;
        if (slot == 8) { openShop(player); return ActionResult.SUCCESS; }
        if (slot == 5) { beginChannel(player, f); return ActionResult.SUCCESS; }
        Gun gun = f.gun(slot);
        if (gun != null) { trigger(player, f, gun); return ActionResult.SUCCESS; }
        return ActionResult.FAIL;
    }
    @Override public ActionResult useBlock(ServerPlayerEntity player, Hand hand, BlockHitResult hit) { return useItem(player, hand); }
    @Override public ActionResult attackBlock(ServerPlayerEntity player, BlockPos pos) { return ActionResult.FAIL; }
    @Override public boolean allowsDamage(LivingEntity victim, DamageSource source) { return false; }

    private void beginChannel(ServerPlayerEntity player, Fighter f) {
        if (this.phase != Phase.LIVE || !f.alive) return;
        if (!player.isSneaking()) { player.sendMessage(Text.literal("§e请保持潜行，再右键开始安装/拆除。"), true); return; }
        if (this.channel != null) {
            if (this.channel.player().equals(f.id)) return;
            player.sendMessage(Text.literal("§e已有玩家正在操作 C4。"), true); return;
        }
        boolean planting = !this.bombPlanted;
        BlockPos target = planting ? this.map.siteAt(player.getBlockPos()) : this.bombPosition;
        if (planting && (!attackers(f) || !f.id.equals(this.bombCarrier) || target == null)) {
            player.sendMessage(Text.literal("§e持有 C4 的进攻方可在橙色/黄色包点安装。"), true); return;
        }
        if (!planting && (attackers(f) || target == null || !near(player, target, 3))) return;
        int duration = planting ? MyGoRules.PLANT_TICKS : f.kit ? MyGoRules.KIT_DEFUSE_TICKS : MyGoRules.DEFUSE_TICKS;
        f.cancel(); this.channel = new Channel(f.id, planting ? player.getBlockPos() : target, player.getPos(), this.ticks + duration, planting);
        soundAt(player.getBlockPos(), planting ? "plant" : "defuse", 1);
        player.sendMessage(Text.literal("§e正在" + (planting ? "安装 C4" : "拆包") + "，保持潜行、站定并持有第 6 格。"), true);
    }

    private void tickChannel() {
        if (this.channel == null || this.phase != Phase.LIVE) return;
        Channel action = this.channel;
        ServerPlayerEntity p = this.match.onlinePlayer(action.player());
        Fighter f = this.fighters.get(action.player());
        if (p == null || f == null || !this.active.contains(f.id) || !f.alive || !p.isSneaking()
                || p.getInventory().selectedSlot != 5 || p.getPos().squaredDistanceTo(action.origin()) > .1225
                || action.planting() && (!f.id.equals(this.bombCarrier) || this.map.siteAt(p.getBlockPos()) == null)
                || !action.planting() && (!this.bombPlanted || !near(p, this.bombPosition, 3))) { cancelChannel(); return; }
        if (this.ticks < action.completeAt()) {
            if (this.ticks % 5 == 0) p.sendMessage(Text.literal("§e" + (action.planting() ? "安装" : "拆除") + "剩余 "
                    + String.format(Locale.ROOT, "%.1f", (action.completeAt() - this.ticks) / 20.0) + " 秒"), true);
            return;
        }
        this.channel = null;
        if (action.planting()) {
            this.bombPlanted = true; this.bombPosition = action.target(); this.bombCarrier = null;
            this.deadline = this.ticks + MyGoRules.BOMB_TICKS;
            f.cash = MyGoRules.addMoney(f.cash, 300); setBombMarker(this.bombPosition, "§c已安装 C4 · 防守方潜行右键第 6 格拆除");
            announce("§cC4 已安装！40 秒后爆炸，进攻方全灭也会继续计时。"); syncEquipment(p, f);
        } else { f.cash = MyGoRules.addMoney(f.cash, 300); resolve(MyGoRules.Outcome.DEFENDERS, "C4 已拆除"); }
    }

    private void cancelChannel() { this.channel = null; }

    /** 原版 Q 由入口取消，装备掉落由本会话记录，避免复制枪械或把 C4 丢入虚空后卡死。 */
    public boolean drop(ServerPlayerEntity player) {
        Fighter f = fighter(player); if (f == null) return false;
        if (!f.alive || this.ended || this.phase == Phase.POST) return true;
        int slot = player.getInventory().selectedSlot;
        if (slot == 0 && f.primary != null) { dropGun(f.primary, player.getBlockPos()); f.primary = null; }
        else if (slot == 5 && f.id.equals(this.bombCarrier)) dropBomb(player.getBlockPos());
        syncEquipment(player, f); return true;
    }

    private void dropGun(Gun gun, BlockPos pos) {
        gun.cancel(); BlockPos safe = this.map.safeDrop(pos);
        ArmorStandEntity marker = label(safe, "§e" + gun.type.label + " · 空主枪槽靠近拾取");
        this.groundGuns.add(new GroundGun(gun, safe, this.ticks + 20, marker));
    }
    private void dropBomb(BlockPos pos) {
        this.bombCarrier = null; this.bombGround = this.map.safeDrop(pos); cancelChannel();
        this.bombPickupAt = this.ticks + 20;
        setBombMarker(this.bombGround, "§6C4 · 进攻方靠近拾取");
    }
    private void tickPickups() {
        for (ServerPlayerEntity p : participants()) {
            Fighter f = fighter(p); if (!f.alive) continue;
            if (!this.bombPlanted && this.bombCarrier == null && this.bombGround != null && this.ticks >= this.bombPickupAt && attackers(f) && near(p, this.bombGround, 1.6)) {
                this.bombCarrier = f.id; this.bombGround = null; removeBombMarker(); syncEquipment(p, f);
                p.sendMessage(Text.literal("§6已拾取 C4。"), true);
            }
            if (f.primary != null) continue;
            for (GroundGun drop : List.copyOf(this.groundGuns)) {
                if (this.ticks >= drop.pickupAt && near(p, drop.pos, 1.6)) {
                    f.primary = drop.gun; this.groundGuns.remove(drop); discard(drop.marker); syncEquipment(p, f); break;
                }
            }
        }
    }

    public boolean buy(ServerPlayerEntity player, String id) {
        Fighter f = fighter(player);
        if (f == null || !f.alive || this.ended || this.phase != Phase.BUY || !this.map.inBuyZone(player.getBlockPos(), attackers(f))) {
            player.sendMessage(Text.literal("§c只能在购买阶段、本方出生区购买。"), true); return false;
        }
        String key = id.toLowerCase(Locale.ROOT);
        if ("defuser".equals(key) || "defuse".equals(key)) key = "kit";
        MyGoWeapon type = MyGoWeapon.parse(key);
        if (type == MyGoWeapon.P9) { player.sendMessage(Text.literal("§eP9 是每回合自带的免费副枪，按 2 切换。"), true); return false; }
        int cost;
        if (type != null && type != MyGoWeapon.P9) {
            if (f.primary != null) { player.sendMessage(Text.literal("§e已有主枪，请先按 Q 丢下再购买。"), true); return false; }
            cost = type.price;
        } else if ("vest".equals(key)) {
            if (f.vest) return false; cost = 650;
        } else if ("helmet".equals(key)) {
            if (!f.vest || f.helmet) { player.sendMessage(Text.literal("§e需要先购买防弹衣，头盔不能重复购买。"), true); return false; }
            cost = 350;
        } else if ("kit".equals(key)) {
            if (attackers(f) || f.kit) { player.sendMessage(Text.literal("§e拆包器仅防守方可购买一次。"), true); return false; }
            cost = 400;
        } else { player.sendMessage(Text.literal("§c未知商品。可购 mp5、ak、m4、aw、vest、helmet、kit。"), false); return false; }
        if (f.cash < cost) { player.sendMessage(Text.literal("§c现金不足，需要 $" + cost + "，现有 $" + f.cash), true); return false; }
        f.cash -= cost;
        if (type != null) f.primary = new Gun(type);
        else if ("vest".equals(key)) f.vest = true;
        else if ("helmet".equals(key)) f.helmet = true;
        else f.kit = true;
        syncEquipment(player, f); player.sendMessage(Text.literal("§a购买成功 · 余额 $" + f.cash), true); return true;
    }

    public void openShop(ServerPlayerEntity player) {
        Fighter f = fighter(player);
        if (f == null || !f.alive || this.phase != Phase.BUY || this.ended) {
            player.sendMessage(Text.literal("§e商店只在每回合开始的 10 秒购买阶段开放。"), true); return;
        }
        player.openHandledScreen(new NamedScreenHandlerFactory() {
            @Override public Text getDisplayName() { return Text.literal("MyGO 商店 · $" + f.cash); }
            @Override public ScreenHandler createMenu(int syncId, PlayerInventory inventory, PlayerEntity p) { return new ShopHandler(syncId, inventory); }
        });
    }

    private final class ShopHandler extends ScreenHandler {
        private final SimpleInventory inventory = new SimpleInventory(27);
        ShopHandler(int syncId, PlayerInventory player) {
            super(ScreenHandlerType.GENERIC_9X3, syncId);
            for (int i = 0; i < 27; i++) addSlot(new Slot(this.inventory, i, 8 + i % 9 * 18, 18 + i / 9 * 18));
            for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++) addSlot(new Slot(player, 9 + row * 9 + col, 8 + col * 18, 86 + row * 18));
            for (int i = 0; i < 9; i++) addSlot(new Slot(player, i, 8 + i * 18, 144));
            for (int i = 0; i < SHOP_IDS.length; i++) {
                MyGoWeapon weapon = MyGoWeapon.parse(SHOP_IDS[i]);
                ItemStack icon = weapon != null ? gunStack(new Gun(weapon)) : named(i == 4 ? Items.LEATHER_CHESTPLATE : i == 5 ? Items.LEATHER_HELMET : Items.SHEARS, SHOP_NAMES[i]);
                icon.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§e" + SHOP_NAMES[i] + " · $" + SHOP_COSTS[i]));
                icon.set(DataComponentTypes.LORE, new LoreComponent(List.of(Text.literal("§7点击购买；已有主枪请先 Q 丢下"))));
                this.inventory.setStack(i, icon);
            }
        }
        @Override public void onSlotClick(int index, int button, SlotActionType action, PlayerEntity p) {
            if (p instanceof ServerPlayerEntity sp && index >= 0 && index < SHOP_IDS.length
                    && (action == SlotActionType.PICKUP || action == SlotActionType.QUICK_MOVE)) buy(sp, SHOP_IDS[index]);
        }
        @Override public boolean canUse(PlayerEntity p) { Fighter f = fighters.get(p.getUuid()); return !ended && phase == Phase.BUY && f != null && f.alive; }
        @Override public ItemStack quickMove(PlayerEntity player, int index) { return ItemStack.EMPTY; }
    }

    private void resolve(MyGoRules.Outcome outcome, String reason) {
        if (this.phase != Phase.LIVE || this.ended) return;
        int attackerTeam = MyGoRules.switchedSides(this.round) ? 1 : 0;
        int winner = outcome == MyGoRules.Outcome.ATTACKERS ? attackerTeam : 1 - attackerTeam;
        this.phase = Phase.POST; this.deadline = this.ticks + MyGoRules.POST_TICKS; cancelChannel(); this.scores[winner]++;
        this.losses[winner] = 0; this.losses[1 - winner]++;
        for (ServerPlayerEntity p : participants()) {
            Fighter f = fighter(p); f.cancel(); f.scoped = false; updateHead(p, f);
            int reward = f.team == winner ? 3250 : MyGoRules.lossReward(this.losses[f.team]);
            if (outcome == MyGoRules.Outcome.DEFENDERS && !this.bombPlanted && "进攻超时".equals(reason) && attackers(f) && f.alive) reward = 0;
            if (this.bombPlanted && f.team != winner && attackers(f)) reward += 600;
            f.cash = MyGoRules.addMoney(f.cash, reward);
            p.sendMessage(Text.literal("§6回合" + (f.team == winner ? "胜利" : "失败") + " · " + reason + " · +$" + reward), false);
        }
        announce("§6回合结束：队伍 " + (winner + 1) + " 获胜 · " + this.scores[0] + ":" + this.scores[1]);
        if (MyGoRules.finished(this.scores[0], this.scores[1])) {
            Integer win = this.scores[0] == this.scores[1] ? null : this.scores[0] > this.scores[1] ? 0 : 1;
            finish(win, win == null ? "§eMyGO 6:6 平局。" : "§6MyGO 队伍 " + (win + 1) + " 获胜！");
        } else this.round++;
        updateBar();
    }

    private void checkElimination() {
        if (this.phase != Phase.LIVE) return;
        int a = 0, d = 0;
        for (Fighter f : this.fighters.values()) if (this.active.contains(f.id) && f.alive) { if (attackers(f)) a++; else d++; }
        MyGoRules.Outcome outcome = MyGoRules.eliminationOutcome(this.bombPlanted, a, d);
        if (outcome != null) resolve(outcome, a == 0 ? "进攻方全灭" : "防守方全灭");
    }

    private void checkTeams() {
        if (this.ended) return;
        long first = this.active.stream().filter(id -> this.fighters.get(id).team == 0).count();
        long second = this.active.size() - first;
        if (first == 0 || second == 0) finish(first == 0 && second == 0 ? null : first > 0 ? 0 : 1, "§e有一方全部离场，MyGO 结束。");
    }
    private void finish(Integer winner, String message) {
        if (this.ended) return;
        this.winningTeam = winner; this.finalResult = true; this.phase = Phase.FINISHED;
        announce(message); this.match.finishMatch(null);
    }
    @Override public Set<UUID> winners() {
        if (this.winningTeam == null) return Set.of();
        Set<UUID> result = new LinkedHashSet<>();
        for (Fighter f : this.fighters.values()) if (this.active.contains(f.id) && f.team == this.winningTeam) result.add(f.id);
        return result;
    }
    @Override public boolean hasFinalResult() { return this.finalResult; }
    @Override public void onDeath(ServerPlayerEntity player) { if (!this.ended) kill(player, null, "环境异常"); }
    @Override public void onLeave(ServerPlayerEntity player) { remove(player.getUuid(), player); }
    private void remove(UUID id, ServerPlayerEntity player) {
        if (this.ended) return;
        if (!this.active.remove(id)) return;
        Fighter f = this.fighters.get(id); f.cancel();
        if (id.equals(this.bombCarrier)) dropBomb(player == null ? this.map.center().up() : player.getBlockPos());
        if (this.channel != null && this.channel.player().equals(id)) cancelChannel();
        if (!this.ended && this.phase == Phase.LIVE && f.alive && f.primary != null) {
            dropGun(f.primary, player == null ? this.map.center().up() : player.getBlockPos()); f.primary = null;
        }
        if (player != null) {
            this.timer.removePlayer(player); player.closeHandledScreen(); player.getInventory().clear();
            player.currentScreenHandler.setCursorStack(ItemStack.EMPTY); restoreHead(player, f); removeSpeed(player, f);
        }
        f.alive = false; checkTeams();
    }
    @Override public void onEnd() {
        if (this.ended) return;
        this.ended = true; this.channel = null;
        for (Fighter f : this.fighters.values()) {
            f.cancel(); ServerPlayerEntity p = this.match.onlinePlayer(f.id);
            if (p != null && this.active.contains(f.id)) { p.closeHandledScreen(); restoreHead(p, f); removeSpeed(p, f); }
        }
        this.timer.clearPlayers(); clearRoundEntities();
        for (ArmorStandEntity label : this.labels) label.discard();
        this.labels.clear(); this.ownedEntities.clear();
    }
    @Override public void clearArena() { this.map.clear(this.match.arenaWorld()); }
    @Override public boolean ownsEntity(Entity entity) { return entity != null && this.ownedEntities.containsKey(entity.getUuid()); }
    @Override public List<String> scoreboardLines() {
        return List.of("§6MyGO · 经济爆破", "§f队伍1 §c" + this.scores[0] + " §7: §9" + this.scores[1] + " §f队伍2",
                "§e第 " + Math.min(12, this.round) + " 回合 · " + phaseName(), "§f剩余 " + time(this.deadline - this.ticks),
                this.bombPlanted ? "§cC4 已安装" : "§7C4 尚未安装", "§7右键开火 · F 换弹", "§7潜行瞄准 · 9 商店");
    }

    public String debugState(ServerPlayerEntity player) {
        Fighter f = fighter(player); Gun gun = f == null ? null : f.gun(player.getInventory().selectedSlot);
        return "MyGO round=" + this.round + " phase=" + this.phase + " remainingTicks=" + Math.max(0, this.deadline - this.ticks)
                + " score=" + this.scores[0] + ":" + this.scores[1] + " team=" + (f == null ? -1 : f.team)
                + " side=" + (f != null && attackers(f) ? "T" : "CT") + " alive=" + (f != null && f.alive)
                + " hp=" + (f == null ? 0 : f.hp) + " cash=" + (f == null ? 0 : f.cash)
                + " gun=" + (gun == null ? "none" : gun.type) + " ammo=" + (gun == null ? 0 : gun.ammo)
                + " reserve=" + (gun == null ? 0 : gun.reserve) + " vest=" + (f != null && f.vest)
                + " helmet=" + (f != null && f.helmet) + " bombPlanted=" + this.bombPlanted
                + " bombCarrier=" + this.bombCarrier + " bomb=" + this.bombPosition + " sites=" + this.map.sites();
    }

    private void syncEquipment(ServerPlayerEntity p, Fighter f) {
        if (!f.alive || this.ended) return;
        for (int i = 0; i < p.getInventory().size(); i++) {
            if (i == 0 || i == 1 || i == 2 || i == 5 || i == 8 || i == 38 || i == 39) continue;
            if (!p.getInventory().getStack(i).isEmpty()) p.getInventory().setStack(i, ItemStack.EMPTY);
        }
        setSlot(p, 0, f.primary == null ? ItemStack.EMPTY : gunStack(f.primary));
        setSlot(p, 1, gunStack(f.pistol));
        ItemStack knife = model(Items.IRON_SWORD, 71006, "§f战术刀 · 左键"); setSlot(p, 2, knife);
        ItemStack objective = attackers(f) ? (f.id.equals(this.bombCarrier) ? named(Items.REDSTONE, "§6C4 · 包点潜行右键安装") : ItemStack.EMPTY)
                : named(f.kit ? Items.SHEARS : Items.STICK, "§b" + (f.kit ? "拆包器 · 4 秒" : "徒手拆包 · 8 秒") + " · 潜行右键");
        setSlot(p, 5, objective); setSlot(p, 8, named(Items.EMERALD, "§aMyGO 商店 · 购买阶段右键"));
        ItemStack teamArmor = named(Items.LEATHER_CHESTPLATE, attackers(f) ? "§c进攻方制服" : "§9防守方制服");
        teamArmor.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(attackers(f) ? 0xD93935 : 0x3478DB, false));
        if (!ItemStack.areEqual(p.getEquippedStack(EquipmentSlot.CHEST), teamArmor)) p.equipStack(EquipmentSlot.CHEST, teamArmor);
        updateHead(p, f); p.currentScreenHandler.sendContentUpdates();
    }
    private static void setSlot(ServerPlayerEntity p, int slot, ItemStack stack) {
        if (!ItemStack.areEqual(p.getInventory().getStack(slot), stack)) p.getInventory().setStack(slot, stack);
    }
    private static ItemStack gunStack(Gun gun) {
        return model(Items.IRON_PICKAXE, gun.type.model, "§f" + gun.type.label + " §7[" + gun.ammo + "/" + gun.reserve + "]");
    }
    private static ItemStack model(Item item, int model, String name) {
        ItemStack stack = named(item, name); stack.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(model)); return stack;
    }
    private static ItemStack named(Item item, String name) { ItemStack stack = new ItemStack(item); stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name)); return stack; }
    private void updateHead(ServerPlayerEntity p, Fighter f) {
        ItemStack expected = f.scoped ? model(Items.CARVED_PUMPKIN, 71010, "§7MyGO 瞄准镜") : ItemStack.EMPTY;
        if (!ItemStack.areEqual(p.getEquippedStack(EquipmentSlot.HEAD), expected)) p.equipStack(EquipmentSlot.HEAD, expected);
    }
    private void restoreHead(ServerPlayerEntity p, Fighter f) { f.scoped = false; p.equipStack(EquipmentSlot.HEAD, f.oldHead.copy()); }
    private void updateSpeed(ServerPlayerEntity p, Fighter f) {
        Gun gun = f.gun(p.getInventory().selectedSlot);
        double amount = this.phase == Phase.BUY || this.phase == Phase.POST ? -1 : gun == null ? 0 : gun.type == MyGoWeapon.AW ? -.25 : gun.type == MyGoWeapon.MP5 ? -.03 : -.10;
        if (f.scoped && gun != null) amount -= .10;
        if (Double.compare(amount, f.speed) == 0) return;
        var attr = p.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (attr != null) { attr.removeModifier(SPEED_ID); if (amount != 0) attr.addTemporaryModifier(new EntityAttributeModifier(SPEED_ID, amount, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)); }
        f.speed = amount;
    }
    private void removeSpeed(ServerPlayerEntity p, Fighter f) { var attr = p.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED); if (attr != null) attr.removeModifier(SPEED_ID); f.speed = Double.NaN; }
    private void sendHud(ServerPlayerEntity p, Fighter f) {
        if (this.channel != null && this.channel.player().equals(f.id)) return;
        Gun gun = f.gun(p.getInventory().selectedSlot);
        String weapon = gun == null ? "" : " · " + gun.type.label + " " + gun.ammo + "/" + gun.reserve + (gun.reloadAt > 0 ? " 换弹中" : "");
        p.sendMessage(Text.literal("§a" + String.format(Locale.ROOT, "%.1f", f.hp) + " HP §6$" + f.cash
                + " §7" + (f.vest ? "衣" : "无甲") + (f.helmet ? "+盔" : "") + weapon), true);
    }
    private void updateBar() {
        int total = this.phase == Phase.BUY ? MyGoRules.BUY_TICKS : this.phase == Phase.POST ? MyGoRules.POST_TICKS : this.bombPlanted ? MyGoRules.BOMB_TICKS : MyGoRules.ROUND_TICKS;
        this.timer.setName(Text.literal("MyGO " + this.scores[0] + ":" + this.scores[1] + " · " + phaseName() + " " + time(this.deadline - this.ticks)));
        this.timer.setColor(this.bombPlanted ? BossBar.Color.RED : this.phase == Phase.BUY ? BossBar.Color.BLUE : BossBar.Color.YELLOW);
        this.timer.setPercent(Math.max(0, Math.min(1, (float) (this.deadline - this.ticks) / total)));
    }
    private String phaseName() { return switch (this.phase) { case BUY -> "购买"; case LIVE -> this.bombPlanted ? "C4 倒计时" : "作战"; case POST -> "回合结束"; case FINISHED -> "比赛结束"; }; }
    private static String time(int ticks) { int sec = Math.max(0, (ticks + 19) / 20); return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60); }
    private boolean attackers(Fighter f) { return f.team == (MyGoRules.switchedSides(this.round) ? 1 : 0); }
    private Fighter fighter(ServerPlayerEntity p) { return this.fighters.get(p.getUuid()); }
    private List<ServerPlayerEntity> participants() { return this.match.onlineParticipants().stream().filter(p -> this.active.contains(p.getUuid())).toList(); }
    private void announce(String text) { this.match.broadcastToMatch(Text.literal(text)); }
    private void teleportHome(ServerPlayerEntity p) {
        Fighter f = fighter(p); int index = 0;
        for (Fighter each : this.fighters.values()) { if (each.id.equals(f.id)) break; if (each.team == f.team) index++; }
        teleport(p, this.map.spawn(attackers(f), index), attackers(f) ? -90 : 90); f.last = p.getPos();
    }
    private void teleport(ServerPlayerEntity p, BlockPos pos, float yaw) { p.teleport(this.match.arenaWorld(), pos.getX() + .5, pos.getY(), pos.getZ() + .5, yaw, 0); p.setVelocity(Vec3d.ZERO); p.fallDistance = 0; }
    private static boolean near(Entity p, BlockPos pos, double radius) { return pos != null && p.squaredDistanceTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5) <= radius * radius; }
    private void sound(ServerPlayerEntity p, String sound, float volume) { p.playSoundToPlayer(SoundEvent.of(Identifier.of("pvp", "mygo." + sound)), SoundCategory.PLAYERS, volume, 1); }
    private void soundAt(BlockPos pos, String sound, float volume) { if (pos != null) this.match.arenaWorld().playSound(null, pos, SoundEvent.of(Identifier.of("pvp", "mygo." + sound)), SoundCategory.PLAYERS, volume, 1); }
    private ArmorStandEntity label(BlockPos pos, String name) {
        ArmorStandEntity stand = new ArmorStandEntity(this.match.arenaWorld(), pos.getX() + .5, pos.getY() + .2, pos.getZ() + .5);
        NbtCompound nbt = new NbtCompound(); nbt.putBoolean("Marker", true); stand.readCustomDataFromNbt(nbt);
        stand.setInvisible(true); stand.setNoGravity(true); stand.setInvulnerable(true); stand.setCustomName(Text.literal(name)); stand.setCustomNameVisible(true);
        this.match.arenaWorld().spawnEntity(stand); this.ownedEntities.put(stand.getUuid(), stand); this.labels.add(stand); return stand;
    }
    private void discard(ArmorStandEntity stand) { if (stand != null) { stand.discard(); this.ownedEntities.remove(stand.getUuid()); this.labels.remove(stand); } }
    private void setBombMarker(BlockPos pos, String name) { removeBombMarker(); this.bombMarker = label(pos, name); }
    private void removeBombMarker() { discard(this.bombMarker); this.bombMarker = null; }
    private void clearRoundEntities() { removeBombMarker(); for (GroundGun drop : List.copyOf(this.groundGuns)) discard(drop.marker); this.groundGuns.clear(); }
}
