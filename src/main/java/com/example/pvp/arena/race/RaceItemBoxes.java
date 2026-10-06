package com.example.pvp.arena.race;

import com.example.pvp.arena.ArenaWorld;
import com.example.pvp.mixin.DisplayEntityInvoker;
import com.mojang.logging.LogUtils;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 赛道道具箱：每个 Checkpoint 门后一横排，船开过去吃掉 → 给一个随机道具 → 若干秒后在原地重生。
 *
 * <p><b>为什么用展示实体（ItemDisplayEntity）而不是掉落物</b>：掉落物会被物理推动、在冰上滑走、
 * 被原版拾取逻辑抢走、被合并、超时消失；展示实体没有碰撞也没有物理，正好当"箱子"的视觉，
 * 拾取判定完全由本类按<b>扫掠线段</b>做。
 *
 * <p><b>为什么必须扫掠</b>：船在冰上的极速约 2.0 格/tick、氮气时 3.64 格/tick，而"点在一个半径内"
 * 这种逐 tick 判定在两次采样之间会整段跳过箱子（同一个坑在 Checkpoint 判定上已经踩过，
 * 见 {@code RaceProgressTracker} 的扫掠穿越）。这里用"上一 tick 位置 → 本 tick 位置"这条线段
 * 与箱子做距离判定，速度再快也不会漏。
 *
 * <p>位置全部由 Checkpoint 数据推导（{@code gate + 弧长偏移} 后再投影回中心线），
 * 所以不可能落进护栏、缓冲带或门架里；同 Seed 同配置的布局完全可复现，便于自检与清场。
 */
final class RaceItemBoxes {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 箱子图标（纯展示，没有实际物品语义）。 */
    private static final Item BOX_ICON = Items.CHEST;
    /** 箱子悬浮高度：冰面之上这么高（船的碰撞箱不到 1 格，不会和船重叠）。 */
    private static final double BOX_LIFT = 1.15;
    /**
     * 单 tick 位移超过这么多格就当成传送/回位，不做拾取判定。
     *
     * <p>正常最快约 3.64 格/tick；回位与 {@code /tp} 是几十上百格。不排除掉的话，
     * 一次回位传送就会"顺路"扫过一串箱子白送道具。
     */
    private static final double MAX_PICKUP_MOVE = 8.0;

    /** 一个箱子位。字段同包可见，方便 {@link BoatRaceSession} 直接标记/回收。 */
    static final class Slot {
        /** 所属 Checkpoint 门号（0 = 起终点线，正常不会出现）。 */
        final int gate;
        final double x;
        final double z;
        /** 箱子所在的冰面高度（箱子实体浮在它上面 {@link #BOX_LIFT} 格）。 */
        final int surfaceY;
        /** 重生时刻（服务器 tick）；0 表示此刻在场。 */
        long respawnAt;
        /**
         * 在场时的展示实体；null = 不在场。
         *
         * <p>这里刻意存<b>实体引用</b>而不是 UUID：{@code arena.getEntity(uuid)} 在实体刚生成时
         * 有偶发查不到的时刻（"空船残留"那个坑就是这么来的），一旦查不到就会漏掉 discard，
         * 赛道上就会永远留着一个吃不到的箱子。直接拿引用就完全绕开了查找表。
         */
        DisplayEntity.ItemDisplayEntity entity;

        Slot(int gate, double x, double z, int surfaceY) {
            this.gate = gate;
            this.x = x;
            this.z = z;
            this.surfaceY = surfaceY;
        }

        boolean live() {
            return this.entity != null;
        }
    }

    private final RaceTrack track;
    private final List<Slot> slots;
    /** 玩家 → 上一 tick 的位置（x/y/z），扫掠判定用。 */
    private final Map<UUID, double[]> lastPos = new HashMap<>();
    /** 玩家 → 拾取冷却结束 tick（避免一 tick 扫过一整排箱子全部吃掉）。 */
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();

    RaceItemBoxes(RaceTrack track, double gateOffset, double laneOffset, int lanes) {
        this.track = track;
        this.slots = buildSlots(track, gateOffset, laneOffset, lanes);
    }

    // ==================== 布局 ====================

    /** 箱子位来自 {@link RaceBoxLayout}（纯几何，可离线校验）；这里只负责把它变成场上实例。 */
    private static List<Slot> buildSlots(RaceTrack track, double gateOffset, double laneOffset, int lanes) {
        List<RaceBoxLayout.Box> boxes = RaceBoxLayout.compute(track, gateOffset, laneOffset, lanes);
        List<Slot> slots = new ArrayList<>(boxes.size());
        for (RaceBoxLayout.Box box : boxes) {
            slots.add(new Slot(box.gate(), box.x(), box.z(), track.surfaceY()));
        }
        return slots;
    }

    // ==================== 生成 / 重生 / 回收 ====================

    int size() {
        return this.slots.size();
    }

    List<Slot> slots() {
        return this.slots;
    }

    int liveCount() {
        int count = 0;
        for (Slot slot : this.slots) {
            if (slot.live()) {
                count++;
            }
        }
        return count;
    }

