package com.example.pvp.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 全服投掷物改为不继承投掷者速度（复刻 1.8.7 手感）。
 *
 * <p><b>根因</b>：1.21.1 {@link ProjectileEntity#setVelocity(Entity, float, float, float, float, float)}
 * 会把"投掷者自身速度"加进投掷物初速度——水平方向恒加，竖直方向在投掷者不站在地面上时也加
 * （1.9+ 引入的通用抛射物动量继承）。而 1.8.7 的 EntityThrowable(World, EntityLivingBase) / EntityArrow
 * 只按视线方向给固定初速，<b>完全不继承投掷者的运动速度</b>。
 *
 * <p>后果：玩家掉入虚空/下落途中丢投掷物时，投掷者下落速度（最高约 -3.92 格/tick 终速）会把投掷物
 * 自己的向上/向前初速抵消成净向下——典型表现就是"下坠时往上扔末影珍珠扔不上去"。
 * 重力、阻力与逐 tick 积分顺序两版本完全一致，真正差异只在初速合成这一步。
 *
 * <p>改动对所有走 {@code setVelocity(Entity, ...)} 重载的原版投掷物生效（末影珍珠/雪球/鸡蛋/喷溅药水/
 * 弓射的箭/弩/三叉戟等），覆盖 1.8.7 的不继承行为；Mod 自己用单值重载生成的抛射物不受影响。
 */
@Mixin(ProjectileEntity.class)
public abstract class ProjectileEntityMixin {
    /**
     * 把 {@code shooter.getMovement()} 的取值替换为 ZERO，等效于投掷物初速不再叠加投掷者速度。
     * 原代码随后执行 add(0, 0, 0)，不改变 {@code setVelocity(Entity, ...)} 已经算好的速度。
     */
    @Redirect(method = "setVelocity(Lnet/minecraft/entity/Entity;FFFFF)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/entity/Entity;getMovement()Lnet/minecraft/util/math/Vec3d;"))
    private Vec3d pvp$noThrowerVelocityInheritance(Entity shooter) {
        return Vec3d.ZERO; // 1.8.7 手感：投掷物初速不叠加投掷者速度（含下落时的竖直速度）
    }
}
