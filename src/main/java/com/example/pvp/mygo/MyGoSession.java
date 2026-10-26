package com.example.pvp.mygo;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.match.Match;
import com.example.pvp.match.MatchState;
import com.example.pvp.match.PortedGameSession;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.BlockPredicatesChecker;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.network.packet.s2c.play.PositionFlag;
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
import net.minecraft.predicate.BlockPredicate;
import net.minecraft.world.GameMode;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Comparator;
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
 * 按 CS:GO 统一军械、瞄具、经济与时限；保留工具承载及服务端虚拟伤害，避免原版物品属性介入。
 * 编辑前审查：成员、存活、回合、整场胜负分别管理；截止 tick 先于引导进度；所有枪包状态由服务端授权。
 * 装备模型、快捷栏和南瓜镜框只是状态投影，移动或伪造物品不会获得另一把枪或多出弹药。
 */
public final class MyGoSession implements PortedGameSession, WeaponSession {
    private enum Phase { BUY, LIVE, POST, FINISHED }
    private static final Identifier SPEED_ID = Identifier.of("pvp", "mygo_speed");
    // Keep the client-only target inside vanilla Adventure block reach.
    private static final double SYNTHETIC_HOLD_REACH = 3.0;
    private static final double REAL_BLOCK_REACH = 4.5;
    // Vanilla teleport IDs are non-negative. This camera-only packet must not
    // acknowledge or replace an outstanding world/round teleport on the server.
    private static final int RECOIL_CONFIRM_ID = -1;

    private static final class Gun {
        final MyGoWeapon type;
        // Reason: source weapon cones/recovery cannot be represented by one class-wide heat scalar.
        final MyGoGunAccuracy accuracy;
        final MyGoInputLease inputLease = new MyGoInputLease();
        final MyGoPreciseCadence cadence = new MyGoPreciseCadence();
        int ammo, reserve, ready, reloadAt, lastShot, sequence, lastInput = -1000;
        boolean autoArmed;
        boolean burstMode, silenced, alternateFire, reloadTransferred;
        int nextShellAt, magazineAt, deployedAt, modeReadyAt, burstShotsPending;
        double nextShotAt;
        double heat;
        final List<Double> shots = new ArrayList<>();
        Gun(MyGoWeapon type) { this.type = type; this.accuracy = new MyGoGunAccuracy(type); this.silenced = type.hasSilencer(); refill(); }
        void refill() { this.ammo = this.type.magazine; this.reserve = this.type.reserve; this.heat = 0; this.accuracy.reset(); cancel(); }
        void cancel() { this.shots.clear(); this.reloadAt = 0; this.nextShellAt = 0; this.magazineAt = 0; this.reloadTransferred = false; this.burstShotsPending = 0; this.lastInput = -1000; this.autoArmed = false; this.inputLease.clear(); this.cadence.reset(); this.nextShotAt = Double.POSITIVE_INFINITY; }
    }
    private static final class Fighter {
        final UUID id;
        final int team;
        int cash = MyGoRules.START_MONEY;
        double hp = 100, armor;
        boolean alive, vest, helmet, kit, scoped;
        boolean awZoom;
        int scopeLevel, resumeScopeLevel, resumeScopeAt, scopeReadyAt;
        final MyGoScope scopeView = new MyGoScope();
        Gun primary, pistol;
        int selected = -1, aimSince, stoppedAt, knifeReady, throwReady, feedbackUntil, channelInputAt;
        int taggedAt = -1000;
        double tagSlowdown;
        UUID spectating;
        final MyGoFootsteps footsteps = new MyGoFootsteps();
        int stepSounds;
        String lastStepCue = "NONE", lastStepSurface = "none";
        int scopeUseTick = -20;
        int kills, deaths, roundKills, objectiveScore;
        double roundDamage;
        final Map<MyGoUtilities.Type, Integer> grenades = new EnumMap<>(MyGoUtilities.Type.class);
        final MyGoRoundEquipment roundEquipment = new MyGoRoundEquipment();
        double speed = Double.NaN;
        Vec3d last = Vec3d.ZERO;
        Vec3d lastSafe;
        ItemStack oldHead = ItemStack.EMPTY;
        Fighter(UUID id, int team) { this.id = id; this.team = team; }
        Gun gun(int slot) { return slot == 0 ? this.primary : slot == 1 ? this.pistol : null; }
        void cancel() { if (this.primary != null) this.primary.cancel(); if (this.pistol != null) this.pistol.cancel(); this.resumeScopeLevel = 0; this.resumeScopeAt = 0; }
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
    private record GroundEquipment(MyGoUtilities.Type grenade, boolean kit, BlockPos pos, int pickupAt,
                                   ArmorStandEntity marker) { }
    private record SyntheticHold(BlockPos pos, BlockState actual) { }
    private record Channel(UUID player, BlockPos target, Vec3d origin, int completeAt, boolean planting) { }

    private final Match match;
    private final MyGoArena map;
    private final Map<UUID, Fighter> fighters = new LinkedHashMap<>();
    private final Set<UUID> active = new LinkedHashSet<>();
    private final List<BlockPos> initialSpawns = new ArrayList<>();
    private final List<GroundGun> groundGuns = new ArrayList<>();
    private final List<GroundEquipment> groundEquipment = new ArrayList<>();
    private final Map<UUID, SyntheticHold> syntheticHolds = new HashMap<>();
    private final List<ArmorStandEntity> labels = new ArrayList<>();
    private final Map<UUID, Entity> ownedEntities = new HashMap<>();
    private final Random random;
    private final MyGoUtilities utilities;
    private final MyGoLagHistory lagHistory = new MyGoLagHistory();
    private final int[] scores = new int[2], losses = {MyGoRules.START_LOSS_LEVEL, MyGoRules.START_LOSS_LEVEL};
    private final ServerBossBar timer = new ServerBossBar(Text.literal("MyGO · 购买阶段"), BossBar.Color.BLUE, BossBar.Style.PROGRESS);
    private Phase phase = Phase.BUY;
    private int ticks, deadline, buyUntil, bombPickupAt, round = 1;
    private boolean ended, finalResult, bombPlanted;
    private Integer winningTeam;
    private UUID bombCarrier;
    private BlockPos bombGround, bombPosition;
    private ArmorStandEntity bombMarker;
    private Channel channel;

    public MyGoSession(Match match, ArenaTemplate template, int regionIndex, int seed) {
        this(match, template, regionIndex, seed, null);
    }

    public MyGoSession(Match match, ArenaTemplate template, int regionIndex, int seed, String forcedMapKey) {
        this.match = match;
        String mapKey = forcedMapKey == null || forcedMapKey.isBlank()
                ? MyGoMapBlueprint.randomKey(seed) : forcedMapKey;
        this.map = new MyGoArena(template, regionIndex, mapKey);
        this.random = new Random(seed * 341873128712L + 1927);
        this.utilities = new MyGoUtilities(this.match.arenaWorld(),
                () -> participants().stream().filter(p -> fighter(p).alive).toList(), (victim, owner, amount) -> {
            Fighter target = fighter(victim), source = fighter(owner);
            if (target == null || source == null || target.team == source.team && victim != owner) return;
            // Review: finite armour absorbs only what remains; one armour point must not cap HE at 57 HP.
            MyGoRules.Damage hit = MyGoRules.heDamage(amount, target.armor);
            consumeArmor(target, hit.armor());
            damage(victim, this.active.contains(source.id) ? owner : null, hit.health(), "破片手雷");
        }, (victim, owner, amount, kind) -> {
            Fighter target = fighter(victim), source = owner == null ? null : fighter(owner);
            if (target == null || !target.alive || source == null) return;
            if (kind == MyGoUtilities.DamageKind.DECOY) return;
            boolean friendly = source.team == target.team && victim != owner;
            if (kind == MyGoUtilities.DamageKind.IMPACT && friendly) return;
            double applied = friendly && kind == MyGoUtilities.DamageKind.FIRE ? amount * .15 : amount;
            damage(victim, this.active.contains(source.id) ? owner : null, applied,
                    kind == MyGoUtilities.DamageKind.FIRE ? "燃烧伤害" : "手雷撞击");
        });
        int attackers = (match.players().size() + 1) / 2;
        int attackerSpawnIndex = 0;
        int defenderSpawnIndex = 0;
        for (int i = 0; i < match.players().size(); i++) {
            ServerPlayerEntity player = match.players().get(i);
            int team = i < attackers ? 0 : 1;
            this.fighters.put(player.getUuid(), new Fighter(player.getUuid(), team));
            this.active.add(player.getUuid());
            int spawnIndex = team == 0 ? attackerSpawnIndex++ : defenderSpawnIndex++;
            this.initialSpawns.add(this.map.spawn(team == 0, spawnIndex));
        }
    }

    @Override public List<BlockPos> spawnPositions() { return List.copyOf(this.initialSpawns); }
    public String mapName() { return this.map.name(); }
    public String mapId() { return this.map.id(); }
    @Override public void stageMap() { this.map.generate(this.match.arenaWorld()); }

