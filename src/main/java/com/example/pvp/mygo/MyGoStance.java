package com.example.pvp.mygo;

/**
 * MyGO 的移动姿态：默认奔跑，Shift 静步，Ctrl 蹲下，按 CS:GO 的速度比例。
 * 服务端只能读到原版的两个状态：Shift 是潜行（客户端输入乘 .3），Ctrl 是疾跑（速度属性 +30%）。
 * 因此 Ctrl 需配合前进才会产生疾跑状态；潜行优先，同一 tick 两者并存时按静步处理。
 */
public enum MyGoStance {
    RUN(1, 1), WALK(.52, .3), CROUCH(.34, 1.3);

    /** 相对武器最大速度的目标比例。 */
    public final double speedRatio;
    /** 原版客户端已经乘上的倍率，修饰符必须把它抵消。 */
    private final double vanillaMultiplier;

    MyGoStance(double speedRatio, double vanillaMultiplier) {
        this.speedRatio = speedRatio;
        this.vanillaMultiplier = vanillaMultiplier;
    }

    public static MyGoStance of(boolean sneaking, boolean sprinting) {
        return sneaking ? WALK : sprinting ? CROUCH : RUN;
    }

    /** 乘到速度因子上，使原版倍率叠加后恰好等于目标比例。 */
    public double compensation() {
        return this.speedRatio / this.vanillaMultiplier;
    }

    /** 静步和蹲下都不产生脚步声。 */
    public boolean quiet() {
        return this != RUN;
    }
}
