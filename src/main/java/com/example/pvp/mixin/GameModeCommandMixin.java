package com.example.pvp.mixin;

import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.text.Messages;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.GameModeCommand;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/**
 * 竞技场内禁止切换游戏模式：OP 等级低于 3 的玩家在竞技场维度里用不了 /gamemode。
 * 来源和目标两边都管 —— 否则在大厅用 {@code /gamemode creative <场上的人>}
 * 就能把被强制旁观的玩家放出来。OP 3/4 不受限，管理员不会被锁死。
 *
 * <p>为什么拦在命令执行入口、而不是给命令树套一层包装：Brigadier 1.3.10 的
 * {@code CommandNode.command} 是 final，既没有 setCommand 也没有 setRequirement；
 * 想换节点又得整个复刻原版 gamemode 的三层参数结构。拦 {@code execute} 则参数已经解析好，
 * 目标玩家直接就能拿到。
 *
 * <p>对局自己的模式切换（幽灵 / 复活 / 发装备 / 地图编辑）全部走 {@code changeGameMode}，
 * 不经过这个命令，完全不受影响。
 */
@Mixin(GameModeCommand.class)
public abstract class GameModeCommandMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void pvp$guardArenaGamemode(CommandContext<ServerCommandSource> context,
                                               Collection<ServerPlayerEntity> targets,
                                               GameMode gameMode,
                                               CallbackInfoReturnable<Integer> cir) {
        ServerCommandSource source = context.getSource();
        if (source.hasPermissionLevel(3)) {
            return; // OP 3/4：不管
        }
        ServerPlayerEntity self = source.getPlayer();
        boolean blocked = self != null
                && self.getWorld().getRegistryKey() == ArenaWorldManager.ARENA_WORLD_KEY;
        if (!blocked) {
            for (ServerPlayerEntity target : targets) {
                if (target.getWorld().getRegistryKey() == ArenaWorldManager.ARENA_WORLD_KEY) {
                    blocked = true;
                    break;
                }
            }
        }
        if (blocked) {
            source.sendError(Messages.error("竞技场内不能切换游戏模式（需要 OP 等级 3 以上）"));
            cir.setReturnValue(0);
        }
    }
}
