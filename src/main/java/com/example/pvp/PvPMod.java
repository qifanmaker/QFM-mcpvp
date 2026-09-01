package com.example.pvp;

import com.example.pvp.arena.ArenaWorldManager;
import com.example.pvp.arena.VoidChunkGenerator;
import com.example.pvp.arena.bedwars.BedWarsEditor;
import com.example.pvp.arena.bridge.BridgeLayout;
import com.example.pvp.arena.luckypillar.LuckyPillarLayout;
import com.example.pvp.arena.skywars.SkyWarsLoot;
import com.example.pvp.arena.tntrun.TntRunLayout;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
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
import java.util.UUID;

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

    /** 被烈焰弹击退后免疫第一次摔落伤害的玩家（一次性，落地后消耗）。 */
    public static final Set<UUID> fireballNoFallOnce = new HashSet<>();

    /** 消耗一次"烈焰弹免摔"次数（返回是否有）。 */
    public static boolean consumeFireballNoFall(ServerPlayerEntity player) {
        return fireballNoFallOnce.remove(player.getUuid());
    }

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

    /** 竞技场内抛出一颗 TNT（沿视线方向，2 秒爆炸），消耗物品栏中的 1 个 TNT。 */
    public static void throwTnt(ServerPlayerEntity player, World world, ItemStack stack) {
        Vec3d look = player.getRotationVector();
        TntEntity tnt = new TntEntity(world,
                player.getX() + look.x * 0.5,
                player.getEyeY() - 0.2,
                player.getZ() + look.z * 0.5,
                player);
        tnt.setVelocity(look.multiply(1.5));
        tnt.setFuse(40); // 引信减短（2 秒爆炸）
        world.spawnEntity(tnt);
        stack.decrement(1);
    }

    /** 床战标记类型的中文名（消息提示用）。 */
    private static String markTypeName(BedWarsEditor.MarkType type) {
        return switch (type) {
            case SHOP -> "普通商店";
            case UPGRADE_SHOP -> "团队升级商店";
            case IRON -> "铁生成点";
            case GOLD -> "金生成点";
            case DIAMOND -> "钻石生成点";
            case EMERALD -> "绿宝石生成点";
        };
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
            PvpGuiManager.get().tick(); // 每秒刷新打开的 GUI，实时显示各模式排队人数
            BedWarsEditor.tickParticles(); // 持续显示床战标记粒子
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
            // 进服强制解除幽灵状态（仅限主城/非场上幽灵）：退出重进有概率残留隐身的浮空/飞行。
            // 回连正在进行中且已被淘汰的幽灵玩家保留幽灵状态（隐身/浮空），不打断。
            boolean reconnectingGhost = MATCH != null && MATCH.isInMatch(handler.player.getUuid())
                    && MATCH.isEliminated(handler.player.getUuid());
            if (!reconnectingGhost
                    && handler.player.interactionManager.getGameMode() != GameMode.CREATIVE
                    && handler.player.interactionManager.getGameMode() != GameMode.SPECTATOR) {
                handler.player.setInvisible(false);
                handler.player.setNoGravity(false);
                if (handler.player.getAbilities().allowFlying || handler.player.getAbilities().flying) {
                    handler.player.getAbilities().allowFlying = false;
                    handler.player.getAbilities().flying = false;
                    handler.player.sendAbilitiesUpdate();
                }
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
                // 烫手山芋：禁止使用（吃/右键）山芋物品，只能左键传递
                if (Match.isHotPotatoItem(stack)) {
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
                // 竞技场内 TNT：右键即抛出（对准方块时由 UseBlockCallback 拦截抛出，不再放置）
                if (stack.isOf(Items.TNT) && world.getRegistryKey() == ArenaWorldManager.ARENA_WORLD_KEY) {
                    throwTnt(serverPlayer, world, stack);
                    return TypedActionResult.success(stack);
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
        // 竞技场内 TNT：对着方块右键也抛出而不是放置（UseItemCallback 只覆盖"右键空气"路径）
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!(player instanceof ServerPlayerEntity sp) || world.getRegistryKey() != ArenaWorldManager.ARENA_WORLD_KEY
                    || !sp.getStackInHand(hand).isOf(Items.TNT)
                    || (MATCH != null && MATCH.isEliminated(sp.getUuid()))) {
                return ActionResult.PASS;
            }
            throwTnt(sp, world, sp.getStackInHand(hand));
            return ActionResult.SUCCESS;
        });
        // 床战地图标记模式：手持标记物品【右键】取消标记
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!(player instanceof ServerPlayerEntity sp) || hitResult == null || hitResult.getBlockPos() == null) {
                return ActionResult.PASS;
            }
            BedWarsEditor.Session session = BedWarsEditor.get(sp.getUuid());
            if (session == null) {
                return ActionResult.PASS;
            }
            if (BedWarsEditor.markTypeOf(sp.getStackInHand(hand)) == null) {
                return ActionResult.PASS;
            }
            BlockPos pos = hitResult.getBlockPos();
            BedWarsEditor.MarkType removed = BedWarsEditor.unmark(session, pos, world);
            if (removed != null) {
                sp.sendMessage(Messages.warn("§c已取消标记：§r" + markTypeName(removed)), false);
            }
            return ActionResult.SUCCESS; // 拦截原版交互
        });
        // 床战标记模式：右键纸 = 保存并退出（UseItemCallback 覆盖右键空气）
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!(player instanceof ServerPlayerEntity sp) || !sp.getStackInHand(hand).isOf(Items.PAPER)) {
                return TypedActionResult.pass(player.getStackInHand(hand));
            }
            BedWarsEditor.Session session = BedWarsEditor.get(sp.getUuid());
            if (session == null) {
                return TypedActionResult.pass(player.getStackInHand(hand));
            }
            if (!BedWarsEditor.isReady(session)) {
                sp.sendMessage(Messages.warn("标记不完整！需至少标记：商店(木棍)、铁点(铁锭)、金点(金锭)"), false);
                return TypedActionResult.success(player.getStackInHand(hand));
            }
            if (BedWarsEditor.save(session)) {
                sp.sendMessage(Messages.gold("§a已保存地图配置到 map.json！§r"), false);
                BedWarsEditor.remove(sp.getUuid());
                MATCH.teleportToOverworldSpawn(sp);
            }
            return TypedActionResult.success(player.getStackInHand(hand));
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayerEntity sp && MATCH != null) {
                if (MATCH.isEliminated(sp.getUuid())) {
                    return ActionResult.FAIL;
                }
                // 起床战争：右击商店实体（村民=普通商店，僵尸=升级商店）打开 GUI
                Match match = MATCH.getMatchFor(sp);
                if (match != null && match.tryOpenBedwarsShop(sp, entity)) {
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayerEntity sp && MATCH != null) {
                if (MATCH.isEliminated(sp.getUuid())) {
                    return ActionResult.FAIL;
                }
                // 烫手山芋：左键（攻击）其他存活玩家传递山芋；
                // 放行原版攻击（被打者受击退），伤害由 applyDamage 拦截（不掉血）
                Match match = MATCH.getMatchFor(sp);
                if (match != null && match.getType() == MatchType.HOT_POTATO
                        && entity instanceof ServerPlayerEntity) {
                    match.tryPassHotPotato(sp, entity);
                    return ActionResult.PASS;
                }
            }
            return ActionResult.PASS;
        });
        // 床战标记模式：手持标记物品【左键】标记（创造模式下左键破坏方块前触发）
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayerEntity sp) {
                if (MATCH != null && MATCH.isEliminated(sp.getUuid())) {
                    return false;
                }
                BedWarsEditor.Session session = BedWarsEditor.get(sp.getUuid());
                if (session != null) {
                    BedWarsEditor.MarkType type = BedWarsEditor.markTypeOf(sp.getStackInHand(player.getActiveHand()));
                    if (type != null) {
                        if (BedWarsEditor.mark(session, type, pos, world)) {
                            sp.sendMessage(Messages.gold("§a已标记" + markTypeName(type)
                                    + "§r @ (" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")"), false);
                        } else {
                            sp.sendMessage(Messages.warn("该位置已有标记"), false);
                        }
                        return false; // 阻止破坏方块
                    }
                }
            }
            return true;
        });
        // 幽灵造成的伤害全部拦截（含箭/投掷物，源攻击者为幽灵）
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (source.getAttacker() instanceof ServerPlayerEntity attacker
                    && MATCH != null && MATCH.isEliminated(attacker.getUuid())) {
                return false;
            }
            return true;
        });

        // 对局中拦截致死伤害：不弹死亡界面——战桥由对局下 tick 原地重生；其余模式直接淘汰转隐身幽灵。
        // 不在对局中的玩家（主城/竞技场访客）也取消原生死亡处理：满血送回复活点，由 Mod 自己处理。
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity sp && MATCH != null) {
                Match match = MATCH.getMatchFor(sp);
                if (match != null) {
                    if (match.getType().isBridge()) {
                        match.onBridgeDeath(sp);
                    } else if (match.getType().isBedWars()) {
                        // 起床战争：床活 → 延迟重生；床死 → 淘汰
                        match.onBedwarsDeath(sp);
                    } else if (match.getType() == MatchType.HEARTBEAT) {
                        // 心跳水立方：死亡（撞地板/掉出塔等）不淘汰，回当前关塔顶重试
                        match.onHeartbeatDeath(sp);
                    } else if (match.getState() == MatchState.ACTIVE) {
                        // 不死图腾救场：空岛/幸运之柱受到致死伤害时消耗图腾取消死亡（非掉虚空→原版逻辑原地复活）
                        if ((match.getType() == MatchType.SKYWARS || match.getType() == MatchType.LUCKY_PILLAR)
                                && match.tryTotemSave(sp, false)) {
                            // 已救回，不淘汰
                        } else {
                            // 幸运之柱：记录击杀（超时决胜用），再淘汰
                            if (match.getType() == MatchType.LUCKY_PILLAR
                                    && source.getAttacker() instanceof ServerPlayerEntity killer && killer != sp) {
                                match.registerLuckyPillarKill(killer);
                            }
                            match.eliminate(sp, EliminationCause.DEATH);
                        }
                    } else {
                        // 倒计时/庆祝中：不淘汰，直接回血防原生死亡界面
                        sp.setHealth(sp.getMaxHealth());
                        sp.setFireTicks(0);
                        sp.fallDistance = 0;
                    }
                    return false; // 对局内（含倒计时/庆祝）一律取消原生死亡处理
                }
                // 不在对局中（主城/竞技场访客）：不触发原生死亡界面，满血送回复活点
                sp.setHealth(sp.getMaxHealth());
                sp.setFireTicks(0);
                sp.clearStatusEffects();
                sp.fallDistance = 0;
                MATCH.teleportToOverworldSpawn(sp);
                sp.sendMessage(Messages.warn("你已死亡，已被送回主城"), false);
                return false;
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
                // 起床战争：床方块可破坏（触发床被摧毁），地图其他方块不可破坏，玩家搭的方块可拆
                if (match != null && match.getType().isBedWars()) {
                    return match.onBedwarsBlockBreak(sp, pos);
                }
                // 幸运之柱：柱子（柱身 + 平台）不可破坏，玩家放置的方块可拆
                if (match != null && match.getType() == MatchType.LUCKY_PILLAR) {
                    if (match.getState() != MatchState.ACTIVE) {
                        return false; // 倒计时/庆祝中禁止破坏
                    }
                    LuckyPillarLayout layout = match.luckyPillarLayout();
                    return layout == null || !layout.contains(pos);
                }
                // TNT 跑酷：平台方块不可拆（踩过的会自然掉落），玩家放置的方块可拆
                if (match != null && match.getType() == MatchType.TNT_RUN) {
                    if (match.getState() != MatchState.ACTIVE) {
                        return false; // 倒计时/庆祝中禁止破坏
                    }
                    TntRunLayout layout = match.tntRunLayout();
                    return layout == null || !layout.isPlatformBlock(pos);
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
