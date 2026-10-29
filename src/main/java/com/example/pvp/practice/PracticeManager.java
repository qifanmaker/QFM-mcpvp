package com.example.pvp.practice;

import com.example.pvp.PvPMod;
import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.kit.Kit;
import com.example.pvp.kit.KitManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import com.example.pvp.text.Messages;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 练习管理器：为每位玩家分配一个独占的竞技场区域并驱动其练习会话。
 *
 * <p>区域索引从 {@link #REGION_BASE} 起，远离比赛用的 0..(maxConcurrentMatches*2-1)，
 * 因此练习场地永远不会和正式比赛抢位置。
 */
public final class PracticeManager {
    /**
     * 练习区域起始索引。区域 X 坐标 = 索引 × {@code ArenaTemplate.REGION_SPACING}(384)。
     * 比赛区域实际只用到 0..7（maxConcurrentMatches 默认 4），这里从 100 起足够安全，
     * 同时 X 只有 38400 —— 虚空中生成瞬时完成，不会因为坐标过远而卡住传送。
     */
    private static final int REGION_BASE = 100;
    private static final int MAX_REGIONS = 32;

    /** 退出练习的物品标记（放在快捷栏第 9 格，右键退出）。 */
    public static final String EXIT_TAG = "pvp.practice.exit";
    static final String PROJECTILE_ROUND_PREFIX = "pvp_practice_round_";

    private static PracticeManager instance;

    private final Map<UUID, PracticeSession> sessions = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> allocatedRegions = new ConcurrentHashMap<>();

    private PracticeManager() {
    }

    public static PracticeManager get() {
        if (instance == null) {
            instance = new PracticeManager();
        }
        return instance;
    }

    // ---------- 退出物品 ----------

    public static ItemStack createExitItem() {
        ItemStack stack = new ItemStack(Items.BARRIER);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§c退出练习 §7(右键)"));
        NbtCompound nbt = new NbtCompound();
        nbt.putString(EXIT_TAG, "1");
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        return stack;
    }

    public static boolean isExitItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        NbtComponent nbt = stack.get(DataComponentTypes.CUSTOM_DATA);
        return nbt != null && nbt.copyNbt().contains(EXIT_TAG);
    }

    // ---------- 会话生命周期 ----------

    /**
     * 开始一个练习会话。
     *
     * @return 成功进入返回 true；玩家已在练习中或正在比赛返回 false
     */
    public boolean startPractice(ServerPlayerEntity player, PracticeType type) {
        if (type == PracticeType.PVP) return startPvpPractice(player, BotDifficulty.NORMAL, KitManager.get("sword"));
        return startPractice(player, type, null, null);
    }

    /** 只提供已实现武器/补给行为的 PvP 套件，防止把相扑或自定义玩法误当普通决斗。 */
    public static List<Kit> getPvpKits() {
        return KitManager.getKits().stream().filter(PracticeManager::isPvpKit).toList();
    }

    public static boolean isPvpKit(Kit kit) {
        return kit != null && Set.of("sword", "bow", "full_gear", "iron_pvp", "axe", "no_debuff", "gapple",
                KitManager.LEGACY_18_KIT_ID).contains(kit.getId());
    }

    public boolean startPvpPractice(ServerPlayerEntity player, BotDifficulty difficulty, Kit kit) {
        if (difficulty == null || !isPvpKit(kit)) {
            player.sendMessage(Messages.error("请选择有效难度与人机支持的 PvP 套件"), false);
            return false;
        }
        return startPractice(player, PracticeType.PVP, difficulty, kit);
    }

    private boolean startPractice(ServerPlayerEntity player, PracticeType type, BotDifficulty difficulty, Kit kit) {
        var myGo = com.example.pvp.mygo.practice.MyGoPracticeManager.getOrNull();
        if (myGo != null && myGo.contains(player)) {
            player.sendMessage(Messages.error("请先退出 MyGO 人机练习"), false);
            return false;
        }
        ArenaWorldManager arenaManager = ArenaWorldManager.getOrNull();
        if (type == null || arenaManager == null || arenaManager.getWorld() == null || PvPMod.MATCH == null) {
            player.sendMessage(Messages.error("竞技场未就绪，无法进入练习"), false);
            return false;
        }
        if (sessions.containsKey(player.getUuid())) {
            player.sendMessage(Messages.warn("你已经在练习模式中了"), false);
            return false;
        }
        if (PvPMod.MATCH != null && PvPMod.MATCH.isInMatch(player.getUuid())) {
            player.sendMessage(Messages.error("比赛进行中无法进入练习"), false);
            return false;
        }
        if (PvPMod.QUEUE != null && PvPMod.QUEUE.contains(player.getUuid())) {
            player.sendMessage(Messages.error("请先离开匹配队列"), false);
            return false;
        }

        int region = allocateRegion();
        if (region < 0) {
            player.sendMessage(Messages.error("练习场地已满，请稍后再试"), false);
            return false;
        }

        PracticeSession session;
        try {
            session = new PracticeSession(player.getUuid(), type, region, difficulty, kit);
        } catch (RuntimeException error) {
            releaseRegion(region);
            PvPMod.LOGGER.error("[PvP] 练习初始化失败", error);
            player.sendMessage(Messages.error("练习初始化失败，请稍后重试"), false);
            return false;
        }
        sessions.put(player.getUuid(), session);

        // 生成/入场失败也必须回收区域与机器人，否则一次异常会永久占用练习名额。
        try {
            session.buildArena();
            session.start(player);
            if (PvPMod.DUEL != null) PvPMod.DUEL.removeChallengesInvolving(player.getUuid());
            return true;
        } catch (RuntimeException error) {
            sessions.remove(player.getUuid());
            try {
                session.exit(player);
            } finally {
                releaseRegion(region);
            }
            PvPMod.LOGGER.error("[PvP] 练习入场失败", error);
            player.sendMessage(Messages.error("练习入场失败，已回收场地，请稍后重试"), false);
            return false;
        }
    }

    /** 退出练习。玩家可能已离线（断线清理），此时只回收场地。 */
    public boolean exitPractice(ServerPlayerEntity player) {
        PracticeSession session = sessions.remove(player.getUuid());
        if (session == null) {
            return false;
        }
        session.exit(player);
        return true;
    }

    /** 按 UUID 退出（断线清理用，此时玩家对象已不可用于传送）。 */
    public boolean exitPracticeByUuid(UUID uuid) {
        PracticeSession session = sessions.remove(uuid);
        if (session == null) {
            return false;
        }
        session.exit(null);
        return true;
    }

    public PracticeSession getSession(ServerPlayerEntity player) {
        return player == null ? null : sessions.get(player.getUuid());
    }

    public PracticeSession getSession(UUID uuid) {
        return sessions.get(uuid);
    }

    /** 机器人不在在线玩家列表，伤害/死亡事件仍需找到它所属的唯一练习会话。 */
    public PracticeSession getCombatSession(UUID uuid) {
        PracticeSession owned = sessions.get(uuid);
        if (owned != null) return owned;
        for (PracticeSession session : sessions.values()) {
            if (session.getPvpDuel() != null && session.getPvpDuel().contains(uuid)) return session;
        }
        return null;
    }

    /** 倒计时/结算禁伤；所有练习参战者只能与本场对手互相造成伤害（含箭等投掷物）。 */
    public boolean allowsDamage(LivingEntity victim, DamageSource source) {
        PracticeSession defending = getCombatSession(victim.getUuid());
        PracticeSession attacking = source.getAttacker() == null ? null : getCombatSession(source.getAttacker().getUuid());
        if (defending == null && attacking == null) return true;
        if (defending == null) return false;
        if (defending.getPvpDuel() == null) return source.getAttacker() == null;
        if (!defending.getPvpDuel().isActive()) return false;
        return source.getAttacker() == null || attacking == defending;
    }

    /** 鱼竿/雪球等额外击退不一定经过伤害事件，必须使用同一会话与阶段限制。 */
    public boolean allowsCombatInteraction(Entity attacker, Entity victim) {
        PracticeSession attacking = getCombatSession(attacker.getUuid());
        PracticeSession defending = getCombatSession(victim.getUuid());
        if (attacking == null && defending == null) return true;
        return attacking != null && attacking == defending && attacking.getPvpDuel() != null
                && attacking.getPvpDuel().isActive();
    }

    /**
     * 飞出加载范围的箭不在清场实体索引中；给投掷物保存回合标记，重新加载时丢弃旧箭。
     * 机器人 UUID 跨回合稳定，仅按投掷者 UUID 判断会把旧箭误认为本轮攻击。
     */
    public static void handleProjectileLoad(Entity entity) {
        if (!(entity instanceof net.minecraft.entity.projectile.ProjectileEntity projectile)) return;
        String savedRound = entity.getCommandTags().stream()
                .filter(tag -> tag.startsWith(PROJECTILE_ROUND_PREFIX)).findFirst().orElse(null);
        Entity owner = projectile.getOwner();
        PracticeSession session = PvPMod.PRACTICE == null || owner == null ? null
                : PvPMod.PRACTICE.getCombatSession(owner.getUuid());
        PvpPracticeDuel duel = session == null ? null : session.getPvpDuel();
        if (savedRound != null) {
            if (duel == null || !savedRound.equals(duel.projectileRoundTag())) entity.discard();
        } else if (duel != null) {
            entity.addCommandTag(duel.projectileRoundTag());
        }
    }

    public boolean onDeath(ServerPlayerEntity victim) {
        PracticeSession session = getCombatSession(victim.getUuid());
        if (session == null) return false;
        if (session.getPvpDuel() != null) session.getPvpDuel().onDeath(victim);
        victim.setHealth(victim.getMaxHealth());
        victim.setFireTicks(0);
        victim.fallDistance = 0;
        return true;
    }

    public void shutdown() {
        for (UUID uuid : List.copyOf(sessions.keySet())) {
            ServerPlayerEntity player = PvPMod.SERVER.getPlayerManager().getPlayer(uuid);
            if (player == null) exitPracticeByUuid(uuid);
            else exitPractice(player);
        }
    }

    public boolean isInPractice(ServerPlayerEntity player) {
        return player != null && sessions.containsKey(player.getUuid());
    }

    public boolean isInPractice(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    /** 每 tick 驱动所有会话。 */
    public void tick() {
        if (sessions.isEmpty()) {
            return;
        }
        MinecraftServer server = PvPMod.SERVER;
        if (server == null) {
            return;
        }
        long ticks = server.getTicks();
        for (PracticeSession session : sessions.values()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(session.getUuid());
            // /tp、跨维度命令等第三方离场也要清场，不让后台 AI 继续攻击或占着区域。
            if (player == null || player.getWorld() != ArenaWorldManager.getOrNull().getWorld()) {
                if (player == null) exitPracticeByUuid(session.getUuid());
                else exitPractice(player);
                continue;
            }
            session.tick();
            session.tickTimerHud(ticks);
        }
    }

    // ---------- 区域分配 ----------

    private int allocateRegion() {
        for (int i = 0; i < MAX_REGIONS; i++) {
            int region = REGION_BASE + i;
            if (allocatedRegions.putIfAbsent(region, Boolean.TRUE) == null) {
                return region;
            }
        }
        return -1;
    }

    void releaseRegion(int region) {
        allocatedRegions.remove(region);
    }
}
