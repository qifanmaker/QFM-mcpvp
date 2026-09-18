package com.example.pvp.practice;

import com.example.pvp.PvPMod;
import com.example.pvp.match.MatchState;
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

        PracticeSession session = new PracticeSession(player.getUuid(), type, region);
        sessions.put(player.getUuid(), session);

        // 先建场地再登记，最后才传入场 —— 顺序反了会在传送后才发现没有落脚点
        session.buildArena();
        session.start(player);
        return true;
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
