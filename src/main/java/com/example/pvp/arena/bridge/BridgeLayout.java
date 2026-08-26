package com.example.pvp.arena.bridge;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.config.PvPConfig;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 战桥地图确定性布局：双基地（1v1/2v2/混战）沿 X 轴相望，或四方十字（1v1v1v1）。
 * 纯几何计算（不碰世界），同时给出地图保护方块集合——被 {@link PvPMod} 的方块破坏拦截器使用，
 * 玩家不能拆地图本体，只能拆自己放置的方块。
 */
public final class BridgeLayout {

    /** 一座基地：队伍索引、中心、球门洞边界（double 判定）、出生笼位、地板/边圈/外沿墙方块坐标。 */
    public record BridgeBase(
            int teamIndex,
            BlockPos center,
            double goalMinX, double goalMaxX, double goalMinZ, double goalMaxZ,
            BlockPos spawn,
            List<BlockPos> floor, List<BlockPos> border, List<BlockPos> wall) {

        /** 玩家 (x,z) 是否落在本基地的 2×2 球门洞里。 */
        public boolean goalContains(double x, double z) {
            return x >= goalMinX && x < goalMaxX && z >= goalMinZ && z < goalMaxZ;
        }
    }

    private final BlockPos mapCenter;
    private final boolean fourTeam;
    private final int baseRadius;
    private final int baseWidth;
    private final int gap;
    private final int maxRadius;
    private final List<BridgeBase> bases;
    private final List<BlockPos> bridgeBlocks;
    private final List<BlockPos> hubBlocks;
    private final Set<BlockPos> protectedBlocks;

    private BridgeLayout(BlockPos mapCenter, boolean fourTeam, int baseRadius, int baseWidth, int gap,
                         int maxRadius, List<BridgeBase> bases, List<BlockPos> bridgeBlocks,
                         List<BlockPos> hubBlocks, Set<BlockPos> protectedBlocks) {
        this.mapCenter = mapCenter;
        this.fourTeam = fourTeam;
        this.baseRadius = baseRadius;
        this.baseWidth = baseWidth;
        this.gap = gap;
        this.maxRadius = maxRadius;
        this.bases = bases;
        this.bridgeBlocks = bridgeBlocks;
        this.hubBlocks = hubBlocks;
        this.protectedBlocks = protectedBlocks;
    }

