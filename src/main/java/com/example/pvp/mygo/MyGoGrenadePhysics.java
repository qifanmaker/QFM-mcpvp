package com.example.pvp.mygo;

import net.minecraft.util.math.Vec3d;

/**
 * 手雷以一格一米、20 tick/s 换算；750 unit/s 来自 legacy CS:GO 分发属性。
 * 其余 Source 风格的抛物线/弹性参数适配 Minecraft 方块碰撞，不声称逐帧复刻 Source。
 */
public final class MyGoGrenadePhysics {
    public static final double UNITS_PER_BLOCK = MyGoWeapon.SOURCE_UNITS_PER_BLOCK;
    public static final int FUSE_TICKS = 30;
    public static final int SMOKE_TICKS = 18 * 20;
    public static final int MAX_FLIGHT_TICKS = 20 * 20;
    public static final double HE_RADIUS = 350 / UNITS_PER_BLOCK;
    public static final double SMOKE_RADIUS = 144 / UNITS_PER_BLOCK;
    public static final double FLASH_RADIUS = 1500 / UNITS_PER_BLOCK;
    public static final double GRAVITY = 320 / UNITS_PER_BLOCK / (20 * 20);
    public static final double STOP_SPEED = 20 / UNITS_PER_BLOCK / 20;

    private MyGoGrenadePhysics() { }

    /** 主键强抛抬高 10 度；副键从低处轻抛，平视时近乎水平。瞄准方向仍决定最终弧线。 */
    public static Vec3d launchVelocity(float pitch, float yaw, boolean strong, Vec3d playerVelocity) {
        float adjusted = strong ? pitch - (90 - Math.abs(pitch)) * 10 / 90F : pitch;
        adjusted = Math.clamp(adjusted, -89.9F, 89.9F);
        double speed = 750 * .9 * (strong ? 1 : .3) / UNITS_PER_BLOCK / 20;
        return Vec3d.fromPolar(adjusted, yaw).multiply(speed).add(playerVelocity.multiply(1.25));
    }

    public static Vec3d reflect(Vec3d velocity, Vec3d normal) {
        return velocity.subtract(normal.multiply(2 * velocity.dotProduct(normal))).multiply(.45);
    }

    /** 高斯型爆心衰减，替换旧的六格线性固定 80 点；护甲和 98/57 上限由会话统一结算。 */
    public static double heDamage(double distance) {
        if (!Double.isFinite(distance) || distance > HE_RADIUS) return 0;
        double scaled = (Math.max(0, distance) * UNITS_PER_BLOCK - 25) / 140;
        return 105 * Math.exp(-scaled * scaled);
    }

    /** 正对闪光约五秒，侧身/背身更短；这是 Minecraft 状态效果的持续时间近似。 */
    public static int flashDurationTicks(double distance, double lookDot) {
        if (!Double.isFinite(distance) || distance >= FLASH_RADIUS) return 0;
        double frontal = Math.clamp((lookDot + .2) / .8, 0, 1);
        double seconds = .95 + 3.92 * frontal;
        return Math.max(1, (int) Math.ceil(seconds * 20 * (1 - Math.max(0, distance) / FLASH_RADIUS)));
    }
}
