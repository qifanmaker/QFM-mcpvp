package com.example.pvp.validation;

import com.example.pvp.mygo.practice.MyGoPracticeManager;
import com.google.gson.Gson;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test-only snapshot entry point. MyGO intentionally rejects vanilla /kill
 * damage, so deterministic multi-victim cases invoke the existing public
 * onDeath path in one command tick. No HP, roster or round fields are changed.
 * This file is never part of src/main or the published mod entry points.
 */
public final class MyGoDeathTakeoverProbe implements ModInitializer {
    @Override public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(CommandManager.literal("mygo_takeover_test")
                        .requires(source -> source.hasPermissionLevel(4))
                        .then(CommandManager.literal("kill")
                                .then(CommandManager.argument("actors", EntityArgumentType.entities())
                                        .executes(context -> {
                                            var manager = MyGoPracticeManager.get();
                                            var source = context.getSource();
                                            var session = manager.session(source.getPlayerOrThrow());
                                            if (session == null) throw new IllegalStateException("Test operator must join a practice room");
                                            var actors = EntityArgumentType.getEntities(context, "actors");
                                            for (Entity entity : actors) if (!(entity instanceof ServerPlayerEntity player)
                                                    || manager.session(player) != session)
                                                throw new IllegalStateException("All test victims must belong to the operator's practice room");
                                            var rows = new ArrayList<Map<String, Object>>();
                                            for (Entity entity : actors) {
                                                var player = (ServerPlayerEntity) entity;
                                                var before = session.botView(player);
                                                Map<String, Object> row = new LinkedHashMap<>();
                                                row.put("tick", source.getServer().getTicks());
                                                row.put("actor", player.getUuid().toString());
                                                row.put("name", player.getGameProfile().getName());
                                                row.put("phase", before == null ? "NONE" : before.phase());
                                                row.put("aliveBefore", before != null && before.alive());
                                                session.onDeath(player);
                                                var after = session.botView(player);
                                                row.put("aliveAfter", after != null && after.alive());
                                                rows.add(row);
                                            }
                                            try {
                                                Files.writeString(Path.of("mygo-takeover-probe.jsonl"), new Gson().toJson(rows) + "\n",
                                                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                                            } catch (java.io.IOException error) {
                                                throw new IllegalStateException("Could not preserve test death-batch evidence", error);
                                            }
                                            source.sendFeedback(() -> Text.literal("MyGoTakeoverProbe tick="
                                                    + source.getServer().getTicks() + " count=" + rows.size()), false);
                                            return rows.size();
                                        })))));
    }
}
