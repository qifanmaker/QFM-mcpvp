package com.example.pvp.mygo;

/**
 * 原版右键交互方块也会紧随使用包发送一次主手摆手，不能把这次动画当作左键。
 * 只为已确认会预测摆手的 block-use 建立一次性令牌，按同一 TCP 连接顺序消费；
 * 不使用时间窗，空气右键后立即左键仍能射击。明确的新输入会清除过期推断。
 */
public final class MyGoInputGuard {
    private boolean predictedMainHandSwing;

    public void blockUse(boolean predictsMainHandSwing) {
        this.predictedMainHandSwing = predictsMainHandSwing;
    }

    public boolean consumePredictedSwing(boolean mainHand) {
        if (!mainHand || !this.predictedMainHandSwing) return false;
        this.predictedMainHandSwing = false;
        return true;
    }

    public void clear() {
        this.predictedMainHandSwing = false;
    }
}
