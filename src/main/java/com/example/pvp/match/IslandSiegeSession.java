package com.example.pvp.match;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.arena.islandsiege.IslandSiegeArena;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 同服运行 Island Siege 的原生攻防状态机。
 * 原图的 1.21.8 命令方块会修改全服队伍、计分板和玩家，因此本类将状态限制在 Match 内。
 * 审计原包得到建造 4 分钟、进攻 8 分钟；保留三目标完成两个获胜的主规则。
 * 地形由 IslandSiegeArena 重建；守卫、物资、夺取时间是本次移植的原生平衡参数，非原包逐命令复制。
 */
public final class IslandSiegeSession implements PortedGameSession {
    private static final int BUILD_TICKS = 240 * 20;
    private static final int ATTACK_TICKS = 480 * 20;
    private static final int FLAG_TICKS = 30 * 20;
    private static final int UNLOCK_TICKS = 5 * 20;
    private static final int SUPPLY_COOLDOWN = 45 * 20;
    private static final int SPAWN_PROTECTION = 3 * 20;
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final Match match;
    private final IslandSiegeArena map;
    private final Random random;
    private final Set<UUID> active = new LinkedHashSet<>();
    private final Set<UUID> redTeam = new HashSet<>();
    private final Map<UUID, BlockPos> spawns = new HashMap<>();
    private final List<BlockPos> spawnList = new ArrayList<>();
    private final Set<BlockPos> placedBlocks = new HashSet<>();
    private final Map<BlockPos, Integer> supplyReady = new HashMap<>();
    private final Map<UUID, Integer> respawnAt = new HashMap<>();
    private final Map<UUID, Integer> protectedUntil = new HashMap<>();
    private final Map<UUID, Entity> entities = new HashMap<>();
    private final ServerBossBar timer = new ServerBossBar(Text.literal("海岛攻防 · 建造准备"), BossBar.Color.BLUE, BossBar.Style.PROGRESS);

    private boolean attacking;
    private boolean ended;
    private Boolean redWon;
    private int ticks;
    private int remaining = BUILD_TICKS;
    private int flagProgress;
    private boolean flagDone;
    private boolean guardianDone;
    private boolean skullDone;
    private boolean cageOpen;
    private UUID unlocker;
    private int unlockProgress;
    private UUID skullCarrier;
    private ZombieEntity guardian;

    public IslandSiegeSession(Match match, ArenaTemplate template, int regionIndex, int seed) {
        this.match = match;
        this.map = new IslandSiegeArena(template, regionIndex, seed);
        this.random = new Random(seed * 1125899906842597L + 41);
        List<ServerPlayerEntity> players = match.players();
        int redCount = players.size() / 2;
        for (int i = 0; i < players.size(); i++) {
            UUID uuid = players.get(i).getUuid();
            boolean red = i < redCount;
            this.active.add(uuid);
            if (red) this.redTeam.add(uuid);
            int teamIndex = red ? i : i - redCount;
            BlockPos spawn = (red ? this.map.redSpawn() : this.map.blueSpawn()).add(0, 0, teamIndex * 2 - 4);
            this.spawns.put(uuid, spawn);
            this.spawnList.add(spawn);
        }
    }

    @Override
    public List<BlockPos> spawnPositions() { return List.copyOf(this.spawnList); }

    @Override
    public void stageMap() { this.map.generate(this.match.arenaWorld()); }

    @Override
    public void start() {
        this.ticks = 0;
        this.remaining = BUILD_TICKS;
        for (ServerPlayerEntity player : participants()) {
            resetPlayer(player);
            this.timer.addPlayer(player);
            player.sendMessage(Text.literal(isRed(player) ? "§c你是进攻方：建造期留在红色基地整备，随后完成任意两个目标。"
                    : "§9你是防守方：有 4 分钟搭建防线，保护旗帜、盔甲守卫和金色骷髅！"), false);
        }
        label(this.map.flag().add(0, 7, 0), "§e目标 ① 旗帜：红方占领累计 30 秒");
        label(this.map.guardian().add(0, 4, 0), "§f目标 ② 击败盔甲守卫");
        label(this.map.skull().add(0, 4, 0), "§6目标 ③ 解锁金骷髅并带回红色基地");
        label(this.map.redSpawn().add(0, 4, 0), "§c红方基地 · 金骷髅交付点");
        label(this.map.blueSpawn().add(0, 4, 0), "§9蓝方基地");
        spawnGuardian();
        announce("§b建造阶段 4:00§r：蓝方搭防线，红方等待。右击木桶可领取随机物资，每 45 秒刷新。");
        announce("§7原地图玩法移植 · 本局采用原生重建地图。原地形不可破坏，玩家搭建方块可以拆除。");
        updateBar();
        checkTeams();
    }

