package com.example.pvp;

import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.arena.VoidChunkGenerator;
import com.example.pvp.command.PvPCommands;
import com.example.pvp.config.KitConfig;
import com.example.pvp.config.PvPConfig;
import com.example.pvp.config.StatsStore;
import com.example.pvp.duel.DuelManager;
import com.example.pvp.gui.PvpGuiManager;
import com.example.pvp.kit.KitManager;
import com.example.pvp.match.MatchManager;
import com.example.pvp.queue.QueueManager;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import org.slf4j.Logger;

/**
 * PvP 匹配 Mod 入口：注册虚空生成器、加载配置、挂接生命周期事件。
 */
public final class PvPMod implements ModInitializer {
    public static final String MOD_ID = "pvp";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static MinecraftServer SERVER;
    public static MatchManager MATCH;
    public static QueueManager QUEUE;
    public static DuelManager DUEL;

    @Override
    public void onInitialize() {
        LOGGER.info("[PvP] 正在初始化 PvP 匹配 Mod...");

        Registry.register(Registries.CHUNK_GENERATOR, Identifier.of(MOD_ID, "void"), VoidChunkGenerator.CODEC);

        PvPConfig.load();
        KitConfig.load();
        StatsStore.INSTANCE.load();
        KitManager.reload();
        PvpGuiManager.init();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SERVER = server;
            ArenaWorldManager.get(server).createWorld();
            KitManager.onServerStarted(server); // 附魔注册表此时可用，重建套件应用附魔
            MATCH = MatchManager.init(server);
            QUEUE = new QueueManager(server);
            DUEL = new DuelManager(server);
            LOGGER.info("[PvP] 服务器已就绪，PvP 竞技场可用");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ArenaWorldManager.get(server).onServerStopping();
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (MATCH != null && MATCH.getServer() == server) {
                MATCH.tick();
                QUEUE.tick(MATCH);
                DUEL.tick();
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> PvPCommands.register(dispatcher));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (MATCH != null) {
                MATCH.onPlayerJoin(handler.player);
            }
            PvpGuiManager.get().giveMenuItem(handler.player);
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (player instanceof ServerPlayerEntity serverPlayer
                    && PvpGuiManager.isMenuItem(stack)) {
                PvpGuiManager.get().openMainMenu(serverPlayer);
                return TypedActionResult.success(stack);
            }
            return TypedActionResult.pass(stack);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (MATCH != null) {
                MATCH.onPlayerDisconnect(handler.player);
                DUEL.removeChallengesInvolving(handler.player.getUuid());
            }
            if (QUEUE != null) {
                QUEUE.leave(handler.player.getUuid());
            }
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayerEntity player && MATCH != null) {
                MATCH.onPlayerDeath(player);
            }
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (MATCH != null) {
                MATCH.onPlayerRespawn(oldPlayer, newPlayer, alive);
            }
        });

        LOGGER.info("[PvP] 初始化完成。使用 /pvp join <模式> <套件> 加入匹配，/duel <玩家> 发起决斗。");
    }
}
