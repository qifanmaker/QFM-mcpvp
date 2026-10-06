package com.example.pvp.arena.race;

import java.util.ArrayList;
import java.util.List;

/**
 * 道具箱的<b>位置计算</b>（纯几何，不依赖任何 Minecraft 类型，便于离线批量校验）。
 *
 * <p>规则：跳过起终点线（那里是发车区，留干净），其余每道门后 {@code gateOffset} 格处
 * 沿赛道法向摆 {@code lanes} 个箱子。
 *
 * <p>先沿门的前进方向推出一个粗略点，再用 {@code nearestSample} 投影回中心线并<b>重新</b>
 * 按法向偏移取点 —— 这样即使门后那一段是弯道，箱子的横向偏移也是精确的：
 * 直接沿门的切线走 12 格，在半径 32 的弯上会横向偏出约 {@code 12²/(2·32)} ≈ 2.3 格。
 */
final class RaceBoxLayout {
    /** 一个箱子位（门号 + 世界坐标）。 */
    record Box(int gate, double x, double z) {
    }

    private RaceBoxLayout() {
    }

    static List<Box> compute(RaceTrack track, double gateOffset, double laneOffset, int lanes) {
        List<Box> boxes = new ArrayList<>();
        int laneCount = Math.max(1, lanes);
        // 横向偏移不能顶到护栏：冰面半宽再收回 1 格
        double maxLat = Math.max(0.5, track.halfWidth() - 1.0);
        for (RaceTrack.Checkpoint gate : track.checkpoints()) {
            if (gate.isFinishLine()) {
                continue;
            }
            // 分岔：箱子只摆一个位置，落在岔口里就会变成"只有走内线才吃得到"，
            // 等于给两条路线加不平衡的补给 —— 所以门后 gateOffset 落在岔口区间内就整门跳过。
            if (track.isInsideForkSpan(gate.progress() + gateOffset)) {
                continue;
            }
            int sample = track.nearestSample(
                    gate.x() + gate.dirX() * gateOffset,
                    gate.z() + gate.dirZ() * gateOffset);
            for (int lane = 0; lane < laneCount; lane++) {
                double lat = (lane - (laneCount - 1) / 2.0) * laneOffset;
                lat = Math.max(-maxLat, Math.min(maxLat, lat));
                boxes.add(new Box(gate.index(),
                        track.sampleX(sample) + track.sampleNormalX(sample) * lat,
                        track.sampleZ(sample) + track.sampleNormalZ(sample) * lat));
            }
        }
        return boxes;
    }
}