    /** 开局把所有箱子放出来（GO 那一 tick 调用）。 */
    int spawnAll(ArenaWorld arena) {
        int spawned = 0;
        for (Slot slot : this.slots) {
            if (slot.live()) {
                continue;
            }
            if (this.spawn(arena, slot)) {
                spawned++;
            }
        }
        return spawned;
    }

    /** 每 tick：把到点的箱子重新放出来。 */
    int tickRespawn(ArenaWorld arena, long nowTick) {
        int spawned = 0;
        for (Slot slot : this.slots) {
            if (slot.live() || slot.respawnAt == 0 || nowTick < slot.respawnAt) {
                continue;
            }
            slot.respawnAt = 0;
            if (this.spawn(arena, slot)) {
                spawned++;
            }
        }
        return spawned;
    }

    private boolean spawn(ArenaWorld arena, Slot slot) {
        try {
            DisplayEntity.ItemDisplayEntity display =
                    new DisplayEntity.ItemDisplayEntity(EntityType.ITEM_DISPLAY, arena);
            display.refreshPositionAndAngles(slot.x, slot.surfaceY + BOX_LIFT, slot.z, 0.0F, 0.0F);
            display.setItemStack(new ItemStack(BOX_ICON));
            display.setNoGravity(true);
            // 展示实体的配置 setter 在 1.21.1 全是 private，必须走项目已有的 @Invoker
            ((DisplayEntityInvoker) display).pvp$setBillboardMode(DisplayEntity.BillboardMode.CENTER);
            if (!arena.spawnEntity(display)) {
                LOGGER.warn("[PvP] 亦可赛艇：道具箱实体生成失败（{}, {}）", slot.x, slot.z);
                return false;
            }
            slot.entity = display;
            return true;
        } catch (Exception e) {
            LOGGER.warn("[PvP] 亦可赛艇：道具箱实体生成异常", e);
            return false;
        }
    }

    /** 吃掉一个箱子：标记重生时间并立刻移除视觉实体（拾取冷却在 {@link #pickup} 里按玩家记）。 */
    void consume(Slot slot, long nowTick, long respawnTicks) {
        slot.respawnAt = nowTick + Math.max(1L, respawnTicks);
        this.discardSlot(slot);
    }

    private void discardSlot(Slot slot) {
        if (slot.entity == null) {
            return;
        }
        if (!slot.entity.isRemoved()) {
            slot.entity.discard();
        }
        slot.entity = null;
    }

    /** 精确回收本场所有箱子实体（只动自己记录过的那批，绝不按区域乱扫，避免误删下一局的箱子）。 */
    int discardAll() {
        int removed = 0;
        for (Slot slot : this.slots) {
            if (slot.live()) {
                removed++;
            }
            this.discardSlot(slot);
        }
        this.lastPos.clear();
        this.cooldownUntil.clear();
        return removed;
    }

    // ==================== 拾取（扫掠） ====================

    /**
     * 用"上一 tick 位置 → 本 tick 位置"这条线段找被吃掉的箱子（只取最近的一个）。
     *
     * @param forward       本 tick 是否沿赛道正向行进（逆行/倒车不算，天然反刷）
     * @param radius        拾取半径（格）
     * @param dy            允许的高度差（格）
     * @param cooldownTicks 连吃两个箱子之间的最小间隔
     * @return 命中的箱子位；没有则 null
     */
    Slot pickup(ServerPlayerEntity player, long nowTick, boolean forward,
                double radius, double dy, long cooldownTicks) {
        UUID uuid = player.getUuid();
        double[] prev = this.lastPos.put(uuid,
                new double[]{player.getX(), player.getY(), player.getZ()});
        if (prev == null || !forward) {
            return null;
        }
        if (this.cooldownUntil.getOrDefault(uuid, 0L) > nowTick) {
            return null;
        }
        double moved = Math.hypot(player.getX() - prev[0], player.getZ() - prev[2]);
        if (moved > MAX_PICKUP_MOVE) {
            return null;
        }
        Slot best = null;
        double bestDistance = Double.MAX_VALUE;
        double eyeY = this.track.surfaceY() + 1.0;
        for (Slot slot : this.slots) {
            if (!slot.live()) {
                continue;
            }
            if (Math.abs(player.getY() - eyeY) > dy) {
                continue;
            }
            double distance = RaceTrack.distanceToSegment(slot.x, slot.z,
                    prev[0], prev[2], player.getX(), player.getZ());
            if (distance <= radius && distance < bestDistance) {
                best = slot;
                bestDistance = distance;
            }
        }
        if (best != null) {
            this.cooldownUntil.put(uuid, nowTick + Math.max(0L, cooldownTicks));
        }
        return best;
    }

    /** 玩家离开/回位时清掉他的扫掠历史，避免拿"传送前的位置"做线段。 */
    void forget(UUID uuid) {
        this.lastPos.remove(uuid);
        this.cooldownUntil.remove(uuid);
    }
}