    @Override public void start() {
        for (ServerPlayerEntity player : participants()) {
            Fighter fighter = fighter(player);
            fighter.oldHead = player.getEquippedStack(EquipmentSlot.HEAD).copy();
            this.timer.addPlayer(player);
            player.sendMessage(Text.literal("§e本场队伍：" + (fighter.team == 0 ? "§cT 进攻方" : "§9CT 防守方")), false);
            player.sendMessage(Text.literal("§6MyGO 经典爆破 · 先赢 16 回合，第 15 回合换边，15:15 平局。"), false);
            player.sendMessage(Text.literal("§e左键点射 · 瞄具枪右键切换倍率/关镜 · F 换弹 · Q 丢枪/C4 · 购买器右键商店。"), false);
            player.sendMessage(Text.literal("§7CS:GO 军械库 34 种枪械 · T 默认 Glock-18 / CT 默认 USP-S · 仅瞄具枪支持开镜。"), false);
            player.sendMessage(Text.literal("§e投掷物：左键大力高抛，右键小力低抛，投出后自动切回武器。潜行/低速移动静音。"), false);
            player.sendMessage(Text.literal("§7携 C4 到包点，选择第 6 格，潜行右键安装；防守方同样操作拆包。阵亡后在封闭室等待下一回合。"), false);
        }
        String invalidSpawn = this.map.validateSpawns(this.match.arenaWorld());
        if (invalidSpawn != null) {
            this.match.cancelMatch(this.map.name() + " 出生点校验失败：" + invalidSpawn);
            return;
        }
        announce("§6地图：" + this.map.name() + " · A / B 双包点");
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
        this.lagHistory.capture(this.ticks, participants());
        if (this.phase == Phase.POST && this.ticks >= this.deadline) { beginRound(false); return; }
        if (this.phase == Phase.BUY && this.ticks >= this.deadline) {
            this.phase = Phase.LIVE;
            this.deadline = this.ticks + MyGoRules.ROUND_TICKS;
            announce("§c开始行动！进攻方安装 C4，防守方阻止或拆除。");
            // 解冻不关闭军械库：留在购买区的玩家还有独立 20 秒补购窗口。
        }
        // 截止时间优先于引导完成，防止压线拆包和超时安装改变已经产生的胜负。
        if (this.phase == Phase.LIVE) {
            MyGoRules.Outcome timed = MyGoRules.timedOutcome(this.bombPlanted, this.ticks, this.deadline);
            if (timed != null) {
                if (this.bombPlanted) {
                    soundAt(this.bombPosition, "explode", 2);
                    Vec3d blast = Vec3d.ofCenter(this.bombPosition);
                    this.match.arenaWorld().spawnParticles(ParticleTypes.EXPLOSION_EMITTER, blast.x, blast.y, blast.z, 1, 0, 0, 0, 0);
                    // Reason: a 12-block instant-kill cutoff discarded distance, remaining HP and armour.
                    // Classic C4 damage passes through cover; never use a destructive Minecraft explosion.
                    for (ServerPlayerEntity p : participants()) if (fighter(p).alive) {
                        Fighter target = fighter(p);
                        MyGoRules.Damage hit = MyGoBombDamage.damage(p.getBoundingBox().getCenter().distanceTo(blast), target.armor);
                        consumeArmor(target, hit.armor());
                        damage(p, null, hit.health(), "C4 爆炸");
                    }
                }
                resolve(timed, this.bombPlanted ? MyGoRules.WinCause.BOMB_EXPLODED : MyGoRules.WinCause.TIMEOUT);
                return;
            }
            if (this.bombPlanted && this.ticks % (this.deadline - this.ticks <= 200 ? 10 : 20) == 0)
                soundAt(this.bombPosition, "plant", .35F);
        }
        if (this.phase == Phase.LIVE && this.ticks == this.buyUntil)
            for (ServerPlayerEntity p : participants()) if (p.currentScreenHandler instanceof ShopHandler) p.closeHandledScreen();
        for (ServerPlayerEntity player : participants()) {
            Fighter fighter = fighter(player);
            player.setFireTicks(0);
            player.fallDistance = 0;
            if (this.ticks % 20 == 0) player.getHungerManager().setFoodLevel(20);
            if (!fighter.alive) {
                clearSyntheticHold(player);
                fighter.footsteps.reset();
                tickSpectator(player, fighter);
                if (this.ticks % 40 == 0) actionBar(player, "§7阵亡，等待下一回合 · 队友：" +
                        this.fighters.values().stream().filter(f -> f.team == fighter.team && this.active.contains(f.id) && f.alive)
                                .map(f -> { ServerPlayerEntity teammate = this.match.onlinePlayer(f.id); return (teammate == null ? "队友" : teammate.getName().getString())
                                        + " " + String.format(Locale.ROOT, "%.0f", f.hp) + "HP"; }).reduce((a, b) -> a + " / " + b).orElse("全部阵亡"));
                continue;
            }
            // 物理血条仅映射虚拟 HP；持续覆盖可阻止饱食回血改变枪械模式的 100 HP 规则。
            float displayHealth = (float) Math.max(.01, player.getMaxHealth() * fighter.hp / 100);
            if (Math.abs(player.getHealth() - displayHealth) > .001) player.setHealth(displayHealth);
            if (player.getWorld() != this.match.arenaWorld() || !this.map.inBounds(player.getBlockPos())) {
                // 能到达的地图地形均合法；掉出完整地图或误入其他世界只救援，不制造环境阵亡。
                recoverPosition(player, fighter);
                continue;
            }
            if (this.phase == Phase.BUY && !this.map.inBuyZone(player.getBlockPos(), attackers(fighter))) teleportHome(player);
            onSelectedSlotChanged(player);
            int selected = player.getInventory().selectedSlot;
            boolean moving = player.getPos().squaredDistanceTo(fighter.last) > .0004;
            Vec3d movement = player.getPos().subtract(fighter.last);
            if (moving || player.isSprinting()) fighter.stoppedAt = this.ticks;
            fighter.last = player.getPos();
            if (combatPhase()) tickMovementSounds(player, fighter);
            else fighter.footsteps.reset();
            if (player.isOnGround() && this.match.arenaWorld().isSpaceEmpty(player, player.getBoundingBox())
                    && this.match.arenaWorld().getBlockCollisions(player, player.getBoundingBox().offset(0, -.05, 0)).iterator().hasNext())
                fighter.lastSafe = player.getPos();
            Gun selectedGun = fighter.gun(selected);
            SyntheticHold synthetic = this.syntheticHolds.get(player.getUuid());
            if (synthetic != null && (selectedGun == null || !selectedGun.type.automatic()))
                clearSyntheticHold(player);
            if (combatPhase() && selectedGun != null && selectedGun.type.automatic())
                ensureSyntheticHold(player, fighter);
            if (selectedGun != null) {
                double maxSpeed = selectedGun.type.profile().maxSpeed(alternateMode(fighter, selectedGun))
                        / (100 / 2.54) / 20;
                selectedGun.accuracy.update(this.ticks, alternateMode(fighter, selectedGun), player.isSneaking(),
                        player.isOnGround(), player.isClimbing(), movement.horizontalLength() / Math.max(.001, maxSpeed),
                        movement.lengthSquared() > 6.25 ? 0 : movement.y);
            }
            applyCameraRecovery(player, fighter);
            if (fighter.scoped && (!combatPhase() || selectedGun == null
                    || selectedGun.type.scopeLevels() == 0 || fighter.scopeLevel > selectedGun.type.scopeLevels())) {
                fighter.scoped = false; fighter.scopeLevel = 0; updateHead(player, fighter);
            }
            updateSpeed(player, fighter);
            if (combatPhase() || this.phase == Phase.BUY) tickGun(player, fighter, fighter.gun(selected));
            if (this.ticks % 10 == 0) sendHud(player, fighter);
            // 实际枪械身份存于 Fighter，整理显示背包不会刷新同一物品、不会重启每 tick 挥手动画。
            if (this.ticks % 20 == 0) syncEquipment(player, fighter);
        }
        if (combatPhase()) {
            this.utilities.tick(this.ticks);
            if (this.phase == Phase.LIVE) { checkElimination(); if (this.phase == Phase.LIVE) tickChannel(); }
        }
        // 冻结期也必须能给队友发枪、交换副枪和交接 C4。
        if (combatPhase() || this.phase == Phase.BUY) tickPickups();
        if (this.ticks % 10 == 0) updateBar();
    }

    private void beginRound(boolean first) {
        // 回合末仍可活动，换边必须等新回合开始，不能在第15局结算时提前交换阵营。
        if (!first) this.round++;
        clearRoundEntities();
        this.lagHistory.clear();
        this.bombPlanted = false; this.bombPosition = null; this.bombGround = null; this.bombCarrier = null; this.channel = null;
        this.phase = Phase.BUY; this.deadline = this.ticks + MyGoRules.FREEZE_TICKS;
        this.buyUntil = this.deadline + MyGoRules.BUY_TICKS;
        boolean halftime = this.round == MyGoRules.HALF_ROUNDS + 1;
        for (ServerPlayerEntity player : participants()) {
            Fighter f = fighter(player);
            if (first || halftime) { f.cash = MyGoRules.START_MONEY; f.primary = null; f.pistol = null; f.armor = 0; f.vest = false; f.helmet = false; f.kit = false; f.grenades.clear(); }
            if (!f.alive && !first) { f.primary = null; f.pistol = null; f.armor = 0; f.vest = false; f.helmet = false; f.kit = false; f.grenades.clear(); }
            if (f.pistol == null) f.pistol = new Gun(MyGoWeapon.defaultPistol(attackers(f)));
            f.alive = true; f.hp = 100; f.roundKills = 0; f.roundDamage = 0; f.objectiveScore = 0; f.pistol.refill(); if (f.primary != null) f.primary.refill();
            f.roundEquipment.beginRound(f.grenades); f.taggedAt = -1000; f.tagSlowdown = 0; f.spectating = null;
            f.scoped = false; f.scopeLevel = 0; f.resumeScopeLevel = 0; f.resumeScopeAt = 0; f.scopeView.reset(player); f.selected = -1; f.aimSince = this.ticks; f.stoppedAt = this.ticks;
            f.throwReady = 0; f.stepSounds = 0; f.lastStepCue = "NONE"; f.lastStepSurface = "none";
            player.closeHandledScreen(); player.setCameraEntity(player); player.changeGameMode(GameMode.ADVENTURE); player.clearStatusEffects();
            player.setHealth(player.getMaxHealth()); player.setVelocity(Vec3d.ZERO);
            player.getInventory().clear(); player.getInventory().selectedSlot = f.primary == null ? 1 : 0;
            // 回合复位会切到主枪；必须同步客户端，否则画面仍是副枪而服务端实际开主枪。
            player.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(player.getInventory().selectedSlot));
            player.equipStack(EquipmentSlot.CHEST, ItemStack.EMPTY); player.equipStack(EquipmentSlot.LEGS, ItemStack.EMPTY);
            player.equipStack(EquipmentSlot.FEET, ItemStack.EMPTY);
            teleportHome(player); updateHead(player, f);
        }
        if (halftime) { this.losses[0] = MyGoRules.START_LOSS_LEVEL; this.losses[1] = MyGoRules.START_LOSS_LEVEL; announce("§e半场换边，现金与装备重置。"); }
        List<Fighter> attack = this.fighters.values().stream().filter(f -> this.active.contains(f.id) && attackers(f)).toList();
        if (!attack.isEmpty()) this.bombCarrier = attack.get(this.random.nextInt(attack.size())).id;
        for (ServerPlayerEntity player : participants()) syncEquipment(player, fighter(player));
        announce("§6第 " + this.round + " 回合 · " + this.scores[0] + ":" + this.scores[1] + " · 冻结 15 秒，解冻后可在购买区补购 20 秒");
        for (ServerPlayerEntity player : participants()) player.sendMessage(Text.literal(attackers(fighter(player))
                ? "§c本回合：进攻方" + (player.getUuid().equals(this.bombCarrier) ? " · 你携带 C4" : "") : "§9本回合：防守方"), false);
        updateBar(); checkTeams();
    }

    private void tickGun(ServerPlayerEntity player, Fighter f, Gun gun) {
        if (gun == null) return;
        if (gun.autoArmed && gun.inputLease.active(this.ticks)
                && gun.ready <= this.ticks && gun.reloadAt == 0) {
            if (gun.ammo > 0) trigger(player, f, gun);
            else { gun.autoArmed = false; reload(player); }
        }
        else if (gun.autoArmed && !gun.inputLease.active(this.ticks))
            gun.autoArmed = false;
        if (f.resumeScopeLevel > 0 && this.ticks >= f.resumeScopeAt) {
            int level = f.resumeScopeLevel; f.resumeScopeLevel = 0;
            if (combatPhase() && gun.type.unzoomAfterShot() && gun.ammo > 0 && gun.reloadAt == 0) {
                f.scopeLevel = level; f.scoped = true; f.aimSince = this.ticks; updateHead(player, f); updateSpeed(player, f);
            }
        }
        if (gun.reloadAt > 0 && this.ticks >= gun.reloadAt) {
            int amount = Math.min(gun.type.magazine - gun.ammo, gun.reserve);
            gun.ammo += amount; gun.reserve -= amount; gun.reloadAt = 0;
            syncEquipment(player, f);
        }
        if (gun.lastShot > 0 && this.ticks == gun.lastShot + 2) syncEquipment(player, f);
        while (!gun.shots.isEmpty() && gun.shots.getFirst() <= this.ticks) {
            gun.shots.removeFirst();
            if (gun.ammo > 0 && f.alive && combatPhase()) fire(player, f, gun);
        }
    }

