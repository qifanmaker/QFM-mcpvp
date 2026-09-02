package com.example.pvp.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 服务器配置 config/pvp/config.json（可热重载）。
 */
public final class PvPConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static PvPConfig INSTANCE = new PvPConfig();

    /** 自由乱斗：凑齐最少人数后开始倒计时开赛。 */
    public int ffaMinPlayers = 3;
    public int ffaCountdownSeconds = 60;
    public int ffaEarlyStartPlayers = 6;
    public int ffaEarlyStartSeconds = 10;
    public int ffaMaxPlayers = 16;

    public int countdownSeconds = 5;
    public int maxConcurrentMatches = 4;
    public int duelExpirySeconds = 30;
    /** 大厅保护：不在对局的玩家设为冒险模式、无敌、饱食度不掉。 */
    public boolean lobbyProtection = true;
    /** 对局超时（秒）：超过后强制平局结束，防止卡死的对局占用场地。 */
    public int matchTimeoutSeconds = 600;

    public String floorBlock = "minecraft:polished_deepslate";
    public String wallBlock = "minecraft:glass";

    public int duel1v1Size = 51;
    public int duel2v2Size = 71;
    public int ffaSize = 101;
    public int sumoSize = 11;

    // ---------- 空岛战争 (SkyWars) ----------
    /** 空岛战争：最少/触发开赛倒计时/最多人数。默认凑齐 4 人开赛，最少 2 人可开。 */
    public int skywarsMinPlayers = 2;
    public int skywarsStartPlayers = 4;
    public int skywarsMaxPlayers = 8;
    /** 开赛倒计时（秒）；不足开赛人数时等待填充的最长时间（秒）。 */
    public int skywarsCountdownSeconds = 30;
    public int skywarsFillTimeoutSeconds = 60;
    /** 地图覆盖边长（生成/清理边界，需覆盖所有岛屿）。 */
    public int skywarsSize = 176;
    public int skywarsIslandRadius = 5;
    /** 中岛群半径（岛群覆盖范围，中央主岛+卫星岛；spawnDist 随之外推，与其他岛 gap 不变）。
     *  地图放大主要拉大该值：80 → 整图直径约 290 格（原 ~218）。 */
    public int skywarsMiddleRadius = 80;
    /** 出生岛边缘到中间主岛边缘的空隙（格）：越大出生岛离中间岛越远、越难偷袭。 */
    public int skywarsIslandGap = 50;
    public int skywarsChestsPerIsland = 3;
    /** 中间主岛箱子数（均匀分布在整个圆盘上）。 */
    public int skywarsMiddleChests = 10;
    /** 中途岛：半径固定为玩家岛×1.5，每个玩家岛对应的中途岛箱数。 */
    public int skywarsMidIslandChests = 3;
    /** 对局超时（秒）与缩圈：开赛多少秒后开始缩圈、每圈间隔多少秒、每圈塌掉几格、最小安全半径。 */
    public int skywarsTimeoutSeconds = 600;
    public int skywarsShrinkStartSeconds = 180;
    public int skywarsShrinkIntervalSeconds = 30;
    public int skywarsShrinkBlocksPerStage = 4;
    public int skywarsShrinkMinRadius = 8;
    /** 开赛多少秒后触发"物资刷新"事件：清空并重新塞满全图所有箱子（默认 240s=4 分钟）。 */
    public int skywarsRefillSeconds = 240;

    // ---------- 战桥 (Bridge) ----------
    /** 区域覆盖边长（生成/清理边界，需覆盖整张地图）。 */
    public int bridgeSize = 101;
    /** 基地半宽：基地边长为 2*bridgeBaseRadius+1（默认 13）。 */
    public int bridgeBaseRadius = 6;
    /** 两基地内沿（或枢纽外沿到基地内沿）之间的虚空间隔（格），即搭桥区。 */
    public int bridgeGap = 35;
    /** 先得 X 分获胜（所有战桥模式通用）。 */
    public int bridgeWinScore = 5;
    /** 箭矢回复间隔（秒）：弓每次给 1 支箭，用完后每隔该时长补 1 支。 */
    public int bridgeArrowRegenSeconds = 4;
    /** 战桥混战最少人数（需为偶数，总人数/2 分两队）。 */
    public int bridgeTeamMinPlayers = 4;
    /** 对局超时（秒）：超过后比分高者获胜，平局结束。 */
    public int bridgeTimeoutSeconds = 300;

    // ---------- 幸运之柱 (Lucky Pillar) ----------
    /** 最少/触发开赛倒计时/最多人数。默认凑齐 4 人开赛，最少 2 人可开。 */
    public int luckyPillarMinPlayers = 2;
    public int luckyPillarStartPlayers = 4;
    public int luckyPillarMaxPlayers = 8;
    /** 开赛倒计时（秒）；不足开赛人数时等待填充的最长时间（秒）。 */
    public int luckyPillarCountdownSeconds = 30;
    public int luckyPillarFillTimeoutSeconds = 60;
    /** 地图覆盖边长（生成/清理边界，需覆盖所有柱子）。 */
    public int luckyPillarSize = 101;
    /** 随机物品发放间隔（秒）：每隔该时长每名存活玩家获得 1 件纯随机物品（开赛立即发一轮）。 */
    public int luckyPillarItemIntervalSeconds = 3;
    /** 随机事件间隔（秒）：每隔该时长触发一个随机事件。 */
    public int luckyPillarEventIntervalSeconds = 45;
    /** 是否开启随机事件（一击必杀/箭雨/雷击/TNT 雨/位置交换/补给潮）。 */
    public boolean luckyPillarEvents = true;
    /** 一击必杀事件持续时长（秒）。 */
    public int luckyPillarOneHitSeconds = 10;
    /** 对局超时（秒）：超过后击杀最多的存活者获胜，无人有击杀则平局。 */
    public int luckyPillarTimeoutSeconds = 600;
    /** 柱顶高于地图中心的高度（格）。 */
    public int luckyPillarHeight = 40;
    /** 相邻柱子的间隙（格）：柱子 1 格宽，越大越需要搭方块跨柱（默认 8 格）。 */
    public int luckyPillarGap = 8;
    /** 柱顶下方多少格有一圈大平台（即柱高，默认 40 格；掉出平台下方 20 格死亡）。 */
    public int luckyPillarPlatformGap = 40;

    // ---------- TNT 跑酷 (TNT Run) ----------
    /** 最少/触发开赛倒计时/最多人数。默认凑齐 4 人开赛，最少 2 人可开。 */
    public int tntRunMinPlayers = 2;
    public int tntRunStartPlayers = 4;
    public int tntRunMaxPlayers = 8;
    /** 开赛倒计时（秒）；不足开赛人数时等待填充的最长时间（秒）。 */
    public int tntRunCountdownSeconds = 30;
    public int tntRunFillTimeoutSeconds = 60;
    /** 平台边长（每层方形，默认 31 格）。 */
    public int tntRunSize = 31;
    /** 层数（默认 5 层）。 */
    public int tntRunLayerCount = 5;
    /** 层间距（格，默认 6）。 */
    public int tntRunLayerGap = 6;
    /** 踩过的方块多少 tick 后消失（默认 5 tick = 0.25 秒）。 */
    public int tntRunVanishTicks = 5;
    /** 地面掉落物刷新间隔（tick，默认 40 = 2 秒）。 */
    public int tntRunDropIntervalTicks = 40;
    /** 对局超时（秒）：超过后击杀最多者胜，无击杀平局。 */
    public int tntRunTimeoutSeconds = 600;

    // ---------- 心跳水立方 (Heartbeat) ----------
    /** 最少/触发开赛倒计时/最多人数。默认凑齐 4 人开赛，最少 2 人可开。 */
    public int heartbeatMinPlayers = 2;
    public int heartbeatStartPlayers = 4;
    public int heartbeatMaxPlayers = 8;
    /** 开赛倒计时（秒）；不足开赛人数时等待填充的最长时间（秒）。 */
    public int heartbeatCountdownSeconds = 30;
    public int heartbeatFillTimeoutSeconds = 60;
    /** 塔区覆盖边长 = 每关塔的宽（正方形塔，边长 2*halfSize+1）。 */
    public int heartbeatSize = 21;
    /** 关卡总数（塔并排，第 1 关最易 → 最后一关最难）。 */
    public int heartbeatLevels = 5;
    /** 层间距（格，默认 35：下落约 1.5 秒+，配合上下层关联洞位，足够横向移动对准）。 */
    public int heartbeatFloorGap = 35;
    /** 第 1 关玻璃地板层数；每过一关 +1（最后一关 = baseFloors + levels - 1）。 */
    public int heartbeatBaseFloors = 3;
    /** 对局超时（秒）：超时后按关卡进度排名结算，进度最高者胜。 */
    public int heartbeatTimeoutSeconds = 300;

    // ---------- 烫手山芋 (Hot Potato) ----------
    /** 最少/触发开赛倒计时/最多人数。默认凑齐 4 人开赛，最少 2 人可开。 */
    public int hotPotatoMinPlayers = 2;
    public int hotPotatoStartPlayers = 4;
    public int hotPotatoMaxPlayers = 8;
    /** 开赛倒计时（秒）；不足开赛人数时等待填充的最长时间（秒）。 */
    public int hotPotatoCountdownSeconds = 30;
    public int hotPotatoFillTimeoutSeconds = 60;
    /** 障碍物平台边长（默认 61）。 */
    public int hotPotatoSize = 61;
    /** 山芋持有多少秒后爆炸（默认 20 秒）。 */
    public int hotPotatoExplodeSeconds = 20;
    /** 爆炸倒计时最后几秒开始警告（默认 5 秒）。 */
    public int hotPotatoWarnSeconds = 5;
    /** 持有者是否获得速度 I（追逐传递用，默认 true）。 */
    public Boolean hotPotatoHolderSpeed = true;
    /** 山芋爆炸后多久重新随机发放（秒，默认 2）。 */
    public int hotPotatoRespawnSeconds = 2;
    /** 对局超时（秒）：超时后当前持有者爆炸淘汰。 */
    public int hotPotatoTimeoutSeconds = 600;
    // ---------- 起床战争 (Bed Wars) ----------
    /** 区域覆盖边长（生成/清理边界，需覆盖整张地图；Hypixel 图约 100 格）。 */
    public int bedWarsSize = 200;
    /** 每队铁生成器间隔（秒）。 */
    public int bedWarsIronInterval = 2;
    /** 每队金生成器间隔（秒）。 */
    public int bedWarsGoldInterval = 6;
    /** 死亡后复活延迟（秒）。 */
    public int bedWarsRespawnSeconds = 5;
    /** 开局初始羊毛数量（每队玩家）。 */
    public int bedWarsStartWool = 16;
    /** 对局超时（秒）：超过后按存活队伍/床数判定。 */
    public int bedWarsTimeoutSeconds = 900;
    /** 开赛倒计时（秒，大厅等待）。 */
    public int bedWarsCountdownSeconds = 5;
    private PvPConfig() {
    }

    public static void load() {
        Path path = getConfigPath();
        if (Files.exists(path)) {
            try {
                PvPConfig parsed = GSON.fromJson(Files.readString(path), PvPConfig.class);
                INSTANCE = parsed != null ? parsed : new PvPConfig();
            } catch (Exception e) {
                LOGGER.warn("[PvP] 配置解析失败，使用默认配置: {}", e.toString());
                INSTANCE = new PvPConfig();
            }
        } else {
            LOGGER.info("[PvP] 未找到配置文件，生成默认配置 {}", path);
            save();
        }
        // 兼容旧配置：新版本新增字段在旧 config.json 中缺失时 Gson 会解析为 0，用默认值补齐
        if (INSTANCE.migrateOldConfig()) {
            save();
        }
    }

    /** 旧配置文件缺少的新字段用默认值补齐（这些字段合法值均 >0，0 即视为缺失）。 */
    private boolean migrateOldConfig() {
        PvPConfig defaults = new PvPConfig();
        boolean changed = false;
        if (this.bridgeSize <= 0) {
            this.bridgeSize = defaults.bridgeSize;
            changed = true;
        }
        if (this.bridgeBaseRadius <= 0) {
            this.bridgeBaseRadius = defaults.bridgeBaseRadius;
            changed = true;
        }
        if (this.bridgeGap <= 0) {
            this.bridgeGap = defaults.bridgeGap;
            changed = true;
        }
        if (this.bridgeWinScore <= 0) {
            this.bridgeWinScore = defaults.bridgeWinScore;
            changed = true;
        }
        if (this.bridgeArrowRegenSeconds <= 0) {
            this.bridgeArrowRegenSeconds = defaults.bridgeArrowRegenSeconds;
            changed = true;
        }
        if (this.bridgeTeamMinPlayers <= 0) {
            this.bridgeTeamMinPlayers = defaults.bridgeTeamMinPlayers;
            changed = true;
        }
        if (this.bridgeTimeoutSeconds <= 0) {
            this.bridgeTimeoutSeconds = defaults.bridgeTimeoutSeconds;
            changed = true;
        }
        if (this.skywarsMiddleRadius <= 0) {
            this.skywarsMiddleRadius = defaults.skywarsMiddleRadius;
            changed = true;
        } else if (this.skywarsMiddleRadius == 45 || this.skywarsMiddleRadius == 52) {
            // 旧默认 45/52 改为新默认（地图继续加大，配合 REGION_SPACING 增大）
            this.skywarsMiddleRadius = defaults.skywarsMiddleRadius;
            changed = true;
        }
        if (this.skywarsIslandGap <= 0) {
            this.skywarsIslandGap = defaults.skywarsIslandGap;
            changed = true;
        } else if (this.skywarsIslandGap == 40) {
            // 旧默认 40 改为新默认（出生岛外推，配合更大的中岛群）
            this.skywarsIslandGap = defaults.skywarsIslandGap;
            changed = true;
        }
        if (this.skywarsRefillSeconds <= 0) {
            this.skywarsRefillSeconds = defaults.skywarsRefillSeconds;
            changed = true;
        } else if (this.skywarsRefillSeconds == 300) {
            // 旧默认 300s(5 分钟) 改为新默认 240s(4 分钟)
            this.skywarsRefillSeconds = defaults.skywarsRefillSeconds;
            changed = true;
        }
        if (this.luckyPillarMinPlayers <= 0) {
            this.luckyPillarMinPlayers = defaults.luckyPillarMinPlayers;
            changed = true;
        }
        if (this.luckyPillarStartPlayers <= 0) {
            this.luckyPillarStartPlayers = defaults.luckyPillarStartPlayers;
            changed = true;
        }
        if (this.luckyPillarMaxPlayers <= 0) {
            this.luckyPillarMaxPlayers = defaults.luckyPillarMaxPlayers;
            changed = true;
        }
        if (this.luckyPillarCountdownSeconds <= 0) {
            this.luckyPillarCountdownSeconds = defaults.luckyPillarCountdownSeconds;
            changed = true;
        }
        if (this.luckyPillarFillTimeoutSeconds <= 0) {
            this.luckyPillarFillTimeoutSeconds = defaults.luckyPillarFillTimeoutSeconds;
            changed = true;
        }
        if (this.luckyPillarSize <= 0) {
            this.luckyPillarSize = defaults.luckyPillarSize;
            changed = true;
        }
        if (this.luckyPillarItemIntervalSeconds <= 0) {
            this.luckyPillarItemIntervalSeconds = defaults.luckyPillarItemIntervalSeconds;
            changed = true;
        } else if (this.luckyPillarItemIntervalSeconds == 15 || this.luckyPillarItemIntervalSeconds == 1
                || this.luckyPillarItemIntervalSeconds == 2) {
            // 旧默认 15 秒 / 1 秒 / 2 秒改为新默认（每 3 秒刷 1 件）
            this.luckyPillarItemIntervalSeconds = defaults.luckyPillarItemIntervalSeconds;
            changed = true;
        }
        if (this.luckyPillarEventIntervalSeconds <= 0) {
            this.luckyPillarEventIntervalSeconds = defaults.luckyPillarEventIntervalSeconds;
            changed = true;
        }
        if (this.luckyPillarOneHitSeconds <= 0) {
            this.luckyPillarOneHitSeconds = defaults.luckyPillarOneHitSeconds;
            changed = true;
        }
        if (this.luckyPillarTimeoutSeconds <= 0) {
            this.luckyPillarTimeoutSeconds = defaults.luckyPillarTimeoutSeconds;
            changed = true;
        }
        if (this.luckyPillarHeight <= 0) {
            this.luckyPillarHeight = defaults.luckyPillarHeight;
            changed = true;
        } else if (this.luckyPillarHeight == 20) {
            // 旧默认 20 改为新默认（柱高 40 格）
            this.luckyPillarHeight = defaults.luckyPillarHeight;
            changed = true;
        }
        if (this.luckyPillarGap <= 0) {
            this.luckyPillarGap = defaults.luckyPillarGap;
            changed = true;
        } else if (this.luckyPillarGap == 4) {
            // 旧默认 4 改为新默认（柱子之间距离拉远）
            this.luckyPillarGap = defaults.luckyPillarGap;
            changed = true;
        }
        if (this.luckyPillarPlatformGap <= 0) {
            this.luckyPillarPlatformGap = defaults.luckyPillarPlatformGap;
            changed = true;
        } else if (this.luckyPillarPlatformGap == 20) {
            // 旧默认 20 改为新默认（柱高 40 格，平台保持在地图中心高度）
            this.luckyPillarPlatformGap = defaults.luckyPillarPlatformGap;
            changed = true;
        }
        if (this.tntRunMinPlayers <= 0) {
            this.tntRunMinPlayers = defaults.tntRunMinPlayers;
            changed = true;
        }
        if (this.tntRunStartPlayers <= 0) {
            this.tntRunStartPlayers = defaults.tntRunStartPlayers;
            changed = true;
        }
        if (this.tntRunMaxPlayers <= 0) {
            this.tntRunMaxPlayers = defaults.tntRunMaxPlayers;
            changed = true;
        }
        if (this.tntRunCountdownSeconds <= 0) {
            this.tntRunCountdownSeconds = defaults.tntRunCountdownSeconds;
            changed = true;
        }
        if (this.tntRunFillTimeoutSeconds <= 0) {
            this.tntRunFillTimeoutSeconds = defaults.tntRunFillTimeoutSeconds;
            changed = true;
        }
        if (this.tntRunSize <= 0) {
            this.tntRunSize = defaults.tntRunSize;
            changed = true;
        }
        if (this.tntRunLayerCount <= 0) {
            this.tntRunLayerCount = defaults.tntRunLayerCount;
            changed = true;
        }
        if (this.tntRunLayerGap <= 0) {
            this.tntRunLayerGap = defaults.tntRunLayerGap;
            changed = true;
        } else if (this.tntRunLayerGap == 3) {
            // 旧默认 3 改为新默认（层距 6 格）
            this.tntRunLayerGap = defaults.tntRunLayerGap;
            changed = true;
        }
        if (this.tntRunVanishTicks <= 0) {
            this.tntRunVanishTicks = defaults.tntRunVanishTicks;
            changed = true;
        }
        if (this.tntRunDropIntervalTicks <= 0) {
            this.tntRunDropIntervalTicks = defaults.tntRunDropIntervalTicks;
            changed = true;
        }
        if (this.tntRunTimeoutSeconds <= 0) {
            this.tntRunTimeoutSeconds = defaults.tntRunTimeoutSeconds;
            changed = true;
        }
        if (this.heartbeatMinPlayers <= 0) {
            this.heartbeatMinPlayers = defaults.heartbeatMinPlayers;
            changed = true;
        }
        if (this.heartbeatStartPlayers <= 0) {
            this.heartbeatStartPlayers = defaults.heartbeatStartPlayers;
            changed = true;
        }
        if (this.heartbeatMaxPlayers <= 0) {
            this.heartbeatMaxPlayers = defaults.heartbeatMaxPlayers;
            changed = true;
        }
        if (this.heartbeatCountdownSeconds <= 0) {
            this.heartbeatCountdownSeconds = defaults.heartbeatCountdownSeconds;
            changed = true;
        }
        if (this.heartbeatFillTimeoutSeconds <= 0) {
            this.heartbeatFillTimeoutSeconds = defaults.heartbeatFillTimeoutSeconds;
            changed = true;
        }
        if (this.heartbeatSize <= 0) {
            this.heartbeatSize = defaults.heartbeatSize;
            changed = true;
        }
        if (this.heartbeatLevels <= 0) {
            this.heartbeatLevels = defaults.heartbeatLevels;
            changed = true;
        }
        if (this.heartbeatFloorGap <= 0) {
            this.heartbeatFloorGap = defaults.heartbeatFloorGap;
            changed = true;
        }
        if (this.heartbeatBaseFloors <= 0) {
            this.heartbeatBaseFloors = defaults.heartbeatBaseFloors;
            changed = true;
        }
        if (this.heartbeatTimeoutSeconds <= 0) {
            this.heartbeatTimeoutSeconds = defaults.heartbeatTimeoutSeconds;
            changed = true;
        }
        if (this.hotPotatoMinPlayers <= 0) {
            this.hotPotatoMinPlayers = defaults.hotPotatoMinPlayers;
            changed = true;
        }
        if (this.hotPotatoStartPlayers <= 0) {
            this.hotPotatoStartPlayers = defaults.hotPotatoStartPlayers;
            changed = true;
        }
        if (this.hotPotatoMaxPlayers <= 0) {
            this.hotPotatoMaxPlayers = defaults.hotPotatoMaxPlayers;
            changed = true;
        }
        if (this.hotPotatoCountdownSeconds <= 0) {
            this.hotPotatoCountdownSeconds = defaults.hotPotatoCountdownSeconds;
            changed = true;
        }
        if (this.hotPotatoFillTimeoutSeconds <= 0) {
            this.hotPotatoFillTimeoutSeconds = defaults.hotPotatoFillTimeoutSeconds;
            changed = true;
        }
        if (this.hotPotatoSize <= 0) {
            this.hotPotatoSize = defaults.hotPotatoSize;
            changed = true;
        }
        if (this.hotPotatoExplodeSeconds <= 0) {
            this.hotPotatoExplodeSeconds = defaults.hotPotatoExplodeSeconds;
            changed = true;
        }
        if (this.hotPotatoWarnSeconds <= 0) {
            this.hotPotatoWarnSeconds = defaults.hotPotatoWarnSeconds;
            changed = true;
        }
        if (this.hotPotatoRespawnSeconds <= 0) {
            this.hotPotatoRespawnSeconds = defaults.hotPotatoRespawnSeconds;
            changed = true;
        }
        if (this.hotPotatoHolderSpeed == null) {
            this.hotPotatoHolderSpeed = defaults.hotPotatoHolderSpeed;
            changed = true;
        }
        if (this.hotPotatoTimeoutSeconds <= 0) {
            this.hotPotatoTimeoutSeconds = defaults.hotPotatoTimeoutSeconds;
            changed = true;
        }
        return changed;
    }

    public static void save() {
        Path path = getConfigPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(INSTANCE));
        } catch (IOException e) {
            LOGGER.warn("[PvP] 无法保存配置 {}", path, e);
        }
    }

    public Block getFloorBlock() {
        return parseBlock(this.floorBlock, Blocks.POLISHED_DEEPSLATE);
    }

    public Block getWallBlock() {
        return parseBlock(this.wallBlock, Blocks.GLASS);
    }

    private static Block parseBlock(String id, Block fallback) {
        if (id == null) {
            return fallback;
        }
        Block block = Registries.BLOCK.get(Identifier.tryParse(id));
        return block == Blocks.AIR && !id.equals("minecraft:air") ? fallback : block;
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("pvp/config.json");
    }
}
