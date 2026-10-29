package com.example.pvp.validation;

import com.mojang.brigadier.arguments.BoolArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.Block;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.util.math.Direction;
import net.minecraft.world.GameRules;

/** Test-only entrypoint: vanilla /gamerule always targets the server overworld,
 * whereas ArenaWorldProperties deliberately owns independent game rules. */
public final class PartyBlockLootProbe implements ModInitializer {
    @Override public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> {
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