    private void trigger(ServerPlayerEntity player, Fighter f, Gun gun) {
        if (!combatPhase() || !f.alive || gun.reloadAt > 0 || this.channel != null && this.channel.player().equals(f.id)) return;
        if (this.ticks < gun.ready || Double.isFinite(gun.cadence.nextAt()) && !gun.cadence.ready(this.ticks)) return;
        if (gun.ammo <= 0) {
            gun.ready = this.ticks + 8;
            if (gun.reserve > 0) reload(player);
            else { sound(player, "empty", .7F); actionBar(player, "§e没有备用弹药。"); }
            return;
        }
        if (!Double.isFinite(gun.cadence.nextAt())) gun.cadence.armNow(this.ticks);
        gun.ready = this.ticks;
        double[] offsets = gun.type.burstTickOffsets(gun.burstMode);
        gun.burstShotsPending = gun.burstMode ? offsets.length : 0;
        for (double offset : offsets) gun.shots.add(this.ticks + offset);
        // Reason: automatic hold ticks call trigger from tickGun. Re-entering
        // tickGun here would recurse forever once the synthetic hold is alive;
        // the caller's existing shot queue drain handles the due shot.
        if (!gun.autoArmed) tickGun(player, f, gun);
    }

    private static int autoInputGrace(MyGoWeapon type) {
        // A slow weapon's cycle is not evidence that the player is still holding M1.
        return MyGoInputLease.DEFAULT_TICKS;
    }

    /** Shooting cadence/movement spread and camera kick follow the server-side CS4M approach. */
    private void fire(ServerPlayerEntity player, Fighter f, Gun gun) {
        gun.ammo--;
        gun.accuracy.fired(this.ticks, alternateMode(f, gun));
        if (!gun.burstMode || --gun.burstShotsPending <= 0) {
            gun.burstShotsPending = 0;
            gun.cadence.fired(this.ticks, gun.type.cycleTickInterval(gun.burstMode, alternateMode(f, gun)));
            gun.ready = (int) Math.ceil(gun.cadence.nextAt() - 1e-9);
        }
        gun.lastShot = this.ticks; gun.sequence++;
        Map<ServerPlayerEntity, MyGoRules.Damage> hits = new LinkedHashMap<>();
        Set<UUID> headshots = new LinkedHashSet<>();
        for (int pellet = 0; pellet < gun.type.pellets(); pellet++) {
            Vec3d from = player.getEyePos();
            MyGoGunAccuracy.Offset offset = gun.accuracy.sample(this.random);
            Vec3d direction = Vec3d.fromPolar(player.getPitch() + (float) offset.pitchDegrees(),
                    player.getYaw() + (float) offset.yawDegrees());
            List<ServerPlayerEntity> enemies = participants().stream()
                    .filter(candidate -> fighter(candidate).alive && fighter(candidate).team != f.team
                            && candidate.getWorld() == this.match.arenaWorld()).toList();
            MyGoBulletTrace.Result trace = MyGoBulletTrace.trace(player, gun.type, from, direction, enemies,
                    this.lagHistory, this.ticks);
            Vec3d finish = trace.end();
            if (pellet == 0) {
                Vec3d delta = finish.subtract(from);
                int particles = Math.min(8, (int) delta.length() * 2);
                for (int i = 1; i <= particles; i++) {
                    Vec3d point = from.add(delta.multiply((double) i / particles));
                    this.match.arenaWorld().spawnParticles(ParticleTypes.CRIT, point.x, point.y, point.z, 1, 0, 0, 0, 0);
                }
            }
            for (MyGoBulletTrace.Hit traced : trace.hits()) {
                ServerPlayerEntity victim = traced.player();
                Fighter target = fighter(victim);
                MyGoRules.Damage previous = hits.getOrDefault(victim, new MyGoRules.Damage(0, 0));
                // Apply penetration loss before finite armour absorbs the pellet.
                MyGoRules.Damage hit = MyGoRules.damageWithArmor(gun.type, traced.distance(), traced.part(),
                        Math.max(0, target.armor - previous.armor()), target.helmet, traced.damageScale());
                hits.put(victim, new MyGoRules.Damage(previous.health() + hit.health(), previous.armor() + hit.armor()));
                if (traced.part() == MyGoRules.Part.HEAD) headshots.add(victim.getUuid());
            }
        }
        soundAt(player.getBlockPos(), gun.type.soundKey(), 1);
        for (var hit : hits.entrySet()) {
            boolean headshot = headshots.contains(hit.getKey().getUuid());
            MyGoRules.Damage resolved = MyGoRules.roundBulletDamage(hit.getValue());
            consumeArmor(fighter(hit.getKey()), resolved.armor());
            damage(hit.getKey(), player, resolved.health(), gun.type.label + (headshot ? " 爆头" : ""), gun.type.killReward);
            sound(player, headshot ? "headshot" : "hit", .55F);
            f.feedbackUntil = this.ticks + 8;
            actionBar(player, (headshot ? "§6✦ 爆头 " : "§a✦ 命中 ")
                    + String.format(Locale.ROOT, "%.0f", resolved.health()));
        }
        // 开枪只旋转视角，不能使用会改写玩家坐标及待确认传送位置的 requestTeleport。
        // 旧版把后坐力当传送执行，曾将玩家移出竞技场并触发“离开战场”阵亡。
        if (!this.ended && f.alive && combatPhase()) {
            MyGoGunAccuracy.Kick recoil = gun.accuracy.recoil(gun.type, gun.sequence - 1, alternateMode(f, gun));
            float yaw = (float) recoil.yawDegrees();
            float kick = (float) recoil.pitchDegrees();
            float pitch = net.minecraft.util.math.MathHelper.clamp(player.getPitch() - kick, -89.9F, 89.9F);
            float pitchDelta = pitch - player.getPitch();
            player.setYaw(player.getYaw() + yaw);
            player.setPitch(pitch);
            gun.accuracy.recordCameraKick(this.ticks, yaw, -pitchDelta);
            // XYZ are zero client deltas; server coordinates/velocity are untouched.
            // Vanilla ignores the negative confirmation ID and processes the normal movement reply.
            player.networkHandler.sendPacket(new PlayerPositionLookS2CPacket(0, 0, 0, yaw, pitchDelta,
                    EnumSet.allOf(PositionFlag.class), RECOIL_CONFIRM_ID));
            // 栓狙开火后先退镜拉栓，周期结束自动回到原镜位；切枪/换弹/手动镜位会取消恢复。
            if (gun.type.unzoomAfterShot() && f.scopeLevel > 0) {
                f.resumeScopeLevel = f.scopeLevel; f.resumeScopeAt = this.ticks + gun.type.cycleTicks;
                f.scopeLevel = 0; f.scoped = false; updateHead(player, f); updateSpeed(player, f);
            }
            syncEquipment(player, f);
        }
    }

    private void applyCameraRecovery(ServerPlayerEntity player, Fighter fighter) {
        Gun gun = fighter.gun(player.getInventory().selectedSlot);
        if (gun == null || !fighter.alive) return;
        MyGoGunAccuracy.Kick recovery = gun.accuracy.recoverCamera(this.ticks);
        if (Math.abs(recovery.yawDegrees()) < 1e-5 && Math.abs(recovery.pitchDegrees()) < 1e-5) return;
        float yaw = (float) recovery.yawDegrees();
        float pitchDelta = (float) -recovery.pitchDegrees();
        player.setYaw(player.getYaw() + yaw);
        player.setPitch(net.minecraft.util.math.MathHelper.clamp(player.getPitch() + pitchDelta, -89.9F, 89.9F));
        player.networkHandler.sendPacket(new PlayerPositionLookS2CPacket(0, 0, 0, yaw, pitchDelta,
                EnumSet.allOf(PositionFlag.class), RECOIL_CONFIRM_ID));
    }

    private void damage(ServerPlayerEntity victim, ServerPlayerEntity attacker, double amount, String reason) {
        damage(victim, attacker, amount, reason, 300);
    }
    private void consumeArmor(Fighter f, double used) {
        f.armor = Math.max(0, Math.floor(f.armor - used)); f.vest = f.armor > 0;
        if (!f.vest) f.helmet = false;
    }
    private void damage(ServerPlayerEntity victim, ServerPlayerEntity attacker, double amount, String reason, int killReward) {
        // CS:GO 的生命伤害为整数；HE/刀也经过此处，防止它们留下影响下一枪击杀的小数 HP。
        amount = Math.floor(amount);
        Fighter f = fighter(victim);
        if (f == null || !f.alive || !combatPhase() || amount <= 0) return;
        Fighter source = attacker == null ? null : fighter(attacker);
        if (source != null && source.team != f.team) source.roundDamage += Math.min(f.hp, amount);
        // Original tagging is cumulative and depends on the victim's held weapon, not the attacker's gun.
        Gun held = f.gun(victim.getInventory().selectedSlot);
        double mobility = held == null ? .65 : held.type.profile().valueOr(
                amount >= 40 ? "flinch velocity modifier large" : "flinch velocity modifier small", .5);
        double residual = Math.max(0, 1 - (this.ticks - f.taggedAt) / 14.0) * f.tagSlowdown;
        f.tagSlowdown = Math.clamp(Math.max(1 - mobility, residual + .06), 0, .85);
        f.taggedAt = this.ticks;
        updateSpeed(victim, f);
        f.hp = Math.max(0, f.hp - amount);
        if (f.hp <= .0000001) kill(victim, attacker, reason, killReward);
        else actionBar(victim, "§c受到 " + String.format(Locale.ROOT, "%.1f", amount) + " 伤害 · "
                + String.format(Locale.ROOT, "%.1f", f.hp) + " HP");
    }

    private void kill(ServerPlayerEntity victim, ServerPlayerEntity attacker, String reason) {
        kill(victim, attacker, reason, 300);
    }
    private void kill(ServerPlayerEntity victim, ServerPlayerEntity attacker, String reason, int killReward) {
        Fighter f = fighter(victim);
        if (f == null || !f.alive) return;
        clearSyntheticHold(victim);
        MyGoUtilities.Type droppedGrenade = MyGoRoundEquipment.deathGrenade(f.grenades, victim.getInventory().selectedSlot);
        if (droppedGrenade != null) dropEquipment(droppedGrenade, false, victim.getBlockPos());
        if (f.kit) dropEquipment(null, true, victim.getBlockPos());
        f.alive = false; f.hp = 0; f.deaths++; f.cancel(); f.grenades.clear();
        if (this.channel != null && this.channel.player().equals(f.id)) cancelChannel();
        if (f.id.equals(this.bombCarrier)) dropBomb(victim.getBlockPos());
        if (f.primary != null) { dropGun(f.primary, victim.getBlockPos()); f.primary = null; }
        else if (f.pistol != null) dropGun(f.pistol, victim.getBlockPos());
        f.pistol = null;
        f.armor = 0; f.vest = false; f.helmet = false; f.kit = false; f.scoped = false; f.scopeLevel = 0;
        victim.closeHandledScreen(); victim.getInventory().clear(); updateHead(victim, f); removeSpeed(victim, f);
        victim.changeGameMode(GameMode.SPECTATOR); victim.setHealth(victim.getMaxHealth());
        teleport(victim, this.map.spectator(), 0);
        if (attacker != null && fighter(attacker) != null && fighter(attacker).team != f.team) {
            Fighter killer = fighter(attacker); killer.cash = MyGoRules.addMoney(killer.cash, killReward); killer.kills++; killer.roundKills++;
        } else if (attacker != null && attacker != victim && fighter(attacker) != null) {
            Fighter killer = fighter(attacker); killer.cash = MyGoRules.addMoney(killer.cash, -300); killer.kills--;
            actionBar(attacker, "§c击杀队友，罚款 $300。");
        }
        announce("§7" + victim.getName().getString() + " 阵亡" + (attacker == null ? "（" + reason + "）"
                : " ← " + attacker.getName().getString() + "（" + reason + "）"));
        // 延迟到会话 tick 末检查队伍，防止一次攻击在死亡装备清理途中触发整场收尾。
    }