    /** 根据配置计算一整张战桥地图（双队或四方），地图完全确定性、公平对称。 */
    public static BridgeLayout compute(BlockPos mapCenter, int playerCount, boolean fourTeam) {
        PvPConfig cfg = PvPConfig.INSTANCE;
        int baseRadius = cfg.bridgeBaseRadius;
        int baseWidth = 2 * baseRadius + 1;
        int gap = cfg.bridgeGap;
        int maxRadius = gap / 2 + baseWidth + 4;
        int cx = mapCenter.getX();
        int cz = mapCenter.getZ();

        List<BridgeBase> bases = new ArrayList<>();
        List<BlockPos> bridgeBlocks = new ArrayList<>();
        List<BlockPos> hubBlocks = new ArrayList<>();

        if (fourTeam) {
            // 基地中心到地图中心的距离：桥（hub 外沿 1 格） + gap + 基地半宽
            int dist = gap / 2 + baseRadius + 1;
            // 中央 5×5 枢纽平台
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    hubBlocks.add(new BlockPos(cx + dx, ArenaTemplate.PLATFORM_Y, cz + dz));
                }
            }
            // 四条 1 格宽桥：枢纽外沿连到各基地内沿（N/E/S/W）
            for (int z = cz - 3; z >= cz - dist + baseRadius; z--) {
                bridgeBlocks.add(new BlockPos(cx, ArenaTemplate.PLATFORM_Y, z));
            }
            for (int z = cz + 3; z <= cz + dist - baseRadius; z++) {
                bridgeBlocks.add(new BlockPos(cx, ArenaTemplate.PLATFORM_Y, z));
            }
            for (int x = cx - 3; x >= cx - dist + baseRadius; x--) {
                bridgeBlocks.add(new BlockPos(x, ArenaTemplate.PLATFORM_Y, cz));
            }
            for (int x = cx + 3; x <= cx + dist - baseRadius; x++) {
                bridgeBlocks.add(new BlockPos(x, ArenaTemplate.PLATFORM_Y, cz));
            }
            // 四座基地：N(红) E(蓝) S(绿) W(黄)，各自朝向中心
            bases.add(buildBase(0, new BlockPos(cx, ArenaTemplate.PLATFORM_Y + 1, cz - dist), 0, 1, baseRadius));
            bases.add(buildBase(1, new BlockPos(cx + dist, ArenaTemplate.PLATFORM_Y + 1, cz), -1, 0, baseRadius));
            bases.add(buildBase(2, new BlockPos(cx, ArenaTemplate.PLATFORM_Y + 1, cz + dist), 0, -1, baseRadius));
            bases.add(buildBase(3, new BlockPos(cx - dist, ArenaTemplate.PLATFORM_Y + 1, cz), 1, 0, baseRadius));
        } else {
            // 双基地沿 X 轴，中央 1 格宽桥横跨两基地内沿
            int dx = gap / 2 + baseRadius;
            for (int x = cx - gap / 2; x <= cx + gap / 2; x++) {
                bridgeBlocks.add(new BlockPos(x, ArenaTemplate.PLATFORM_Y, cz));
            }
            bases.add(buildBase(0, new BlockPos(cx - dx, ArenaTemplate.PLATFORM_Y + 1, cz), 1, 0, baseRadius));
            bases.add(buildBase(1, new BlockPos(cx + dx, ArenaTemplate.PLATFORM_Y + 1, cz), -1, 0, baseRadius));
        }

        // 全部地图方块 = 受保护（玩家不可破坏）集合
        Set<BlockPos> protectedBlocks = new HashSet<>(bridgeBlocks);
        protectedBlocks.addAll(hubBlocks);
        for (BridgeBase base : bases) {
            protectedBlocks.addAll(base.floor());
            protectedBlocks.addAll(base.border());
            protectedBlocks.addAll(base.wall());
        }

        return new BridgeLayout(mapCenter, fourTeam, baseRadius, baseWidth, gap, maxRadius,
                List.copyOf(bases), List.copyOf(bridgeBlocks), List.copyOf(hubBlocks),
                Set.copyOf(protectedBlocks));
    }

    /** 铺一座基地：地板（跳过中央 2×2 球门洞）、外沿一圈队伍色边圈、外侧 2 格高墙。 */
    private static BridgeBase buildBase(int teamIndex, BlockPos center, int dirX, int dirZ, int baseRadius) {
        int cx = center.getX();
        int cz = center.getZ();
        List<BlockPos> floor = new ArrayList<>();
        List<BlockPos> border = new ArrayList<>();

        for (int dx = -baseRadius; dx <= baseRadius; dx++) {
            for (int dz = -baseRadius; dz <= baseRadius; dz++) {
                // 球门洞：中心 2×2 不铺块（直通虚空）
                if (dx >= -1 && dx <= 0 && dz >= -1 && dz <= 0) {
                    continue;
                }
                BlockPos p = new BlockPos(cx + dx, ArenaTemplate.PLATFORM_Y, cz + dz);
                floor.add(p);
                if (Math.abs(dx) == baseRadius || Math.abs(dz) == baseRadius) {
                    border.add(p);
                }
            }
        }

        // 外侧（背向中心）2 格高石砖墙，防止玩家被打飞出基地
        List<BlockPos> wall = new ArrayList<>();
        int outerX = cx - dirX * baseRadius;
        int outerZ = cz - dirZ * baseRadius;
        for (int i = -baseRadius; i <= baseRadius; i++) {
            int wx = dirX != 0 ? outerX : cx + i;
            int wz = dirZ != 0 ? outerZ : cz + i;
            wall.add(new BlockPos(wx, ArenaTemplate.PLATFORM_Y + 1, wz));
            wall.add(new BlockPos(wx, ArenaTemplate.PLATFORM_Y + 2, wz));
        }

        // 出生笼位：基地内沿往里 baseRadius-2 格（朝向桥）
        BlockPos spawn = new BlockPos(
                cx + dirX * (baseRadius - 2), ArenaTemplate.PLATFORM_Y + 1, cz + dirZ * (baseRadius - 2));

        return new BridgeBase(teamIndex, center,
                cx - 1.0, cx + 1.0, cz - 1.0, cz + 1.0,
                spawn, floor, border, wall);
    }

    // ---------- 访问器 ----------

    public BlockPos mapCenter() {
        return this.mapCenter;
    }

    public boolean fourTeam() {
        return this.fourTeam;
    }

    public int baseRadius() {
        return this.baseRadius;
    }

    public int baseWidth() {
        return this.baseWidth;
    }

    public int gap() {
        return this.gap;
    }

    public int maxRadius() {
        return this.maxRadius;
    }

    public List<BridgeBase> bases() {
        return this.bases;
    }

    public List<BlockPos> bridgeBlocks() {
        return this.bridgeBlocks;
    }

    public List<BlockPos> hubBlocks() {
        return this.hubBlocks;
    }

    /** 玩家放置/拆除时：地图本体方块不可破坏。 */
    public boolean isProtected(BlockPos pos) {
        return this.protectedBlocks.contains(pos);
    }
}
