package com.example.pvp.mygo;

import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.network.packet.s2c.play.PlayerAbilitiesS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 原先所有枪都开镜，AWP 也只有一级浅倍率；现在按枪械的镜位单独发送客户端 FOV 基准。
 * 1.21.1 getFovMultiplier 使用 (movementSpeed / abilities.walkSpeed + 1) / 2，
 * S2C 能力中的 walkSpeed 不参与客户端行走物理，因此可以只改包内副本。
 * 深倍率需要有限负基准；真实能力与移动属性始终保持正常，不会反转移动方向。
 * 不能使用望远镜代替：原版 isUsingItem 会吞掉左键输入，导致开镜时无法开枪。
 * 玩家基础 FOV 为 70、FOV 效果为 100% 时对应目标角度；服务器不能覆盖客户端设置。
 */
public final class MyGoScope {
    public static final int REFERENCE_FOV = 70;
    private int sentLevel;
    private float sentWalkSpeed = Float.NaN;

    /** 应在本 tick 的实际移动属性更新之后调用，以免慢走、冲刺改变镜内倍率。 */
    public void update(ServerPlayerEntity player, int level, int targetFov) {
        double speed = player.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (level <= 0 || speed <= 0 || !Double.isFinite(speed)) {
            reset(player);
            return;
        }
        float walkSpeed = clientWalkSpeed(speed, targetFov);
        if (this.sentLevel == level && Float.compare(this.sentWalkSpeed, walkSpeed) == 0) return;
        player.networkHandler.sendPacket(packet(player.getAbilities(), walkSpeed));
        this.sentLevel = level;
        this.sentWalkSpeed = walkSpeed;
    }

    /** 关闭镜位、切枪、死亡或退场后恢复原始能力；不改变服务器上的玩家对象。 */
    public void reset(ServerPlayerEntity player) {
        if (this.sentLevel == 0) return;
        player.sendAbilitiesUpdate();
        this.sentLevel = 0;
        this.sentWalkSpeed = Float.NaN;
    }

    static float clientWalkSpeed(double movementSpeed, int targetFov) {
        double divisor = 2.0 * targetFov / REFERENCE_FOV - 1;
        if (movementSpeed <= 0 || !Double.isFinite(movementSpeed)
                || targetFov < 7 || targetFov > REFERENCE_FOV || divisor == 0) {
            throw new IllegalArgumentException("Unsupported scope FOV or movement speed");
        }
        float value = (float) (movementSpeed / divisor);
        if (!Float.isFinite(value) || value == 0) throw new IllegalArgumentException("Invalid client scope baseline");
        return value;
    }

    /** Mouse-look scale used by the server camera bridge while scoped. */
    static float sensitivityFactor(int targetFov) {
        if (targetFov < 7 || targetFov > 90) throw new IllegalArgumentException("Unsupported scope FOV");
        return Math.max(.1F, targetFov / 90.0F);
    }

    static PlayerAbilitiesS2CPacket packet(PlayerAbilities original, float walkSpeed) {
        PlayerAbilities view = new PlayerAbilities();
        view.invulnerable = original.invulnerable;
        view.flying = original.flying;
        view.allowFlying = original.allowFlying;
        view.creativeMode = original.creativeMode;
        view.allowModifyWorld = original.allowModifyWorld;
        view.setFlySpeed(original.getFlySpeed());
        view.setWalkSpeed(walkSpeed);
        return new PlayerAbilitiesS2CPacket(view);
    }
}
