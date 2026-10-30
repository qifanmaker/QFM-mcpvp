package com.example.pvp.validation;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameRules;

/** Test-only entrypoint: vanilla /gamerule always targets the server overworld,
 * whereas ArenaWorldProperties deliberately owns independent game rules. */
public final class PartyBlockLootProbe implements ModInitializer {
    @Override public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> {
            // Why: the scheduler isolation regression must set and observe
            // each actual world's independent rule, then count native decay.
            // These commands are compiled only into a disposable test snapshot.
            dispatcher.register(CommandManager.literal("p2randomrate").requires(source -> source.hasPermissionLevel(4))
                    .executes(context -> context.getSource().getWorld().getGameRules().getInt(GameRules.RANDOM_TICK_SPEED))
                    .then(CommandManager.argument("speed", IntegerArgumentType.integer(0, 10000)).executes(context -> {
                        int speed = IntegerArgumentType.getInteger(context, "speed");
                        var source = context.getSource();
                        source.getWorld().getGameRules().get(GameRules.RANDOM_TICK_SPEED).set(speed, source.getServer());
                        source.sendFeedback(() -> Text.literal("P2_NATIVE_RANDOM_TICK_SPEED_" + speed), false);
                        return speed;
                    })));
            dispatcher.register(CommandManager.literal("p2randomcount").requires(source -> source.hasPermissionLevel(4))
                    .then(CommandManager.argument("minimum", BlockPosArgumentType.blockPos())
                            .then(CommandManager.argument("maximum", BlockPosArgumentType.blockPos()).executes(context -> {
                                BlockPos minimum = BlockPosArgumentType.getLoadedBlockPos(context, "minimum");
                                BlockPos maximum = BlockPosArgumentType.getLoadedBlockPos(context, "maximum");
                                long volume = ((long) Math.abs(maximum.getX() - minimum.getX()) + 1)
                                        * ((long) Math.abs(maximum.getY() - minimum.getY()) + 1)
                                        * ((long) Math.abs(maximum.getZ() - minimum.getZ()) + 1);
                                if (volume > 65536) throw new IllegalArgumentException("Test-only count is limited to 65536 cells");
                                int count = 0;
                                for (BlockPos pos : BlockPos.iterate(minimum, maximum)) {
                                    if (context.getSource().getWorld().getBlockState(pos).isOf(Blocks.DIRT)) count++;
                                }
                                final int observed = count;
                                context.getSource().sendFeedback(() -> Text.literal("P2_NATIVE_DIRT_COUNT_" + observed), false);
                                return count;
                            }))));
            dispatcher.register(CommandManager.literal("p2lootrule").requires(source -> source.hasPermissionLevel(4))
                    .then(CommandManager.argument("enabled", BoolArgumentType.bool()).executes(context -> {
                        boolean enabled = BoolArgumentType.getBool(context, "enabled");
                        var source = context.getSource();
                        source.getWorld().getGameRules().get(GameRules.DO_TILE_DROPS).set(enabled, source.getServer());
                        source.sendFeedback(() -> Text.literal("P2_NATIVE_TILE_DROPS_" + enabled), false);
                        return enabled ? 1 : 0;
                    })));
            dispatcher.register(CommandManager.literal("p2lootdirection").requires(source -> source.hasPermissionLevel(4))
                    .then(CommandManager.argument("position", BlockPosArgumentType.blockPos()).executes(context -> {
                        Block.dropStack(context.getSource().getWorld(), BlockPosArgumentType.getLoadedBlockPos(context, "position"),
                                Direction.UP, new ItemStack(Items.DIAMOND));
                        return 1;
                    })));
        });
    }
}