    public void reload(ServerPlayerEntity player) {
        Fighter f = fighter(player); if (f == null || !f.alive || this.ended) return;
        Gun gun = f.gun(player.getInventory().selectedSlot);
        if (gun == null || gun.reloadAt > 0 || gun.ammo >= gun.type.magazine || gun.reserve <= 0) return;
        f.resumeScopeLevel = 0; f.resumeScopeAt = 0;
        gun.shots.clear(); gun.inputLease.clear(); gun.autoArmed = false; gun.cadence.reset();
        gun.reloadAt = this.ticks + gun.type.reloadTicks; closeScope(player, f);
        syncEquipment(player, f);
        sound(player, "reload", .8F); actionBar(player, "§e正在换弹，切枪会取消。");
    }

    /** 逐个处理选槽包，防止同 tick 来回切枪绕过待发子弹、换弹与安装/拆除取消规则。 */
    public void onSelectedSlotChanged(ServerPlayerEntity player) {
        Fighter f = fighter(player);
        if (f == null || this.ended || player.getInventory().selectedSlot == f.selected) return;
        clearSyntheticHold(player);
        f.cancel();
        f.selected = player.getInventory().selectedSlot;
        Gun equipped = f.gun(f.selected);
        // Animation event timings are not in items_game; enforce a declared Minecraft deploy window.
        if (equipped != null) {
            equipped.deployedAt = this.ticks + (equipped.type == MyGoWeapon.CZ75 ? 36 : equipped.type == MyGoWeapon.AWP ? 25 : 10);
            equipped.ready = Math.max(equipped.ready, equipped.deployedAt);
        }
        f.aimSince = this.ticks;
        f.scoped = false;
        f.scopeLevel = 0;
        f.scopeUseTick = this.ticks - 6;
        updateHead(player, f);
        syncEquipment(player, f);
        if (f.alive && combatPhase()) soundAt(player.getBlockPos(), "equip", .45F);
        if (this.channel != null && this.channel.player().equals(f.id)) cancelChannel();
    }

    /** 左键的唯一武器入口：投掷物高抛与枪械开火都只在挥手包处理一次。 */
    public void onAttackInput(ServerPlayerEntity player, Hand hand) {
        Fighter f = fighter(player);
        if (f == null || !f.alive || this.ended || hand != Hand.MAIN_HAND
                || this.match.getState() != MatchState.ACTIVE) return;
        Gun gun = f.gun(player.getInventory().selectedSlot);
        if (gun != null) {
            gun.inputLease.refresh(this.ticks);
            boolean repeated = gun.lastInput >= 0 && this.ticks - gun.lastInput <= autoInputGrace(gun.type);
            gun.lastInput = this.ticks;
            // 只有自动武器收到连续挥手包后才进入服务端连射；单击不会被猜成按住。
            if (repeated && gun.type.automatic())
                gun.autoArmed = true;
            trigger(player, f, gun);
            if (gun.type.automatic()) {
                // The target is normally pre-armed each tick, but a newly
                // selected weapon may need one immediate client-only update.
                ensureSyntheticHold(player, f);
                gun.autoArmed = true;
            }
        } else {
            MyGoUtilities.Type type = MyGoUtilities.Type.heldAt(f.grenades, player.getInventory().selectedSlot);
            if (type != null) throwGrenade(player, f, type, MyGoUtilities.ThrowMode.STRONG);
        }
    }

    /** Clear the bounded click lease when vanilla reports that block breaking stopped. */
    public void onAttackRelease(ServerPlayerEntity player) {
        Fighter f = fighter(player);
        if (f == null) return;
        Gun gun = f.gun(player.getInventory().selectedSlot);
        if (gun != null) { gun.inputLease.clear(); gun.autoArmed = false; }
        clearSyntheticHold(player);
    }

    /**
     * Vanilla 1.21.1 repeats left-click input only while its crosshair is on a
     * block. A client-only barrier gives an automatic weapon that same native
     * path when the player is aiming air/an entity; the server never changes
     * its world and treats the corresponding dig packets as input only.
     */
    private void ensureSyntheticHold(ServerPlayerEntity player, Fighter fighter) {
        if (!combatPhase() || !fighter.alive) return;
        Vec3d from = player.getEyePos();
        Vec3d direction = player.getRotationVec(1).normalize();
        Vec3d end = from.add(direction.multiply(8));
        BlockHitResult block = this.match.arenaWorld().raycast(new RaycastContext(from, end,
                // Match the client's selectable outline, including slabs,
                // stairs, carpets and other partial blocks.
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
        double blockDistance = block.getType() == net.minecraft.util.hit.HitResult.Type.MISS
                ? Double.POSITIVE_INFINITY : from.distanceTo(block.getPos());
        double entityDistance = Double.POSITIVE_INFINITY;
        for (ServerPlayerEntity candidate : participants()) {
            if (candidate == player || candidate.getWorld() != this.match.arenaWorld()) continue;
            Optional<Vec3d> hit = candidate.getBoundingBox().raycast(from, end);
            if (hit.isPresent()) entityDistance = Math.min(entityDistance, from.distanceTo(hit.get()));
        }
        BlockPos pos;
        boolean fakeBarrier;
        if (entityDistance < blockDistance) {
            // Keep a fake block in front of a player target so vanilla does not
            // fall back to its one-shot entity attack path while moving.
            pos = reachableAir(from, direction, Math.max(.8, entityDistance - .35));
            fakeBarrier = true;
        } else if (blockDistance <= REAL_BLOCK_REACH) {
            // A reachable real block is the preferred native hold target; do
            // not overlay it with a client-only barrier.
            pos = block.getBlockPos();
            fakeBarrier = false;
        } else {
            if (block.getType() != net.minecraft.util.hit.HitResult.Type.MISS) {
                // A real partial block is in the view path but outside native
                // reach. Do not replace the path with a blocking fake barrier.
                if (this.syntheticHolds.containsKey(player.getUuid())) clearSyntheticHold(player);
                return;
            }
            // Looking down while walking must never create an invisible wall in
            // the player's path. A real reachable block above took precedence.
            if (player.isOnGround() && direction.y < -.15 && player.getVelocity().horizontalLengthSquared() > .0001) {
                if (this.syntheticHolds.containsKey(player.getUuid())) clearSyntheticHold(player);
                return;
            }
            pos = reachableAir(from, direction, SYNTHETIC_HOLD_REACH);
            fakeBarrier = true;
        }
        if (pos == null) {
            if (this.syntheticHolds.containsKey(player.getUuid())) clearSyntheticHold(player);
            return;
        }
        BlockState actual = this.match.arenaWorld().getBlockState(pos);
        if (fakeBarrier && !actual.isAir()) {
            if (this.syntheticHolds.containsKey(player.getUuid())) clearSyntheticHold(player);
            return;
        }
        SyntheticHold existing = this.syntheticHolds.get(player.getUuid());
        if (existing != null && existing.pos().equals(pos)) return;
        if (existing != null) {
            player.networkHandler.sendPacket(new BlockUpdateS2CPacket(existing.pos(), existing.actual()));
            this.syntheticHolds.remove(player.getUuid());
        }
        this.syntheticHolds.put(player.getUuid(), new SyntheticHold(pos, actual));
        if (fakeBarrier) player.networkHandler.sendPacket(new BlockUpdateS2CPacket(pos, Blocks.BARRIER.getDefaultState()));
    }

    /** Find an air cell close enough for vanilla block-breaking reach. */
    private BlockPos reachableAir(Vec3d from, Vec3d direction, double distance) {
        double max = Math.min(SYNTHETIC_HOLD_REACH, distance);
        for (double current = max; current >= 1.25; current -= .25) {
            BlockPos candidate = BlockPos.ofFloored(from.add(direction.multiply(current)));
            if (this.match.arenaWorld().getBlockState(candidate).isAir()) return candidate;
        }
        return null;
    }

    /** Returns true when the packet belongs to the client-only synthetic block. */
    public boolean handleSyntheticAction(ServerPlayerEntity player, net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket packet) {
        SyntheticHold hold = this.syntheticHolds.get(player.getUuid());
        if (hold == null || !hold.pos().equals(packet.getPos())) return false;
        if (packet.getAction() == net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket.Action.START_DESTROY_BLOCK) {
            Fighter f = fighter(player);
            Gun gun = f == null ? null : f.gun(player.getInventory().selectedSlot);
            if (gun != null && gun.type.automatic()) {
                // A START for the client-only barrier proves that the button is
                // still held; only then may the server keep the lease alive.
                gun.autoArmed = true;
                gun.inputLease.refresh(this.ticks);
            }
        }
        if (packet.getAction() == net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK
                || packet.getAction() == net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK)
            onAttackRelease(player);
        return true;
    }

    private void clearSyntheticHold(ServerPlayerEntity player) {
        SyntheticHold hold = this.syntheticHolds.remove(player.getUuid());
        Fighter f = fighter(player);
        if (f != null) {
            for (Gun gun : new Gun[]{f.primary, f.pistol}) if (gun != null) {
                gun.inputLease.clear();
                gun.autoArmed = false;
            }
        }
        if (hold != null) player.networkHandler.sendPacket(new BlockUpdateS2CPacket(hold.pos(), hold.actual()));
    }

    /** 两种抛法共用库存/冷却，投出后切枪，避免按住右键把第二颗闪光也自动丢掉。 */
    private void throwGrenade(ServerPlayerEntity player, Fighter f, MyGoUtilities.Type type, MyGoUtilities.ThrowMode mode) {
        int count = f.grenades.getOrDefault(type, 0);
        if (!combatPhase() || !f.alive || this.ticks < f.throwReady || count <= 0
                || this.channel != null && this.channel.player().equals(f.id)) return;
        f.grenades.put(type, count - 1); f.throwReady = this.ticks + 20;
        this.utilities.launch(player, type, mode, this.ticks);
        player.getInventory().selectedSlot = f.primary != null ? 0 : f.pistol != null ? 1 : 2;
        player.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(player.getInventory().selectedSlot));
        onSelectedSlotChanged(player); syncEquipment(player, f);
    }

    public ActionResult attack(ServerPlayerEntity player, Entity entity) {
        Fighter f = fighter(player);
        if (f == null) return ActionResult.PASS;
        if (!f.alive || !combatPhase() || player.getInventory().selectedSlot != 2 || this.ticks < f.knifeReady) return ActionResult.FAIL;
        f.knifeReady = this.ticks + 12;
        if (entity instanceof ServerPlayerEntity target && fighter(target) != null && fighter(target).team != f.team
                && fighter(target).alive && target.getWorld() == player.getWorld() && player.squaredDistanceTo(target) <= 9
                && player.canSee(target)) damage(target, player, 40, "战术刀", 1500);
        return ActionResult.SUCCESS;
    }

    @Override public ActionResult useItem(ServerPlayerEntity player, Hand hand) {
        Fighter f = fighter(player);
        if (f == null) return ActionResult.PASS;
        if (hand != Hand.MAIN_HAND || !f.alive || this.ended) return ActionResult.FAIL;
        int slot = player.getInventory().selectedSlot;
        // SUCCESS 会向本人回送挥手动画，原版客户端又回发挥手包，曾导致右键先开一枪。
        // 自定义右键动作使用 CONSUME：处理交互但不要求原版播放攻击用的挥手动画。
        // 两种火雷共用第 8 格：按实际库存选择，不能遇到未持有的燃烧瓶便提前返回。
        MyGoUtilities.Type type = MyGoUtilities.Type.heldAt(f.grenades, slot);
        if (type != null) {
            throwGrenade(player, f, type, MyGoUtilities.ThrowMode.WEAK);
            return ActionResult.CONSUME;
        }
        if (slot == 8) { openShop(player); return ActionResult.CONSUME; }
        if (slot == 5) { beginChannel(player, f); return ActionResult.CONSUME; }
        Gun gun = f.gun(slot);
        if (gun != null) {
            // Right-click scope/mode changes must never inherit an automatic-fire lease.
            gun.inputLease.clear(); gun.autoArmed = false;
            clearSyntheticHold(player);
            if (combatPhase() && gun.type.scopeLevels() > 0) {
                // 原版按住右键每 4 tick 重复使用；同次实体／方块／物品交互也可能有多个包。
                // 连续输入仅延长去抖窗口，松开后再次点按才切换，避免镜框不停闪烁。
                int interval = this.ticks - f.scopeUseTick;
                f.scopeUseTick = this.ticks;
                if (interval > 5) {
                    f.resumeScopeLevel = 0; f.resumeScopeAt = 0;
                    f.scopeLevel = (f.scopeLevel + 1) % (gun.type.scopeLevels() + 1);
                    f.scoped = f.scopeLevel > 0; f.aimSince = this.ticks;
                    updateHead(player, f); updateSpeed(player, f);
                }
            } else if (combatPhase() && gun.type.hasBurstMode()) {
                gun.burstMode = !gun.burstMode; gun.inputLease.clear(); gun.autoArmed = false; gun.cadence.reset();
                actionBar(player, gun.burstMode ? "§e三连发模式" : "§e全自动模式");
            } else if (combatPhase() && gun.type == MyGoWeapon.REVOLVER) {
                gun.alternateFire = true; gun.inputLease.refresh(this.ticks); trigger(player, f, gun); gun.alternateFire = false;
            } else if (combatPhase() && gun.type.hasSilencer()) {
                gun.silenced = !gun.silenced; gun.inputLease.clear(); gun.autoArmed = false; gun.cadence.reset();
                actionBar(player, gun.silenced ? "§e已安装消音器" : "§e已拆下消音器");
            }
            return ActionResult.CONSUME;
        }
        return ActionResult.FAIL;
    }
    @Override public ActionResult useBlock(ServerPlayerEntity player, Hand hand, BlockHitResult hit) { return useItem(player, hand); }
    @Override public ActionResult attackBlock(ServerPlayerEntity player, BlockPos pos) { return ActionResult.FAIL; }
    @Override public boolean allowsDamage(LivingEntity victim, DamageSource source) { return false; }

    private void beginChannel(ServerPlayerEntity player, Fighter f) {
        if (this.phase != Phase.LIVE || !f.alive) return;
        if (!player.isSneaking()) { actionBar(player, "§e请保持潜行，再右键开始安装/拆除。"); return; }
        if (this.channel != null) {
            if (this.channel.player().equals(f.id)) return;
            actionBar(player, "§e已有玩家正在操作 C4。"); return;
        }
        boolean planting = !this.bombPlanted;
        BlockPos target = planting ? this.map.siteAt(player.getBlockPos()) : this.bombPosition;
        if (planting && (!attackers(f) || !f.id.equals(this.bombCarrier) || target == null)) {
            actionBar(player, "§e持有 C4 的进攻方可在 A / B 爆破点安装。"); return;
        }
        if (!planting && (attackers(f) || target == null || !near(player, target, 3))) return;
        int duration = planting ? MyGoRules.PLANT_TICKS : f.kit ? MyGoRules.KIT_DEFUSE_TICKS : MyGoRules.DEFUSE_TICKS;
        f.cancel(); this.channel = new Channel(f.id, planting ? player.getBlockPos() : target, player.getPos(), this.ticks + duration, planting);
        soundAt(player.getBlockPos(), planting ? "plant" : "defuse", 1);
        actionBar(player, "§e正在" + (planting ? "安装 C4" : "拆包") + "，保持潜行、站定并持有第 6 格。");
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
            if (this.ticks % 5 == 0) actionBar(p, "§e" + (action.planting() ? "安装" : "拆除") + "剩余 "
                    + String.format(Locale.ROOT, "%.1f", (action.completeAt() - this.ticks) / 20.0) + " 秒");
            return;
        }
        this.channel = null;
        if (action.planting()) {
            this.bombPlanted = true; this.bombPosition = action.target(); this.bombCarrier = null;
            this.deadline = this.ticks + MyGoRules.BOMB_TICKS;
            f.cash = MyGoRules.addMoney(f.cash, 300); f.objectiveScore += 1000; setBombMarker(this.bombPosition, "§c已安装 C4 · 防守方潜行右键第 6 格拆除");
            announce("§cC4 已安装！40 秒后爆炸，进攻方全灭也会继续计时。"); syncEquipment(p, f);
        } else { f.cash = MyGoRules.addMoney(f.cash, 300); f.objectiveScore += 1500; resolve(MyGoRules.Outcome.DEFENDERS, MyGoRules.WinCause.BOMB_DEFUSED); }
    }