    @Override
    public void tick() {
        if (this.ended || this.match.getState() != MatchState.ACTIVE) return;
        this.ticks++;
        for (UUID uuid : List.copyOf(this.active)) {
            ServerPlayerEntity player = this.match.onlinePlayer(uuid);
            if (player == null || !this.match.contains(uuid)) {
                removeParticipant(uuid, player);
                if (this.ended) return;
                continue;
            }
            Integer due = this.respawnAt.get(uuid);
            if (due != null && due <= this.ticks) {
                this.respawnAt.remove(uuid);
                resetPlayer(player);
                player.sendMessage(Text.literal("§a已在队伍基地复活，获得 3 秒保护。"), true);
            }
            if (this.respawnAt.containsKey(uuid)) continue;
            if (player.getWorld() != this.match.arenaWorld() || !this.map.inBounds(player.getBlockPos())) {
                onDeath(player);
                continue;
            }
            if (!this.attacking) {
                boolean crossed = isRed(player)
                        ? player.getX() > this.map.center().getX() - 48
                        : player.getX() < this.map.center().getX() - 28;
                if (crossed) {
                    teleportHome(player);
                    player.sendMessage(Text.literal("§e建造期尚未结束，请留在本方准备区域。"), true);
                }
            }
            if (this.ticks % 20 == 0) {
                player.getHungerManager().setFoodLevel(20);
                // 出生点恢复只在玩家回到自己的基地时生效；同时该区域不能向外输出伤害。
                if (atOwnSpawn(player) && player.getHealth() < player.getMaxHealth()) {
                    player.heal(2.0F);
                }
            }
        }
        if (this.ended) return;
        tickGuardian();
        if (this.attacking) {
            tickFlag();
            tickSkull();
            if (completedObjectives() >= 2) {
                finish(true, "§c进攻方完成了两个目标，红方获胜！");
                return;
            }
        }
        if (--this.remaining <= 0) {
            if (!this.attacking) beginAttack();
            else finish(false, "§9时间到！蓝方成功守住至少两个目标，防守方获胜！");
        }
        if (!this.ended && this.ticks % 20 == 0) {
            updateBar();
            sendHints();
            this.placedBlocks.removeIf(pos -> this.match.arenaWorld().getBlockState(pos).isAir());
        }
        if (this.remaining == 10 * 20 || this.remaining == 60 * 20) {
            announce((this.attacking ? "§c进攻" : "§b建造") + "阶段剩余 " + (this.remaining / 20) + " 秒。");
        }
    }

    private void beginAttack() {
        this.attacking = true;
        this.remaining = ATTACK_TICKS;
        this.timer.setColor(BossBar.Color.RED);
        for (ServerPlayerEntity player : participants()) {
            if (isRed(player)) resetPlayer(player);
        }
        announce("§c进攻开始！8:00 内完成三项中的任意两项：占旗、击败盔甲守卫、将金骷髅带回红方基地。");
        announce("§e黄台占旗累计 30 秒；右击金骷髅铁笼并停留 5 秒解锁。蓝方在目标附近会暂停进度。");
        updateBar();
    }

    private void tickFlag() {
        if (this.flagDone) return;
        boolean red = false;
        boolean blue = false;
        for (ServerPlayerEntity player : participants()) {
            if (!near(player, this.map.flag(), 4.5, 4.0) || this.respawnAt.containsKey(player.getUuid())) continue;
            if (isRed(player)) red = true;
            else blue = true;
        }
        if (red && !blue && ++this.flagProgress >= FLAG_TICKS) {
            this.flagDone = true;
            this.map.markFlagComplete(this.match.arenaWorld());
            announce("§c目标 ① 旗帜已被摧毁！§e（" + completedObjectives() + "/2）");
        }
    }

