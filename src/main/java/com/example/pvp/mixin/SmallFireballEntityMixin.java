package com.example.pvp.mixin;

import com.example.pvp.PvPMod;
import com.example.pvp.arena.ArenaWorldManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;
import net.minecraft.world.explosion.ExplosionBehavior;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 竞技场内的烈焰弹（空岛战争/幸运之柱/TNT 跑酷可抛）：命中时引用 TNT 爆炸判定做径向击退——
 * 不造成伤害、会破坏方块（威力 1.5x）；被击退的玩家免疫第一次摔落伤害（配合无抗性提升）。
 */
@Mixin(SmallFireballEntity.class)
public abstract class SmallFireballEntityMixin {
    @Inject(method = "onCollision", at = @At("HEAD"), cancellable = true)
    private void pvp$fireballExplosion(HitResult hitResult, CallbackInfo ci) {
        SmallFireballEntity fireball = (SmallFireballEntity) (Object) this;
        if (fireball.getWorld().getRegistryKey() != ArenaWorldManager.ARENA_WORLD_KEY) {
            return;
        }
        Entity owner = fireball.getOwner();
        if (!(owner instanceof ServerPlayerEntity)) {
            return; // 仅玩家投掷的烈焰弹生效
        }
        double x = fireball.getX();
        double y = fireball.getY();
        double z = fireball.getZ();
        World world = fireball.getWorld();

        // 爆炸特效（粒子 + 音效）
        if (world instanceof ServerWorld serverWorld) {
            serverWorld.spawnParticles(ParticleTypes.EXPLOSION, x, y, z, 1, 0, 0, 0, 0);
            serverWorld.playSound(null, x, y, z, SoundEvents.ENTITY_GENERIC_EXPLODE,
                    SoundCategory.BLOCKS, 4.0F, (1.0F + serverWorld.random.nextFloat() * 0.2F) * 0.7F);
        }

        // 引用 TNT 爆炸判定：径向击退（shouldDamage=false 只跳伤害，击退照常）+
        // 破坏方块（ExplosionSourceType.TNT），威力 1.5x
        ExplosionBehavior behavior = new ExplosionBehavior() {
            @Override
            public boolean shouldDamage(Explosion explosion, Entity entity) {
                return false; // 不造成伤害，只击退
            }
        };
        DamageSource source = world.getDamageSources().explosion(fireball, owner);
        Explosion explosion = world.createExplosion(fireball, source, behavior,
                x, y, z, 1.5f, false, World.ExplosionSourceType.TNT);

        // 被爆炸击退的玩家：免疫第一次摔落伤害
        for (PlayerEntity player : explosion.getAffectedPlayers().keySet()) {
            if (player instanceof ServerPlayerEntity sp) {
                PvPMod.fireballNoFallOnce.add(sp.getUuid());
            }
        }

        fireball.discard();
        ci.cancel(); // 取消原版 onCollision（不再造成直接命中伤害/原版小爆炸）
    }
}