    private void cancelChannel() { this.channel = null; }

    /** 原版 Q 由入口取消，装备掉落由本会话记录，避免复制枪械或把 C4 丢入虚空后卡死。 */
    public boolean drop(ServerPlayerEntity player) {
        Fighter f = fighter(player); if (f == null) return false;
        if (!f.alive || this.ended) return true;
        int slot = player.getInventory().selectedSlot;
        if (slot == 0 && f.primary != null) { dropGun(f.primary, player.getBlockPos()); f.primary = null; closeScope(player, f); }
        else if (slot == 1 && f.pistol != null) { dropGun(f.pistol, player.getBlockPos()); f.pistol = null; closeScope(player, f); }
        else if (slot == 5 && f.id.equals(this.bombCarrier)) dropBomb(player.getBlockPos());
        else {
            MyGoUtilities.Type grenade = MyGoUtilities.Type.heldAt(f.grenades, slot);
            if (grenade != null) {
                f.grenades.merge(grenade, -1, Integer::sum);
                dropEquipment(grenade, false, player.getBlockPos());
            }
        }
        syncEquipment(player, f); return true;
    }

    private void dropGun(Gun gun, BlockPos pos) {
        gun.cancel(); BlockPos safe = this.map.safeDrop(this.match.arenaWorld(), pos);
        ArmorStandEntity marker = label(safe, "§e" + gun.type.label + " · 空" + (gun.type.pistol() ? "副枪" : "主枪") + "槽靠近拾取");
        marker.equipStack(EquipmentSlot.MAINHAND, gunStack(gun));
        this.groundGuns.add(new GroundGun(gun, safe, this.ticks + 5, marker));
    }
    private void dropEquipment(MyGoUtilities.Type grenade, boolean kit, BlockPos pos) {
        BlockPos safe = this.map.safeDrop(this.match.arenaWorld(), pos);
        ArmorStandEntity marker = label(safe, "§e" + (kit ? "拆包器" : grenade.label) + " · 靠近拾取");
        marker.equipStack(EquipmentSlot.MAINHAND, kit ? model(Items.SHEARS, 73002, "拆包器") : grenade.stack(1));
        this.groundEquipment.add(new GroundEquipment(grenade, kit, safe, this.ticks + 5, marker));
    }
    private void dropBomb(BlockPos pos) {
        this.bombCarrier = null; this.bombGround = this.map.safeDrop(this.match.arenaWorld(), pos); cancelChannel();
        this.bombPickupAt = this.ticks + 20;
        setBombMarker(this.bombGround, "§6C4 · 进攻方靠近拾取");
    }
    private void tickPickups() {
        for (ServerPlayerEntity p : participants()) {
            Fighter f = fighter(p); if (!f.alive) continue;
            if (!this.bombPlanted && this.bombCarrier == null && this.bombGround != null && this.ticks >= this.bombPickupAt
                    && attackers(f) && pickupReachable(p, this.bombGround, 1.6)) {
                this.bombCarrier = f.id; this.bombGround = null; removeBombMarker(); syncEquipment(p, f);
                actionBar(p, "§6已拾取 C4。");
            }
            for (GroundGun drop : List.copyOf(this.groundGuns)) {
                if (drop.gun.type.pistol() ? f.pistol != null : f.primary != null) continue;
                if (this.ticks >= drop.pickupAt && pickupReachable(p, drop.pos, 1.6)) {
                    if (drop.gun.type.pistol()) f.pistol = drop.gun; else f.primary = drop.gun;
                    this.groundGuns.remove(drop); discard(drop.marker); syncEquipment(p, f); break;
                }
            }
            for (GroundEquipment drop : List.copyOf(this.groundEquipment)) {
                if (this.ticks < drop.pickupAt || !pickupReachable(p, drop.pos, 1.6)) continue;
                if (drop.kit) {
                    if (attackers(f) || f.kit) continue;
                    f.kit = true;
                } else {
                    if (!canCarry(f, drop.grenade)) continue;
                    f.grenades.merge(drop.grenade, 1, Integer::sum);
                }
                this.groundEquipment.remove(drop); discard(drop.marker); syncEquipment(p, f);
            }
        }
    }
    private boolean canCarry(Fighter f, MyGoUtilities.Type type) {
        if (f.grenades.values().stream().mapToInt(Integer::intValue).sum() >= 4
                || f.grenades.getOrDefault(type, 0) >= type.maximum) return false;
        return !type.fire() || f.grenades.entrySet().stream().noneMatch(e -> e.getKey().fire() && e.getValue() > 0);
    }
    private boolean pickupReachable(ServerPlayerEntity player, BlockPos pos, double radius) {
        return near(player, pos, radius) && (near(player, pos, .45) || visible(player, Vec3d.ofCenter(pos)));
    }
    private boolean visible(ServerPlayerEntity player, Vec3d point) {
        BlockHitResult wall = this.match.arenaWorld().raycast(new RaycastContext(player.getEyePos(), point,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        return wall.getType() == net.minecraft.util.hit.HitResult.Type.MISS || wall.getPos().squaredDistanceTo(point) < .04;
    }
    /** Sneak-use or /pvp mygo pickup swaps a looked-at ground gun even when its slot is occupied. */
    public boolean pickup(ServerPlayerEntity player) {
        Fighter f = fighter(player);
        if (f == null || !f.alive) return false;
        GroundGun selected = null;
        double nearest = 9;
        for (GroundGun drop : this.groundGuns) {
            Vec3d point = Vec3d.ofCenter(drop.pos);
            double distance = player.getEyePos().squaredDistanceTo(point);
            if (this.ticks < drop.pickupAt || distance > nearest || !pickupReachable(player, drop.pos, 2.5)
                    || player.getRotationVec(1).dotProduct(point.subtract(player.getEyePos()).normalize()) < .94) continue;
            selected = drop; nearest = distance;
        }
        if (selected == null) return false;
        Gun old = selected.gun.type.pistol() ? f.pistol : f.primary;
        this.groundGuns.remove(selected); discard(selected.marker);
        if (old != null) dropGun(old, player.getBlockPos());
        if (selected.gun.type.pistol()) f.pistol = selected.gun; else f.primary = selected.gun;
        f.selected = -1; onSelectedSlotChanged(player); syncEquipment(player, f);
        return true;
    }

    public boolean buy(ServerPlayerEntity player, String id) {
        Fighter f = fighter(player);
        if (!canBuy(player, f)) {
            actionBar(player, "§c只能在购买时限内、本方购买区购买。"); return false;
        }
        String key = id.toLowerCase(Locale.ROOT);
        if ("defuser".equals(key) || "defuse".equals(key)) key = "kit";
        MyGoWeapon type = MyGoWeapon.parse(key);
        MyGoUtilities.Type utility = MyGoUtilities.Type.parse(key);
        int cost;
        if (type != null) {
            if (!type.availableTo(attackers(f))) { actionBar(player, "§c本阵营不可购买 " + type.label + "，可拾取敌方掉落枪械。"); return false; }
            cost = type.price;
        } else if (utility != null) {
            if (!utility.availableTo(attackers(f))) { actionBar(player, "§e本阵营不可购买该投掷物，可拾取敌方掉落。"); return false; }
            if (!canCarry(f, utility)) { actionBar(player, "§e已达到投掷物携带上限（合计 4 颗、火雷合计 1 颗）。"); return false; }
            if (!f.roundEquipment.canBuy(utility)) { actionBar(player, "§e本回合该投掷物购买额度已用完；投出或丢弃不会恢复额度。"); return false; }
            cost = utility.price;
        } else if ("vest".equals(key)) {
            cost = MyGoRules.armorCost(f.armor, f.helmet, false); if (cost == 0) return false;
        } else if ("helmet".equals(key)) {
            cost = MyGoRules.armorCost(f.armor, f.helmet, true); if (cost == 0) return false;
        } else if ("kit".equals(key)) {
            if (attackers(f) || f.kit) { actionBar(player, "§e拆包器仅防守方可购买一次。"); return false; }
            cost = 400;
        } else { player.sendMessage(Text.literal("§c未知商品，请打开 /pvp mygo 查看军械库。"), false); return false; }
        if (f.cash < cost) { actionBar(player, "§c现金不足，需要 $" + cost); return false; }
        f.cash -= cost;
        if (type != null) {
            if (player.getInventory().selectedSlot == (type.pistol() ? 1 : 0)) closeScope(player, f);
            // Reason: 购买同一槽位的新枪不应静默覆盖旧枪；先生成可拾取掉落，保留旧枪及其剩余弹药。
            if (type.pistol()) {
                if (f.pistol != null) dropGun(f.pistol, player.getBlockPos());
                f.pistol = new Gun(type);
            } else {
                if (f.primary != null) dropGun(f.primary, player.getBlockPos());
                f.primary = new Gun(type);
            }
        }
        else if (utility != null) { f.roundEquipment.recordPurchase(utility); f.grenades.merge(utility, 1, Integer::sum); }
        else if ("vest".equals(key)) { f.armor = 100; f.vest = true; }
        else if ("helmet".equals(key)) { f.armor = 100; f.vest = true; f.helmet = true; }
        else f.kit = true;
        syncEquipment(player, f); actionBar(player, "§a购买成功"); return true;
    }

    public void openShop(ServerPlayerEntity player) {
        Fighter f = fighter(player);
        if (!canBuy(player, f)) {
            actionBar(player, "§e军械库限冻结阶段及解冻后 20 秒内，在本方购买区使用。"); return;
        }
        player.openHandledScreen(new NamedScreenHandlerFactory() {
            @Override public Text getDisplayName() { return Text.literal("MyGO 军械库 · " + (attackers(f) ? "进攻方" : "防守方")); }
            @Override public ScreenHandler createMenu(int syncId, PlayerInventory inventory, PlayerEntity p) { return new ShopHandler(syncId, inventory, player); }
        });
    }

    private final class ShopHandler extends ScreenHandler {
        private final SimpleInventory inventory = new SimpleInventory(54);
        private final Map<Integer, String> products = new HashMap<>();
        ShopHandler(int syncId, PlayerInventory player, ServerPlayerEntity buyer) {
            super(ScreenHandlerType.GENERIC_9X6, syncId);
            for (int i = 0; i < 54; i++) addSlot(new Slot(this.inventory, i, 8 + i % 9 * 18, 18 + i / 9 * 18));
            for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++) addSlot(new Slot(player, 9 + row * 9 + col, 8 + col * 18, 140 + row * 18));
            for (int i = 0; i < 9; i++) addSlot(new Slot(player, i, 8 + i * 18, 198));
            redraw(buyer);
        }
        private void redraw(ServerPlayerEntity buyer) {
            Fighter f = fighter(buyer); this.products.clear();
            for (int i = 0; i < 54; i++) this.inventory.setStack(i, named(Items.GRAY_STAINED_GLASS_PANE, " "));
            this.inventory.setStack(0, named(Items.IRON_INGOT, "§fCS:GO 军械库 · 主/副枪独立装备"));
            this.inventory.setStack(4, named(Items.EMERALD, "§6现金 $" + f.cash));
            this.inventory.setStack(8, named(Items.CLOCK, "§e购买剩余 " + time(buyUntil - ticks)));
            for (MyGoWeapon weapon : MyGoWeapon.values()) {
                int slot = 9 + weapon.ordinal();
                ItemStack icon = gunStack(new Gun(weapon));
                boolean allowed = weapon.availableTo(attackers(f));
                icon.set(DataComponentTypes.CUSTOM_NAME, Text.literal((allowed ? "§e" : "§c") + weapon.label + " · $" + weapon.price));
                icon.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                        Text.literal("§7" + weapon.category() + " · " + weapon.magazine + " 发 · 换弹 " + weapon.reloadTicks / 20.0 + " 秒"),
                        Text.literal("§7基础伤害 " + weapon.damage + (weapon.pellets() > 1 ? " × " + weapon.pellets() : "")
                                + " · 穿甲 " + String.format(Locale.ROOT, "%.2f%%", weapon.penetration * 100)),
                        Text.literal("§7部位倍率：头 " + weapon.headshotMultiplier + " · 胸 1 · 腹 1.25 · 腿 0.75"),
                        Text.literal(!allowed ? "§c本阵营不可购买" : "§a点击购买 · 击杀奖励 $" + weapon.killReward))));
                this.inventory.setStack(slot, icon); this.products.put(slot, weapon.name().toLowerCase(Locale.ROOT));
            }
            String[] ids = {"vest", "helmet", "kit"};
            String[] names = {"防弹衣/补甲 · $650", "防弹衣+头盔 · $" + MyGoRules.armorCost(f.armor, f.helmet, true), "拆包器 · $400（仅防守方）"};
            Item[] items = {Items.LEATHER_CHESTPLATE, Items.LEATHER_HELMET, Items.SHEARS};
            for (int i = 0; i < ids.length; i++) { this.inventory.setStack(45 + i, named(items[i], "§b" + names[i])); this.products.put(45 + i, ids[i]); }
            for (MyGoUtilities.Type utility : MyGoUtilities.Type.values()) {
                int slot = 48 + utility.ordinal(); ItemStack icon = utility.stack(1);
                icon.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§e" + utility.label + " · $" + utility.price));
                icon.set(DataComponentTypes.LORE, new LoreComponent(List.of(Text.literal("§7携带 " + f.grenades.getOrDefault(utility, 0) + "/" + utility.maximum + " · 热键 " + (utility.slot + 1)),
                        Text.literal("§7左键大力高抛 · 右键小力低抛"),
                        Text.literal(utility == MyGoUtilities.Type.SMOKE ? "§7落稳起烟，持续 18 秒，子弹可穿过" : utility == MyGoUtilities.Type.FLASH ? "§7背对闪光、掩体遮挡可降低效果（含队友/自己）" : "§7范围伤害，护甲与掩体可减伤"))));
                this.inventory.setStack(slot, icon); this.products.put(slot, utility.name().toLowerCase(Locale.ROOT));
            }
            this.inventory.setStack(43, named(Items.PAPER, "§7左键点射 · 瞄具枪右键倍率/关镜 · F 换弹 · Q 丢枪"));
        }
        @Override public void onSlotClick(int index, int button, SlotActionType action, PlayerEntity p) {
            if (p instanceof ServerPlayerEntity sp && this.products.containsKey(index)
                    && (action == SlotActionType.PICKUP || action == SlotActionType.QUICK_MOVE)) {
                buy(sp, this.products.get(index)); redraw(sp); sendContentUpdates();
            }
        }
        @Override public boolean canUse(PlayerEntity p) { Fighter f = fighters.get(p.getUuid()); return p instanceof ServerPlayerEntity sp && canBuy(sp, f); }
        @Override public ItemStack quickMove(PlayerEntity player, int index) { return ItemStack.EMPTY; }
    }

    private void resolve(MyGoRules.Outcome outcome, MyGoRules.WinCause cause) {
        String reason = switch (cause) {
            case BOMB_EXPLODED -> "C4 爆炸"; case BOMB_DEFUSED -> "C4 已拆除";
            case TIMEOUT -> "进攻超时"; case ELIMINATION -> outcome == MyGoRules.Outcome.ATTACKERS ? "防守方全灭" : "进攻方全灭";
        };
        if (this.phase != Phase.LIVE || this.ended) return;
        int attackerTeam = MyGoRules.switchedSides(this.round) ? 1 : 0;
        int winner = outcome == MyGoRules.Outcome.ATTACKERS ? attackerTeam : 1 - attackerTeam;
        this.phase = Phase.POST; this.deadline = this.ticks + MyGoRules.POST_TICKS; cancelChannel(); this.scores[winner]++;
        int loser = 1 - winner;
        int loserReward = MyGoRules.lossReward(this.losses[loser]);
        this.losses[winner] = MyGoRules.afterWin(this.losses[winner]);
        this.losses[loser] = MyGoRules.afterLoss(this.losses[loser]);
        for (ServerPlayerEntity p : participants()) {
            Fighter f = fighter(p);
            // Keep reload/scope state during the playable post-round interval, as during normal combat.
            int reward = f.team == winner ? MyGoRules.roundReward(cause) : loserReward;
            if (outcome == MyGoRules.Outcome.DEFENDERS && !this.bombPlanted && "进攻超时".equals(reason) && attackers(f) && f.alive) reward = 0;
            if (cause == MyGoRules.WinCause.BOMB_DEFUSED && f.team != winner && attackers(f)) reward += 800;
            f.cash = MyGoRules.addMoney(f.cash, reward);
            p.sendMessage(Text.literal("§6回合" + (f.team == winner ? "胜利" : "失败") + " · " + reason + " · +$" + reward), false);
        }
        announce("§6回合结束：队伍 " + (winner + 1) + " 获胜 · " + this.scores[0] + ":" + this.scores[1]);
        this.fighters.values().stream().filter(f -> f.team == winner && this.active.contains(f.id))
                .max(Comparator.comparingDouble(f -> f.objectiveScore + f.roundKills * 100 + f.roundDamage))
                .filter(f -> f.objectiveScore > 0 || f.roundKills > 0)
                .ifPresent(f -> { ServerPlayerEntity mvp = this.match.onlinePlayer(f.id);
                    if (mvp != null) announce("§e★ MVP " + mvp.getName().getString() + " · " + f.roundKills + " 击杀 / " + String.format(Locale.ROOT, "%.0f", f.roundDamage) + " 伤害" + (f.objectiveScore > 0 ? " · 爆破贡献" : "")); });
        if (MyGoRules.finished(this.scores[0], this.scores[1])) {
            Integer win = this.scores[0] == this.scores[1] ? null : this.scores[0] > this.scores[1] ? 0 : 1;
            finish(win, win == null ? "§eMyGO 15:15 平局。" : "§6MyGO 队伍 " + (win + 1) + " 获胜！");
        }
        updateBar();
    }

    private void checkElimination() {
        if (this.phase != Phase.LIVE) return;
        int a = 0, d = 0;
        for (Fighter f : this.fighters.values()) if (this.active.contains(f.id) && f.alive) { if (attackers(f)) a++; else d++; }
        MyGoRules.Outcome outcome = MyGoRules.eliminationOutcome(this.bombPlanted, a, d);
        if (outcome != null) resolve(outcome, MyGoRules.WinCause.ELIMINATION);
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
    @Override public void onDeath(ServerPlayerEntity player) {
        if (this.ended) return;
        Fighter f = fighter(player);
        // 全局虚空兜底可能先于会话 tick；同样救援，不能绕回原来的环境阵亡路径。
        if (f != null && f.alive && (player.getWorld() != this.match.arenaWorld() || !this.map.inBounds(player.getBlockPos())))
            recoverPosition(player, f);
        else kill(player, null, "环境异常");
    }
    @Override public void onLeave(ServerPlayerEntity player) { remove(player.getUuid(), player); }
    private void remove(UUID id, ServerPlayerEntity player) {
        if (this.ended) return;
        if (!this.active.remove(id)) return;
        if (player != null) clearSyntheticHold(player);
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
        this.map.closeDecorations();
        for (Fighter f : this.fighters.values()) {
            f.cancel(); ServerPlayerEntity p = this.match.onlinePlayer(f.id);
            if (p != null) clearSyntheticHold(p);
            if (p != null && this.active.contains(f.id)) { p.closeHandledScreen(); restoreHead(p, f); removeSpeed(p, f); }
        }
        this.timer.clearPlayers(); clearRoundEntities();
        for (ArmorStandEntity label : this.labels) label.discard();
        this.labels.clear(); this.ownedEntities.clear();
    }
    @Override public void clearArena() { this.map.clear(this.match.arenaWorld()); }
    @Override public boolean ownsEntity(Entity entity) { return entity != null && (this.ownedEntities.containsKey(entity.getUuid()) || this.utilities.owns(entity)); }
    @Override public List<String> scoreboardLines() {
        long attackAlive = this.fighters.values().stream().filter(f -> this.active.contains(f.id) && f.alive && attackers(f)).count();
        long defendAlive = this.fighters.values().stream().filter(f -> this.active.contains(f.id) && f.alive && !attackers(f)).count();
        return List.of("§6MyGO · 经济爆破", "§f队伍1 §c" + this.scores[0] + " §7: §9" + this.scores[1] + " §f队伍2",
                "§e第 " + Math.min(MyGoRules.MAX_ROUNDS, this.round) + " 回合 · " + phaseName(), "§f剩余 " + time(this.deadline - this.ticks),
                "§cT 存活 " + attackAlive + " §7/ §9CT 存活 " + defendAlive,
                this.bombPlanted ? "§cC4 已安装" : "§7C4 尚未安装", "§7左键点射 · F 换弹", "§7瞄具枪右键倍率 · /pvp mygo 商店");
    }

    public String debugState(ServerPlayerEntity player) {
        Fighter f = fighter(player); Gun gun = f == null ? null : f.gun(player.getInventory().selectedSlot);
        MyGoUtilities.LaunchInfo launch = this.utilities.lastLaunch(player.getUuid());
        return "MyGO round=" + this.round + " phase=" + this.phase + " remainingTicks=" + Math.max(0, this.deadline - this.ticks)
                + " score=" + this.scores[0] + ":" + this.scores[1] + " team=" + (f == null ? -1 : f.team)
                + " side=" + (f != null && attackers(f) ? "T" : "CT") + " alive=" + (f != null && f.alive)
                + " hp=" + (f == null ? 0 : f.hp) + " cash=" + (f == null ? 0 : f.cash)
                + " gun=" + (gun == null ? "none" : gun.type) + " ammo=" + (gun == null ? 0 : gun.ammo)
                + " scoped=" + (f != null && f.scoped) + " scopeLevel=" + (f == null ? 0 : f.scopeLevel)
                + " scopeFov=" + (f != null && gun != null && f.scopeLevel > 0 ? gun.type.scopeFov(f.scopeLevel) : 70)
                + " usingItem=" + player.isUsingItem() + " awZoom=" + (f != null && f.awZoom)
                + " moveSpeed=" + player.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED)
                + " walkSpeed=" + player.getAbilities().getWalkSpeed()
                + " buyTicks=" + Math.max(0, this.buyUntil - this.ticks) + " lossLevel=" + (f == null ? 0 : this.losses[f.team])
                + " armor=" + (f == null ? 0 : f.armor) + " pistol=" + (f == null || f.pistol == null ? "none" : f.pistol.type)
                + " reserve=" + (gun == null ? 0 : gun.reserve) + " vest=" + (f != null && f.vest)
                + " kills=" + (f == null ? 0 : f.kills) + " grenades=" + (f == null ? "none" : f.grenades)
                + " activeGrenades=" + this.utilities.activeGrenades() + " smokes=" + this.utilities.activeSmokes()
                + " throwMode=" + (launch == null ? "NONE" : launch.mode()) + " throwType=" + (launch == null ? "none" : launch.type())
                + " throwOrigin=" + vector(launch == null ? Vec3d.ZERO : launch.origin())
                + " throwVelocity=" + vector(launch == null ? Vec3d.ZERO : launch.velocity())
                + " throwTick=" + (launch == null ? -1 : launch.tick())
                + " stepSounds=" + (f == null ? 0 : f.stepSounds) + " lastStepCue=" + (f == null ? "NONE" : f.lastStepCue)
                + " lastStepSurface=" + (f == null ? "none" : f.lastStepSurface)
                + " helmet=" + (f != null && f.helmet) + " bombPlanted=" + this.bombPlanted
                + " map=" + this.map.id() + " range=" + compact(this.map.testAnchor("range"))
                + " waiting=" + compact(this.map.spectator())
                + " spawn=" + compact(initialSpawn(player))
                + " bombCarrier=" + this.bombCarrier + " bomb=" + this.bombPosition + " sites=" + this.map.sites();
    }

    private BlockPos initialSpawn(ServerPlayerEntity player) {
        List<ServerPlayerEntity> players = this.match.players();
        for (int i = 0; i < players.size() && i < this.initialSpawns.size(); i++) {
            if (players.get(i).getUuid().equals(player.getUuid())) return this.initialSpawns.get(i);
        }
        Fighter fighter = fighter(player);
        return this.map.spawn(fighter != null && attackers(fighter), 0);
    }

    private static String compact(BlockPos pos) { return pos.getX() + "," + pos.getY() + "," + pos.getZ(); }
    private static String vector(Vec3d pos) { return String.format(Locale.ROOT, "%.5f,%.5f,%.5f", pos.x, pos.y, pos.z); }

    private void syncEquipment(ServerPlayerEntity p, Fighter f) {
        if (!f.alive || this.ended) return;
        for (int i = 0; i < p.getInventory().size(); i++) {
            if (i <= 8 || i == 38 || i == 39) continue;
            if (!p.getInventory().getStack(i).isEmpty()) p.getInventory().setStack(i, ItemStack.EMPTY);
        }
        setSlot(p, 0, f.primary == null ? ItemStack.EMPTY : gunStack(f.primary));
        setSlot(p, 1, f.pistol == null ? ItemStack.EMPTY : gunStack(f.pistol));
        ItemStack knife = model(Items.IRON_SWORD, 71006, "§f战术刀 · 左键"); setSlot(p, 2, knife);
        ItemStack objective = attackers(f) ? (f.id.equals(this.bombCarrier) ? model(Items.REDSTONE, 73001, "§6C4 · 包点潜行右键安装") : ItemStack.EMPTY)
                : model(f.kit ? Items.SHEARS : Items.STICK, f.kit ? 73002 : 0, "§b" + (f.kit ? "拆包器 · 5 秒" : "徒手拆包 · 10 秒") + " · 潜行右键");
        // 按槽只写一次，避免未持有的另一种火雷清空已买的火雷，留下不可见的占额库存。
        // 第 9 格优先放诱饵弹；用完后恢复商店，持有期间仍可用 /pvp mygo 打开商店。
        for (int slot = 3; slot <= 8; slot++) {
            if (slot == 5) continue;
            MyGoUtilities.Type type = MyGoUtilities.Type.heldAt(f.grenades, slot);
            ItemStack stack = type != null ? type.stack(f.grenades.get(type))
                    : slot == 8 ? named(Items.EMERALD, "§aMyGO 商店 · 购买阶段右键") : ItemStack.EMPTY;
            setSlot(p, slot, stack);
        }
        setSlot(p, 5, objective);
        ItemStack teamArmor = named(Items.LEATHER_CHESTPLATE, attackers(f) ? "§c进攻方制服" : "§9防守方制服");
        teamArmor.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(attackers(f) ? 0xD93935 : 0x3478DB, false));
        if (!ItemStack.areEqual(p.getEquippedStack(EquipmentSlot.CHEST), teamArmor)) p.equipStack(EquipmentSlot.CHEST, teamArmor);
        updateHead(p, f); p.currentScreenHandler.sendContentUpdates();
    }
    private static void setSlot(ServerPlayerEntity p, int slot, ItemStack stack) {
        if (!ItemStack.areEqual(p.getInventory().getStack(slot), stack)) p.getInventory().setStack(slot, stack);
    }
    private ItemStack gunStack(Gun gun) {
        int pose = gun.reloadAt > this.ticks ? 200 : gun.lastShot > 0 && this.ticks < gun.lastShot + 2 ? 100 : 0;
        ItemStack stack = model(Items.IRON_PICKAXE, gun.type.model + pose, "§f" + gun.type.label + " §7[" + gun.ammo + "/" + gun.reserve + "]");
        // Adventure clients refuse to start block breaking unless the held item
        // explicitly allows the target. Server-side input handling cancels the
        // corresponding block action, so real map blocks remain unchanged.
        stack.set(DataComponentTypes.CAN_BREAK, new BlockPredicatesChecker(
                List.of(new BlockPredicate(
                        Optional.empty(),
                        Optional.empty(), Optional.empty())), false));
        return stack;
    }
    private static ItemStack model(Item item, int model, String name) {
        ItemStack stack = named(item, name); stack.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(model)); return stack;
    }
    private static ItemStack named(Item item, String name) { ItemStack stack = new ItemStack(item); stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name)); return stack; }
    private void updateHead(ServerPlayerEntity p, Fighter f) {
        ItemStack expected = f.scoped ? model(Items.CARVED_PUMPKIN, 71010, "§7MyGO 瞄准镜") : ItemStack.EMPTY;
        if (!ItemStack.areEqual(p.getEquippedStack(EquipmentSlot.HEAD), expected)) p.equipStack(EquipmentSlot.HEAD, expected);
        updateScopeZoom(p, f);
    }
    private void closeScope(ServerPlayerEntity p, Fighter f) {
        f.scoped = false; f.scopeLevel = 0; f.resumeScopeLevel = 0; f.resumeScopeAt = 0; updateHead(p, f); updateSpeed(p, f);
    }
    private void restoreHead(ServerPlayerEntity p, Fighter f) { f.scoped = false; f.scopeLevel = 0; p.equipStack(EquipmentSlot.HEAD, f.oldHead.copy()); updateScopeZoom(p, f); }

    /** 只给有瞄具的枪发送视野基准；保持真实权限、真实移动速度不变。 */
    private void updateScopeZoom(ServerPlayerEntity p, Fighter f) {
        Gun gun = f.gun(p.getInventory().selectedSlot);
        boolean zoom = !this.ended && combatPhase() && f.alive && f.scoped && gun != null && gun.type.scopeLevels() > 0;
        f.awZoom = zoom && gun.type == MyGoWeapon.AW;
        if (zoom) f.scopeView.update(p, f.scopeLevel, gun.type.scopeFov(f.scopeLevel));
        else f.scopeView.reset(p);
    }
    private void updateSpeed(ServerPlayerEntity p, Fighter f) {
        Gun gun = f.gun(p.getInventory().selectedSlot);
        // One block is one metre. Vanilla normal walking is 4.317 m/s; Source max speeds are units/s.
        double units = gun == null ? 250 : gun.type.profile().maxSpeed(alternateMode(f, gun));
        double factor = units / MyGoWeapon.SOURCE_UNITS_PER_BLOCK / 4.317;
        if (p.isSprinting()) factor *= .52 / 1.3; // Vanilla sprint key becomes CS-style quiet walking.
        if (p.isSneaking()) factor *= .34 / .3;
        if (gun != null && gun.accuracy.recentlyFired(this.ticks)) factor *= gun.type.profile().attackMoveFactor();
        double tag = Math.max(0, 1 - (this.ticks - f.taggedAt) / 14.0) * f.tagSlowdown;
        factor *= 1 - tag;
        double amount = this.phase == Phase.BUY ? -1 : Math.max(.05, factor) - 1;
        if (Double.compare(amount, f.speed) == 0) { updateScopeZoom(p, f); return; }
        var attr = p.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (attr != null) { attr.removeModifier(SPEED_ID); if (amount != 0) attr.addTemporaryModifier(new EntityAttributeModifier(SPEED_ID, amount, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)); }
        f.speed = amount; updateScopeZoom(p, f);
    }
    private void removeSpeed(ServerPlayerEntity p, Fighter f) { var attr = p.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED); if (attr != null) attr.removeModifier(SPEED_ID); f.speed = Double.NaN; }
    /** 金钱固定显示在快捷栏上方，并保留在购枪、命中、换弹和拆包提示中。 */
    private void actionBar(ServerPlayerEntity player, String detail) {
        Fighter f = fighter(player);
        String money = f == null ? "" : "§6§l$ " + String.format(Locale.ROOT, "%,d", f.cash) + "§r  §8│§r  ";
        player.sendMessage(Text.literal(money + detail), true);
    }
    private void sendHud(ServerPlayerEntity p, Fighter f) {
        if (this.channel != null && this.channel.player().equals(f.id) || this.ticks < f.feedbackUntil) return;
        Gun gun = f.gun(p.getInventory().selectedSlot);
        String weapon = gun == null ? "" : " · " + gun.type.label + " " + gun.ammo + "/" + gun.reserve + (gun.reloadAt > 0 ? " 换弹 " + (int) (100 * (1 - (gun.reloadAt - this.ticks) / (double) gun.type.reloadTicks)) + "%" : "");
        actionBar(p, "§a" + String.format(Locale.ROOT, "%.1f", f.hp) + " HP"
                + " §7K/D " + f.kills + "/" + f.deaths + " · " + (f.vest ? "甲 " + (int) Math.ceil(f.armor) : "无甲") + (f.helmet ? "+盔" : "") + weapon);
    }
    private void updateBar() {
        int total = this.phase == Phase.BUY ? MyGoRules.FREEZE_TICKS : this.phase == Phase.POST ? MyGoRules.POST_TICKS : this.bombPlanted ? MyGoRules.BOMB_TICKS : MyGoRules.ROUND_TICKS;
        this.timer.setName(Text.literal("MyGO " + this.scores[0] + ":" + this.scores[1] + " · " + phaseName() + " " + time(this.deadline - this.ticks)));
        this.timer.setColor(this.bombPlanted ? BossBar.Color.RED : this.phase == Phase.BUY ? BossBar.Color.BLUE : BossBar.Color.YELLOW);
        this.timer.setPercent(Math.max(0, Math.min(1, (float) (this.deadline - this.ticks) / total)));
    }
    private String phaseName() { return switch (this.phase) { case BUY -> "冻结购买"; case LIVE -> this.bombPlanted ? "C4 倒计时" : "作战"; case POST -> "回合结束"; case FINISHED -> "比赛结束"; }; }
    private static String time(int ticks) { int sec = Math.max(0, (ticks + 19) / 20); return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60); }
    private boolean combatPhase() { return this.phase == Phase.LIVE || this.phase == Phase.POST; }
    private static boolean alternateMode(Fighter f, Gun gun) {
        return f.scoped && gun.type.scopeLevels() > 0 || gun.burstMode || gun.silenced && gun.type.hasSilencer()
                || gun.alternateFire && gun.type == MyGoWeapon.REVOLVER;
    }
    private boolean canBuy(ServerPlayerEntity p, Fighter f) { return !this.ended && f != null && f.alive
            && (this.phase == Phase.BUY || this.phase == Phase.LIVE && this.ticks < this.buyUntil)
            && this.map.inBuyZone(p.getBlockPos(), attackers(f)); }
    private boolean attackers(Fighter f) { return f.team == (MyGoRules.switchedSides(this.round) ? 1 : 0); }
    private Fighter fighter(ServerPlayerEntity p) { return this.fighters.get(p.getUuid()); }
    private List<ServerPlayerEntity> participants() { return this.match.onlineParticipants().stream().filter(p -> this.active.contains(p.getUuid())).toList(); }
    private void announce(String text) { this.match.broadcastToMatch(Text.literal(text)); }
    private void teleportHome(ServerPlayerEntity p) {
        Fighter f = fighter(p); int index = 0;
        for (Fighter each : this.fighters.values()) { if (each.id.equals(f.id)) break; if (each.team == f.team) index++; }
        teleport(p, this.map.spawn(attackers(f), index), this.map.spawnYaw(attackers(f), index));
        f.last = p.getPos(); f.lastSafe = p.getPos();
        f.footsteps.reset();
    }
    private void recoverPosition(ServerPlayerEntity p, Fighter f) {
        f.footsteps.reset();
        if (this.channel != null && this.channel.player().equals(f.id)) cancelChannel();
        if (f.lastSafe == null || !this.map.inBounds(BlockPos.ofFloored(f.lastSafe))) teleportHome(p);
        else {
            p.teleport(this.match.arenaWorld(), f.lastSafe.x, f.lastSafe.y, f.lastSafe.z, p.getYaw(), p.getPitch());
            p.setVelocity(Vec3d.ZERO); p.fallDistance = 0; f.last = p.getPos();
        }
        actionBar(p, "§e已返回最近的安全落脚点。");
    }
    /** Team-only spectator follow replaces the old sealed room while retaining server-side death state. */
    private void tickSpectator(ServerPlayerEntity player, Fighter f) {
        if (player.getWorld() != this.match.arenaWorld() || player.squaredDistanceTo(Vec3d.ofCenter(this.map.spectator())) > 16)
            teleport(player, this.map.spectator(), 0);
        if (f.spectating != null) {
            ServerPlayerEntity current = this.match.onlinePlayer(f.spectating);
            if (current != null && fighter(current) != null && fighter(current).alive) return;
            f.spectating = null;
        }
        ServerPlayerEntity teammate = participants().stream()
                .filter(other -> !other.getUuid().equals(f.id))
                .filter(other -> fighter(other).team == f.team && fighter(other).alive)
                .findFirst().orElse(null);
        if (teammate != null) {
            f.spectating = teammate.getUuid();
            player.setCameraEntity(teammate);
        } else player.setCameraEntity(player);
    }
    /** 资源包屏蔽原版脚步后，只由本场实际位移发出具有位置和材质的声源。 */
    private void tickMovementSounds(ServerPlayerEntity player, Fighter f) {
        MyGoFootsteps.Cue cue = f.footsteps.update(this.ticks, player.getX(), player.getY(), player.getZ(),
                player.isOnGround(), player.isSneaking() || player.isSprinting(), false, player.isTouchingWater(), player.isClimbing());
        if (cue == MyGoFootsteps.Cue.NONE) return;
        String surface = footstepSurface(player);
        String key = switch (cue) {
            case STEP -> "step." + surface;
            case LAND -> "land." + surface;
            case JUMP -> "jump";
            case NONE -> throw new IllegalStateException("No footstep cue");
        };
        if (cue == MyGoFootsteps.Cue.STEP) f.stepSounds++;
        f.lastStepCue = cue.name(); f.lastStepSurface = surface;
        float volume = cue == MyGoFootsteps.Cue.JUMP ? .55F : player.isSprinting() ? 1 : .8F;
        this.match.arenaWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvent.of(Identifier.of("pvp", "mygo." + key)), SoundCategory.PLAYERS, volume, 1);
    }
    private String footstepSurface(ServerPlayerEntity player) {
        if (player.isTouchingWater()) return "water";
        if (player.isClimbing()) return "wood";
        String sound = this.match.arenaWorld().getBlockState(BlockPos.ofFloored(player.getX(), player.getY() - .12, player.getZ()))
                .getSoundGroup().getStepSound().getId().getPath();
        if (sound.contains("metal") || sound.contains("copper") || sound.contains("chain") || sound.contains("anvil")
                || sound.contains("netherite")) return "metal";
        if (sound.contains("wood") || sound.contains("bamboo") || sound.contains("scaffolding") || sound.contains("ladder")) return "wood";
        if (sound.contains("grass") || sound.contains("gravel") || sound.contains("sand") || sound.contains("snow")
                || sound.contains("mud") || sound.contains("moss") || sound.contains("wool")) return "dirt";
        return "stone";
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
    private void clearRoundEntities() {
        this.utilities.clear(); this.lagHistory.clear(); removeBombMarker();
        for (GroundGun drop : List.copyOf(this.groundGuns)) discard(drop.marker);
        for (GroundEquipment drop : List.copyOf(this.groundEquipment)) discard(drop.marker);
        this.groundGuns.clear(); this.groundEquipment.clear();
    }
}
