package com.example.pvp.mixin;

import com.example.pvp.PvPMod;
import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.match.Match;
import com.example.pvp.match.MatchManager;
import com.example.pvp.match.MatchType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TNT 跑酷：爆炸在竞技场内把塔形范围内的方块炸掉时，掉落为物品供玩家拾取搭建
 * （不然 TNT 炸平台只是清空，玩家没得搭）。在 affectWorld 破坏方块前（HEAD）生成掉落。
 */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {
    @Inject(method = "affectWorld", at = @At("HEAD"))
    private void pvp$dropTntRunBlocks(boolean createFire, CallbackInfo ci) {
        Explosion self = (Explosion) (Object) this;
        World world = self.getEntity() != null ? self.getEntity().getWorld() : null;
        if (world == null || world.getRegistryKey() != ArenaWorldManager.ARENA_WORLD_KEY) {
            return;
        }
        MatchManager manager = PvPMod.MATCH;
        if (manager == null) {
            return;
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
