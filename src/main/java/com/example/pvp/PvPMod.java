package com.example.pvp;

import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.arena.VoidChunkGenerator;
import com.example.pvp.arena.bridge.BridgeLayout;
import com.example.pvp.arena.luckypillar.LuckyPillarLayout;
import com.example.pvp.arena.skywars.SkyWarsLoot;
import com.example.pvp.command.PvPCommands;
import com.example.pvp.config.KitConfig;
import com.example.pvp.config.PvPConfig;
import com.example.pvp.config.StatsStore;
import com.example.pvp.duel.DuelManager;
import com.example.pvp.gui.PvpGuiManager;
import com.example.pvp.kit.BridgeGear;
import com.example.pvp.kit.KitManager;
import com.example.pvp.match.EliminationCause;
import com.example.pvp.match.Match;
import com.example.pvp.match.MatchManager;
import com.example.pvp.match.MatchState;
import com.example.pvp.match.MatchType;
import com.example.pvp.queue.QueueManager;
import com.example.pvp.text.Messages;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /** 幸运之柱"一击必杀"全局标记：开启时对应对局内所有伤害致死（LivingEntityMixin 检查）。 */
    public static volatile boolean oneHitKillActive = false;

    /** 主城内需自动补 TNT 的发射器（仅主世界，加载/卸载自动增删）。 */
    private static final Set<DispenserBlockEntity> TNT_DISPENSERS = new HashSet<>();

    /** 插件版本号（来自 fabric.mod.json，构建时由 Gradle 填充）。 */
    public static String version() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
    }

    /** 发射一颗火焰弹（空岛战争）：沿视线方向生成小火焰弹，落地/命中会爆炸击退。 */
    public static void launchFireCharge(ServerPlayerEntity player, World world) {
        Vec3d look = player.getRotationVector();
        SmallFireballEntity fireball = new SmallFireballEntity(world, player, look);
        fireball.setPosition(player.getX(), player.getEyeY() - 0.1, player.getZ());
        fireball.setVelocity(look.multiply(1.5)); // 覆盖构造时的 0.1 倍速，飞得更快
        world.spawnEntity(fireball);
    }

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
            SkyWarsLoot.onServerStarted(server); // 空岛战利品附魔
            BridgeGear.onServerStarted(server); // 战桥装备附魔（效率 II 镐）
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

        // 主城内所有发射器实时自动补满 TNT（仅主城/主世界；加载/卸载自动增删追踪）
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, world) -> {
            if (world.getRegistryKey() == World.OVERWORLD && blockEntity instanceof DispenserBlockEntity dispenser) {
                TNT_DISPENSERS.add(dispenser);
            }
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, world) -> {
            if (blockEntity instanceof DispenserBlockEntity) {
                TNT_DISPENSERS.remove(blockEntity);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (DispenserBlockEntity dispenser : List.copyOf(TNT_DISPENSERS)) {
                if (dispenser.isRemoved() || dispenser.getWorld() != server.getOverworld()) {
                    TNT_DISPENSERS.remove(dispenser);
                    continue;
                }
                for (int i = 0; i < dispenser.size(); i++) {
                    ItemStack stack = dispenser.getStack(i);
                    if (!stack.isOf(Items.TNT) || stack.getCount() < 64) {
                        dispenser.setStack(i, new ItemStack(Items.TNT, 64));
                    }
                }
            }
        });

        // 主城 TNT 实体安全上限：防止大量 TNT 堆积导致服务器卡死/看门狗崩溃
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 40 != 0) {
                return;
            }
            ServerWorld overworld = server.getOverworld();
            List<? extends TntEntity> tnts = overworld.getEntitiesByType(EntityType.TNT, (TntEntity tnt) -> true);
            if (tnts.size() > 256) {
                for (int i = 256; i < tnts.size(); i++) {
                    tnts.get(i).discard();
                }
                LOGGER.warn("[PvP] 主城 TNT 实体过多（{}），已清理超出部分防止卡服", tnts.size());
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> PvPCommands.register(dispatcher));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            // 先清空物品再发 UI 工具，避免背包挤满
            handler.player.getInventory().clear();
            if (MATCH != null) {
                MATCH.onPlayerJoin(handler.player);
            }
            PvpGuiManager.get().giveMenuItem(handler.player);
            PvpGuiManager.removeQueueItem(handler.player); // 清理断线残留的排队红石
            // 进服显示插件名 + 版本号，方便确认是否最新版本
            handler.player.sendMessage(Messages.gold(
                    "§6PvP 匹配 Mod §fv" + version() + " §r已连接，右键指南针打开菜单"), false);
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (player instanceof ServerPlayerEntity serverPlayer) {
                // 死亡幽灵：禁止使用任何物品
                if (MATCH != null && MATCH.isEliminated(serverPlayer.getUuid())) {
                    return TypedActionResult.fail(stack);
                }
                // 旁观者 UI：指南针切换观战 / 绿宝石下一把 / 红石退出
                if (PvpGuiManager.isSpectatorUiItem(stack)) {
                    Match match = MATCH == null ? null : MATCH.getMatchFor(serverPlayer);
                    if (match != null) {
                        switch (PvpGuiManager.getSpectatorTag(stack)) {
                            case "spectate" -> match.cycleSpectate(serverPlayer);
                            case "requeue" -> match.spectatorLeave(serverPlayer, true);
                            case "exit" -> match.spectatorLeave(serverPlayer, false);
                            default -> {
                            }
                        }
                    }
                    return TypedActionResult.success(stack);
                }
                if (PvpGuiManager.isMenuItem(stack)) {
                    PvpGuiManager.get().openMainMenu(serverPlayer);
                    return TypedActionResult.success(stack);
                }
                if (PvpGuiManager.isQueueItem(stack)) {
                    if (QUEUE.leave(serverPlayer)) {
                        serverPlayer.sendMessage(Messages.info("已离开匹配队列"), false);
                    }
                    return TypedActionResult.success(stack);
                }
                // 1.8 战斗模式（1.8 经典PvP / 空岛战争）：右键剑进入格挡
                if (stack.getItem() instanceof SwordItem) {
                    Match match = MATCH == null ? null : MATCH.getMatchFor(serverPlayer);
                    if (MATCH != null && MATCH.isLegacyCombat(match)) {
                        match.setBlocking(serverPlayer, true);
                        return TypedActionResult.success(stack);
                    }
                }
                // 竞技场内 TNT：对空中右键可把 TNT 抛射出去（对准方块则交给原版放置）
                if (stack.isOf(Items.TNT) && world.getRegistryKey() == ArenaWorldManager.ARENA_WORLD_KEY) {
                    HitResult hit = serverPlayer.raycast(4.5, 1.0F, false);
                    if (hit != null && hit.getType() == HitResult.Type.MISS) {
                        Vec3d look = serverPlayer.getRotationVector();
                        TntEntity tnt = new TntEntity(world,
                                serverPlayer.getX() + look.x * 0.5,
                                serverPlayer.getEyeY() - 0.2,
                                serverPlayer.getZ() + look.z * 0.5,
                                serverPlayer);
                        tnt.setVelocity(look.multiply(1.5));
                        tnt.setFuse(40); // 引信减短（2 秒爆炸）
                        world.spawnEntity(tnt);
                        stack.decrement(1);
                        return TypedActionResult.success(stack);
                    }
                    return TypedActionResult.pass(stack);
                }
                // 竞技场内火焰弹（空岛战争）：对空右键即发射（对方块右键由 FireChargeItemMixin 拦截发射）
                if (stack.isOf(Items.FIRE_CHARGE) && world.getRegistryKey() == ArenaWorldManager.ARENA_WORLD_KEY) {
                    launchFireCharge(serverPlayer, world);
                    stack.decrement(1);
                    return TypedActionResult.success(stack);
                }
            }
            return TypedActionResult.pass(stack);
        });

        // 死亡幽灵（已淘汰玩家）：禁止一切交互（开箱/按键/攻击/使用物品）
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player instanceof ServerPlayerEntity sp && MATCH != null && MATCH.isEliminated(sp.getUuid())) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayerEntity sp && MATCH != null && MATCH.isEliminated(sp.getUuid())) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayerEntity sp && MATCH != null && MATCH.isEliminated(sp.getUuid())) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (player instanceof ServerPlayerEntity sp && MATCH != null && MATCH.isEliminated(sp.getUuid())) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
        // 幽灵造成的伤害全部拦截（含箭/投掷物，源攻击者为幽灵）
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (source.getAttacker() instanceof ServerPlayerEntity attacker
                    && MATCH != null && MATCH.isEliminated(attacker.getUuid())) {
                return false;
            }
            return true;
        });

        // 对局中拦截致死伤害：不弹死亡界面——战桥由对局下 tick 原地重生；其余模式直接淘汰转隐身幽灵
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity sp && MATCH != null) {
                Match match = MATCH.getMatchFor(sp);
                if (match != null) {
                    if (match.getType().isBridge()) {
                        match.onBridgeDeath(sp);
                    } else if (match.getState() == MatchState.ACTIVE) {
                        // 幸运之柱：记录击杀（超时决胜用），再淘汰
                        if (match.getType() == MatchType.LUCKY_PILLAR
                                && source.getAttacker() instanceof ServerPlayerEntity killer && killer != sp) {
                            match.registerLuckyPillarKill(killer);
                        }
                        match.eliminate(sp, EliminationCause.DEATH);
                    }
                    return false;
                }
            }
            return true;
        });

        // 战桥：地图方块不可破坏（只能拆自己放置的方块）——用布局的保护方块集合判断
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (world.getRegistryKey() != ArenaWorldManager.ARENA_WORLD_KEY) {
                return true;
            }
            if (player instanceof ServerPlayerEntity sp && MATCH != null) {
                Match match = MATCH.getMatchFor(sp);
                if (match != null && match.getType().isBridge()) {
                    if (match.getState() != MatchState.ACTIVE) {
                        return false; // 倒计时/庆祝中禁止破坏
                    }
                    BridgeLayout layout = match.bridgeLayout();
                    return layout == null || !layout.isProtected(pos);
                }
                // 幸运之柱：柱子（柱身 + 平台）不可破坏，玩家放置的方块可拆
                if (match != null && match.getType() == MatchType.LUCKY_PILLAR) {
                    if (match.getState() != MatchState.ACTIVE) {
                        return false; // 倒计时/庆祝中禁止破坏
                    }
                    LuckyPillarLayout layout = match.luckyPillarLayout();
                    return layout == null || !layout.contains(pos);
                }
            }
            return true;
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
