package com.example.pvp.mixin;

import com.example.pvp.PvPMod;
import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.config.PvPConfig;
import com.example.pvp.arena.party.PartySourceMobs;
import com.example.pvp.match.Match;
import com.example.pvp.match.MatchManager;
import com.example.pvp.match.MatchType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 烈焰弹：在原版广播爆炸包前同步配置的水平、垂直击退倍率。
 * TNT 跑酷：爆炸在竞技场内把塔形范围内的方块炸掉时，掉落为物品供玩家拾取搭建
 * （不然 TNT 炸平台只是清空，玩家没得搭）。在 affectWorld 破坏方块前（HEAD）生成掉落。
 */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {
    @Shadow @Final private World world;
    @Shadow @Final @Mutable private Explosion.DestructionType destructionType;

    /** 原打靶地图禁用 mobGriefing；只把本场原生目标的爆炸改为保留方块，不影响其他爆炸。 */
    @Inject(method = "<init>(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;Lnet/minecraft/entity/damage/DamageSource;Lnet/minecraft/world/explosion/ExplosionBehavior;DDDFZLnet/minecraft/world/explosion/Explosion$DestructionType;Lnet/minecraft/particle/ParticleEffect;Lnet/minecraft/particle/ParticleEffect;Lnet/minecraft/registry/entry/RegistryEntry;)V", at = @At("TAIL"))
    private void pvp$sourceMobGriefing(CallbackInfo ci) {
        if (PartySourceMobs.isSourceMob(((Explosion) (Object) this).getEntity())) {
            this.destructionType = Explosion.DestructionType.KEEP;
        }
    }

    /**
     * 烈焰弹倍率必须在 ServerWorld 广播爆炸包前写回玩家向量。
     * 原先返回 createExplosion 后才补服务端速度，燃烧无敌帧不触发额外受击同步时，
     * 客户端只得到未放大的击退；这里同时更新服务端速度和原版爆炸包读取的映射。
     */
    @Inject(method = "collectBlocksAndDamageEntities", at = @At("TAIL"))
    private void pvp$syncFireballKnockback(CallbackInfo ci) {
        Explosion self = (Explosion) (Object) this;
        if (this.world.getRegistryKey() != ArenaWorldManager.ARENA_WORLD_KEY
                || !(self.getEntity() instanceof FireballEntity fireball)
                || !(fireball.getOwner() instanceof ServerPlayerEntity)) return;
        float horizontal = Math.max(0.0f, PvPConfig.INSTANCE.fireballKnockbackHorizontal);
        float vertical = Math.max(0.0f, PvPConfig.INSTANCE.fireballKnockbackVertical);
        if (horizontal == 1.0f && vertical == 1.0f) return;
        for (var entry : self.getAffectedPlayers().entrySet()) {
            PlayerEntity player = entry.getKey();
            Vec3d base = entry.getValue();
            Vec3d scaled = new Vec3d(base.x * horizontal, base.y * vertical, base.z * horizontal);
            player.addVelocity(scaled.x - base.x, scaled.y - base.y, scaled.z - base.z);
            entry.setValue(scaled);
        }
    }

    @Inject(method = "affectWorld", at = @At("HEAD"))
    private void pvp$dropTntRunBlocks(boolean createFire, CallbackInfo ci) {
        Explosion self = (Explosion) (Object) this;
        World world = this.world;
        if (world.getRegistryKey() != ArenaWorldManager.ARENA_WORLD_KEY) {
            return;
        }
        MatchManager manager = PvPMod.MATCH;
        if (manager == null) {
            return;
        }
        // 移植地图的目标/原版小游戏地形只能由本场会话修改，不能被邻场爆炸拆掉。
        var portedMatches = manager.getMatches().stream().filter(match -> match.portedSession() != null).toList();
        if (!portedMatches.isEmpty()) {
            self.getAffectedBlocks().removeIf(pos -> portedMatches.stream().anyMatch(match -> match.ownsPortedPosition(pos)));
        }
        for (BlockPos pos : self.getAffectedBlocks()) {
            if (world.getBlockState(pos).isAir()) {
                continue;
            }
            boolean inTntRun = false;
            for (Match match : manager.getMatches()) {
                if (match.getType() == MatchType.TNT_RUN && match.tntRunLayout() != null
                        && match.tntRunLayout().isWithinArea(pos)) {
                    inTntRun = true;
                    break;
                }
            }
            if (!inTntRun) {
                continue;
            }
            ItemStack stack = new ItemStack(world.getBlockState(pos).getBlock().asItem());
            if (stack.isEmpty()) {
                continue;
            }
            ItemEntity item = new ItemEntity(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
            item.setVelocity((Math.random() - 0.5) * 0.2, 0.2, (Math.random() - 0.5) * 0.2);
            world.spawnEntity(item);
        }
    }
}