    private void tickSkull() {
        if (this.skullDone) return;
        if (this.skullCarrier != null) {
            ServerPlayerEntity carrier = this.match.onlinePlayer(this.skullCarrier);
            if (carrier == null || !isActive(carrier) || this.respawnAt.containsKey(this.skullCarrier)) {
                returnSkull();
            } else if (near(carrier, this.map.redSpawn(), 5.0, 4.0)) {
                this.skullDone = true;
                carrier.removeStatusEffect(StatusEffects.GLOWING);
                carrier.removeStatusEffect(StatusEffects.SLOWNESS);
                this.skullCarrier = null;
                announce("§c目标 ③ 金骷髅已送回红方基地！§e（" + completedObjectives() + "/2）");
            } else if (this.ticks % 20 == 0) {
                carrier.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 40, 0, false, false));
                carrier.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 0, false, false));
            }
            return;
        }
        if (this.unlocker == null || this.cageOpen) return;
        ServerPlayerEntity player = this.match.onlinePlayer(this.unlocker);
        if (player == null || !isActive(player) || this.respawnAt.containsKey(this.unlocker)
                || !near(player, this.map.skull(), 4.8, 3.0)) {
            this.unlocker = null;
            this.unlockProgress = 0;
            return;
        }
        boolean contested = participants().stream().anyMatch(p -> !isRed(p) && near(p, this.map.skull(), 5.0, 4.0));
        if (!contested && ++this.unlockProgress >= UNLOCK_TICKS) {
            this.cageOpen = true;
            this.map.openSkullCage(this.match.arenaWorld());
            this.unlocker = null;
            takeSkull(player);
        }
    }

    private void takeSkull(ServerPlayerEntity player) {
        this.skullCarrier = player.getUuid();
        this.map.showSkull(this.match.arenaWorld(), false);
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 40, 0, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 0, false, false));
        announce("§6" + player.getName().getString() + " 拿到了金骷髅！必须带回红色基地；阵亡或退出时骷髅归位。");
    }

    private void returnSkull() {
        if (this.skullCarrier != null) {
            ServerPlayerEntity old = this.match.onlinePlayer(this.skullCarrier);
            if (old != null) {
                old.removeStatusEffect(StatusEffects.GLOWING);
                old.removeStatusEffect(StatusEffects.SLOWNESS);
            }
        }
        this.skullCarrier = null;
        if (!this.skullDone) this.map.showSkull(this.match.arenaWorld(), true);
        announce("§6金骷髅已回到金色平台，红方可再次夺取。");
    }

    private void spawnGuardian() {
        ArenaWorld world = this.match.arenaWorld();
        this.guardian = new SiegeGuardian(world);
        this.guardian.refreshPositionAndAngles(this.map.guardian().getX() + 0.5, this.map.guardian().getY(),
                this.map.guardian().getZ() + 0.5, 90, 0);
        this.guardian.setCustomName(Text.literal("§f盔甲守卫 · 防守目标"));
        this.guardian.setCustomNameVisible(true);
        this.guardian.setPersistent();
        this.guardian.setCanPickUpLoot(false);
        this.guardian.setBaby(false);
        this.guardian.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        this.guardian.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        this.guardian.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        this.guardian.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        this.guardian.equipStack(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        this.guardian.setEquipmentDropChance(EquipmentSlot.MAINHAND, 0);
        for (EquipmentSlot slot : ARMOR) this.guardian.setEquipmentDropChance(slot, 0);
        this.guardian.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(60 + 20 * this.redTeam.size());
        this.guardian.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE).setBaseValue(5);
        this.guardian.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED).setBaseValue(0.27);
        this.guardian.getAttributeInstance(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE).setBaseValue(0.5);
        this.guardian.getAttributeInstance(EntityAttributes.ZOMBIE_SPAWN_REINFORCEMENTS).setBaseValue(0);
        this.guardian.setHealth(this.guardian.getMaxHealth());
        this.guardian.setAiDisabled(true);
        this.entities.put(this.guardian.getUuid(), this.guardian);
        world.spawnEntity(this.guardian);
    }

    private void tickGuardian() {
        if (this.guardianDone || this.guardian == null) return;
        if (this.guardian.isRemoved()) {
            // 非击杀清除不能送给红队一个免费目标；正常死亡在 onMobDeath 中登记。
            this.entities.remove(this.guardian.getUuid());
            spawnGuardian();
        }
        this.guardian.setFireTicks(0);
        this.guardian.setAiDisabled(!this.attacking);
        if (!this.attacking) return;
        if (!near(this.guardian, this.map.guardian(), 12, 8)) {
            this.guardian.refreshPositionAndAngles(this.map.guardian().getX() + 0.5, this.map.guardian().getY(),
                    this.map.guardian().getZ() + 0.5, 90, 0);
            this.guardian.setVelocity(Vec3d.ZERO);
            this.guardian.getNavigation().stop();
        }
        ServerPlayerEntity nearest = null;
        double distance = 144;
        for (ServerPlayerEntity player : participants()) {
            if (!isRed(player) || this.respawnAt.containsKey(player.getUuid()) || spawnProtected(player)) continue;
            if (!near(player, this.map.guardian(), 12, 6)) continue;
            double d = player.squaredDistanceTo(this.guardian);
            if (d < distance) {
                distance = d;
                nearest = player;
            }
        }
        this.guardian.setTarget(nearest);
    }

    private void label(BlockPos pos, String text) {
        ArmorStandEntity label = new ArmorStandEntity(this.match.arenaWorld(), pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        // 1.21.1 的 setMarker 为 private，使用原版实体 NBT 入口设置无碰撞标记。
        NbtCompound marker = new NbtCompound();
        marker.putBoolean("Marker", true);
        label.readCustomDataFromNbt(marker);
        label.setInvisible(true);
        label.setNoGravity(true);
        label.setInvulnerable(true);
        label.setCustomName(Text.literal(text));
        label.setCustomNameVisible(true);
        this.entities.put(label.getUuid(), label);
        this.match.arenaWorld().spawnEntity(label);
    }

    @Override
    public void onMobDeath(LivingEntity entity, DamageSource source) {
        if (!this.ended && this.attacking && entity == this.guardian && !this.guardianDone) {
            this.guardianDone = true;
            announce("§c目标 ② 盔甲守卫已被击败！§e（" + completedObjectives() + "/2）");
        }
    }

    @Override
    public boolean ownsEntity(Entity entity) { return entity != null && this.entities.containsKey(entity.getUuid()); }

    @Override
    public boolean allowsDamage(LivingEntity victim, DamageSource source) {
        if (this.ended || !this.attacking || this.match.getState() != MatchState.ACTIVE) return false;
        Entity attacker = source.getAttacker();
        if (victim == this.guardian) {
            return !this.guardianDone && attacker instanceof ServerPlayerEntity player && isActive(player)
                    && isRed(player) && !spawnProtected(player) && !this.respawnAt.containsKey(player.getUuid());
        }
        if (!(victim instanceof ServerPlayerEntity player) || !isActive(player)
                || this.respawnAt.containsKey(player.getUuid()) || spawnProtected(player)) return false;
        if (attacker instanceof ServerPlayerEntity other) {
            return isActive(other) && isRed(other) != isRed(player)
                    && !spawnProtected(other) && !this.respawnAt.containsKey(other.getUuid());
        }
        if (attacker == this.guardian) return isRed(player) && !this.guardianDone;
        return attacker == null; // 正常摔落等环境伤害；不接受其他场次或野生生物的伤害。
    }

    @Override
    public void onDeath(ServerPlayerEntity player) {
        // 原生重生兜底可能先把玩家放到主世界；成员身份仍有效时下一 tick 送回本场。
        if (!this.active.contains(player.getUuid()) || !this.match.contains(player.getUuid()) || this.ended) return;
        player.setHealth(player.getMaxHealth());
        player.setFireTicks(0);
        player.fallDistance = 0;
        if (this.respawnAt.putIfAbsent(player.getUuid(), this.ticks + 1) != null) return;
        if (player.getUuid().equals(this.skullCarrier)) returnSkull();
        if (player.getUuid().equals(this.unlocker)) {
            this.unlocker = null;
            this.unlockProgress = 0;
        }
        player.clearStatusEffects();
        player.changeGameMode(GameMode.SPECTATOR);
    }

    @Override
    public void onLeave(ServerPlayerEntity player) { removeParticipant(player.getUuid(), player); }

    private void removeParticipant(UUID uuid, ServerPlayerEntity player) {
        if (!this.active.remove(uuid)) return;
        this.respawnAt.remove(uuid);
        this.protectedUntil.remove(uuid);
        if (uuid.equals(this.skullCarrier)) returnSkull();
        if (uuid.equals(this.unlocker)) {
            this.unlocker = null;
            this.unlockProgress = 0;
        }
        if (player != null) this.timer.removePlayer(player);
        checkTeams();
    }

    private void checkTeams() {
        if (this.ended) return;
        long red = this.active.stream().filter(this.redTeam::contains).count();
        long blue = this.active.size() - red;
        if (red == 0 || blue == 0) {
            if (red == 0 && blue == 0) finish(null, "§7双方已退出，本局结束。");
            else finish(red > 0, red > 0 ? "§c防守方全部退出，红方获胜！" : "§9进攻方全部退出，蓝方获胜！");
        }
    }

    private void resetPlayer(ServerPlayerEntity player) {
        player.changeGameMode(GameMode.SURVIVAL);
        player.clearStatusEffects();
        player.setHealth(player.getMaxHealth());
        player.setFireTicks(0);
        player.fallDistance = 0;
        player.setVelocity(Vec3d.ZERO);
        player.getHungerManager().setFoodLevel(20);
        player.getHungerManager().setSaturationLevel(5);
        player.getInventory().clear();
        player.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
        player.getInventory().setStack(1, new ItemStack(Items.BOW));
        player.getInventory().setStack(2, new ItemStack(Items.IRON_PICKAXE));
        player.getInventory().setStack(3, new ItemStack(Items.SHEARS));
        player.getInventory().setStack(4, new ItemStack(isRed(player) ? Items.RED_WOOL : Items.BLUE_WOOL, 64));
        player.getInventory().setStack(5, new ItemStack(Items.COOKED_BEEF, 16));
        player.getInventory().setStack(6, new ItemStack(Items.GOLDEN_APPLE, 1));
        player.getInventory().setStack(9, new ItemStack(Items.ARROW, 24));
        player.equipStack(EquipmentSlot.HEAD, new ItemStack(isRed(player) ? Items.GOLDEN_HELMET : Items.IRON_HELMET));
        player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        player.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.CHAINMAIL_LEGGINGS));
        player.equipStack(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        teleportHome(player);
        this.protectedUntil.put(player.getUuid(), this.ticks + SPAWN_PROTECTION);
        player.currentScreenHandler.sendContentUpdates();
    }

    private void teleportHome(ServerPlayerEntity player) {
        BlockPos spawn = this.spawns.get(player.getUuid());
        if (spawn == null) return;
        player.teleport(this.match.arenaWorld(), spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                isRed(player) ? -90 : 90, 0);
        player.setVelocity(Vec3d.ZERO);
        player.fallDistance = 0;
    }

    @Override
    public boolean canPlace(ServerPlayerEntity player, BlockPos pos) {
        if (!canInteract(player) || !this.map.inBounds(pos) || this.map.nearSpawn(pos)
                || this.map.protectedObjective(pos)) return false;
        if (pos.getY() < this.map.center().getY() - 3 || pos.getY() > this.map.center().getY() + 20) return false;
        // 建造期只能蓝方在本方半场搭防，红方不能提前铺路穿过边界。
        return this.attacking || (!isRed(player) && pos.getX() >= this.map.center().getX() - 28);
    }

    @Override
    public void onPlaced(ServerPlayerEntity player, BlockPos pos) {
        if (canPlace(player, pos)) this.placedBlocks.add(pos.toImmutable());
    }

    @Override
    public boolean canBreak(ServerPlayerEntity player, BlockPos pos) {
        return canInteract(player) && this.placedBlocks.contains(pos)
                && (this.attacking || (!isRed(player) && pos.getX() >= this.map.center().getX() - 28));
    }

    @Override
    public ActionResult useBlock(ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!canInteract(player)) return ActionResult.FAIL;
        BlockPos pos = hit.getBlockPos();
        if (this.map.supplies().contains(pos)) {
            if (hand != Hand.MAIN_HAND) return ActionResult.SUCCESS;
            if (!this.attacking && (isRed(player) ? pos.getX() > this.map.center().getX() - 48
                    : pos.getX() < this.map.center().getX() - 28)) return ActionResult.FAIL;
            giveSupply(player, pos);
            return ActionResult.SUCCESS;
        }
        if (pos.equals(this.map.skull()) || this.map.skullCage().contains(pos)) {
            if (!this.attacking || !isRed(player) || this.skullDone || this.skullCarrier != null) {
                player.sendMessage(Text.literal("§6红方在进攻阶段可夺取金骷髅并带回基地。"), true);
                return ActionResult.SUCCESS;
            }
            if (!near(player, this.map.skull(), 4.8, 3.0)) return ActionResult.FAIL;
            if (this.cageOpen) takeSkull(player);
            else if (this.unlocker == null) {
                this.unlocker = player.getUuid();
                this.unlockProgress = 0;
                player.sendMessage(Text.literal("§e正在解锁：留在铁笼旁 5 秒；有蓝方靠近时进度暂停。"), false);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }

    private void giveSupply(ServerPlayerEntity player, BlockPos pos) {
        int ready = this.supplyReady.getOrDefault(pos, 0);
        if (ready > this.ticks) {
            player.sendMessage(Text.literal("§7物资还有 " + ((ready - this.ticks + 19) / 20) + " 秒刷新。"), true);
            return;
        }
        this.supplyReady.put(pos.toImmutable(), this.ticks + SUPPLY_COOLDOWN);
        give(player, new ItemStack(isRed(player) ? Items.RED_WOOL : Items.BLUE_WOOL, 32));
        for (int i = 0; i < 2; i++) {
            ItemStack loot = switch (this.random.nextInt(10)) {
                case 0, 1 -> new ItemStack(Items.ARROW, 16);
                case 2 -> new ItemStack(Items.GOLDEN_APPLE, 2);
                case 3 -> new ItemStack(Items.ARROW, 24);
                case 4 -> new ItemStack(Items.COBBLESTONE, 32);
                case 5 -> new ItemStack(Items.DIAMOND_BOOTS);
                case 6 -> new ItemStack(Items.IRON_LEGGINGS);
                case 7 -> new ItemStack(Items.DIAMOND_PICKAXE);
                case 8 -> new ItemStack(Items.DIAMOND_SWORD);
                default -> new ItemStack(Items.COOKED_BEEF, 16);
            };
            give(player, loot);
        }
        player.sendMessage(Text.literal("§a已获得随机物资和 32 个队伍方块。"), true);
    }

    private void give(ServerPlayerEntity player, ItemStack stack) {
        // 不在库存满时丢到共享世界，避免跨场捡拾和反复开箱产生残留实体。
        if (!player.getInventory().insertStack(stack)) {
            player.sendMessage(Text.literal("§e背包已满，剩余 " + stack.getName().getString() + " 无法领取。"), false);
        }
        player.currentScreenHandler.sendContentUpdates();
    }

    private boolean canInteract(ServerPlayerEntity player) {
        return !this.ended && this.match.getState() == MatchState.ACTIVE && isActive(player)
                && !this.respawnAt.containsKey(player.getUuid()) && !player.isSpectator();
    }

    private boolean isActive(ServerPlayerEntity player) {
        return player != null && this.active.contains(player.getUuid()) && this.match.contains(player.getUuid())
                && player.getWorld() == this.match.arenaWorld();
    }

    private boolean isRed(ServerPlayerEntity player) { return this.redTeam.contains(player.getUuid()); }

    private boolean atOwnSpawn(ServerPlayerEntity player) {
        return near(player, isRed(player) ? this.map.redSpawn() : this.map.blueSpawn(), 5, 5);
    }

    private boolean spawnProtected(ServerPlayerEntity player) {
        return this.protectedUntil.getOrDefault(player.getUuid(), 0) > this.ticks || atOwnSpawn(player);
    }

    private static boolean near(Entity player, BlockPos pos, double radius, double vertical) {
        return IslandSiegeArena.horizontalDistanceSquared(player.getBlockPos(), pos) <= radius * radius
                && Math.abs(player.getY() - pos.getY()) <= vertical;
    }

    private List<ServerPlayerEntity> participants() {
        return this.match.onlineParticipants().stream().filter(this::isActive).toList();
    }

    private int completedObjectives() {
        return (this.flagDone ? 1 : 0) + (this.guardianDone ? 1 : 0) + (this.skullDone ? 1 : 0);
    }

    private void updateBar() {
        this.timer.setName(Text.literal((this.attacking ? "§c进攻 " : "§b蓝方建造 / 红方整备 ")
                + time(this.remaining) + " §f| 目标 " + completedObjectives() + "/2"));
        this.timer.setPercent(Math.max(0, Math.min(1, (float) this.remaining / (this.attacking ? ATTACK_TICKS : BUILD_TICKS))));
    }

    private void sendHints() {
        for (ServerPlayerEntity player : participants()) {
            String hint;
            if (player.getUuid().equals(this.skullCarrier)) {
                hint = "§6携带金骷髅：返回西侧红方基地！ 距离 "
                        + (int) Math.sqrt(IslandSiegeArena.horizontalDistanceSquared(player.getBlockPos(), this.map.redSpawn())) + " 格";
            } else if (player.getUuid().equals(this.unlocker)) {
                hint = "§6金骷髅解锁 " + (this.unlockProgress * 100 / UNLOCK_TICKS) + "% §7（守方靠近会暂停）";
            } else if (this.attacking) {
                hint = "§e旗帜 " + (this.flagDone ? "✓" : this.flagProgress * 100 / FLAG_TICKS + "%")
                        + " §f| 守卫 " + (this.guardianDone ? "✓" : this.guardian == null ? "准备中" : (int) this.guardian.getHealth() + " HP")
                        + " §6| 骷髅 " + (this.skullDone ? "✓" : this.skullCarrier != null ? "运送中" : this.cageOpen ? "可夺取" : "待解锁");
            } else {
                hint = isRed(player) ? "§c在红方岛整备，" + time(this.remaining) + " 后进攻 · 右击木桶领取物资"
                        : "§9搭建防线保护三目标 · 原地形保护，己方搭建可拆 · " + time(this.remaining);
            }
            player.sendMessage(Text.literal(hint), true);
        }
    }

    @Override
    public List<String> scoreboardLines() {
        return List.of("§f阶段 §e" + (this.attacking ? "进攻" : "建造准备"), "§f时间 §e" + time(this.remaining),
                "§f已完成 §c" + completedObjectives() + "/2", "§e旗帜 " + (this.flagDone ? "已摧毁" : this.flagProgress * 100 / FLAG_TICKS + "%"),
                "§f守卫 " + (this.guardianDone ? "已击败" : "存活"), "§6金骷髅 " + (this.skullDone ? "已夺取" : this.skullCarrier != null ? "运送中" : "守护中"));
    }

    private static String time(int ticks) {
        int seconds = Math.max(0, (ticks + 19) / 20);
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }

    private void finish(Boolean redWins, String message) {
        if (this.ended) return;
        this.redWon = redWins;
        this.ended = true;
        announce(message);
        this.match.finishMatch(this.match.firstTeam());
    }

    private void announce(String message) {
        Text text = Text.literal("§6[海岛攻防] §r" + message);
        for (ServerPlayerEntity player : participants()) player.sendMessage(text, false);
    }

    @Override
    public Set<UUID> winners() {
        if (this.redWon == null) return Set.of();
        Set<UUID> result = new HashSet<>();
        for (UUID uuid : this.active) {
            if (this.match.contains(uuid) && this.redTeam.contains(uuid) == this.redWon) result.add(uuid);
        }
        return result;
    }

    @Override
    public void onEnd() {
        this.ended = true;
        this.timer.clearPlayers();
        this.timer.setVisible(false);
        this.respawnAt.clear();
        for (Entity entity : this.entities.values()) entity.discard();
        this.entities.clear();
        for (ServerPlayerEntity player : participants()) {
            player.removeStatusEffect(StatusEffects.GLOWING);
            player.removeStatusEffect(StatusEffects.SLOWNESS);
        }
    }

    @Override
    public void clearArena() {
        onEnd();
        ArenaWorld world = this.match.arenaWorld();
        if (world == null) return;
        for (BlockPos pos : this.placedBlocks) world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
        this.placedBlocks.clear();
        this.map.clear(world);
    }

    /** 守卫是玩法实体：不烧头盔、不增援、不产出无归属掉落，也不因全服切和平而消失。 */
    private static final class SiegeGuardian extends ZombieEntity {
        private SiegeGuardian(ServerWorld world) { super(world); }
        @Override protected boolean burnsInDaylight() { return false; }
        @Override protected boolean isDisallowedInPeaceful() { return false; }
        @Override protected int getXpToDrop() { return 0; }
        @Override protected void dropLoot(DamageSource source, boolean causedByPlayer) { }
        @Override protected void dropEquipment(ServerWorld world, DamageSource source, boolean causedByPlayer) { }
    }
}
