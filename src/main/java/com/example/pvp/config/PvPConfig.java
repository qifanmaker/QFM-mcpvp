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
    /** 中间主岛半径（整体扩大后默认 45 ≈ 30×1.5；spawnDist 随之外推，与其他岛 gap 不变）。 */
    public int skywarsMiddleRadius = 45;
    /** 出生岛边缘到中间主岛边缘的空隙（格）：越大出生岛离中间岛越远、越难偷袭。 */
    public int skywarsIslandGap = 40;
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
    /** 随机物品发放间隔（秒）：每隔该时长每名存活玩家获得 1 件随机物品。 */
    public int luckyPillarItemIntervalSeconds = 15;
    /** 随机事件间隔（秒）：每隔该时长触发一个随机事件。 */
    public int luckyPillarEventIntervalSeconds = 45;
    /** 是否开启随机事件（一击必杀/箭雨/雷击/TNT 雨/位置交换/补给潮）。 */
    public boolean luckyPillarEvents = true;
    /** 一击必杀事件持续时长（秒）。 */
    public int luckyPillarOneHitSeconds = 10;
    /** 对局超时（秒）：超过后击杀最多的存活者获胜，无人有击杀则平局。 */
    public int luckyPillarTimeoutSeconds = 600;
    /** 柱顶平台高于地图中心的高度（格）。 */
    public int luckyPillarHeight = 20;
    /** 柱顶平台半径（圆形，默认直径 7）。 */
    public int luckyPillarPlatformRadius = 3;
    /** 相邻平台边缘的间距（格）：越大越难跨柱，默认 4 格可冲刺跳过。 */
    public int luckyPillarGap = 4;
    /** 柱身向下延伸深度（格）：柱身底 = 地图中心 Y - 该值。 */
    public int luckyPillarColumnDepth = 40;

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
        }
        if (this.luckyPillarPlatformRadius <= 0) {
            this.luckyPillarPlatformRadius = defaults.luckyPillarPlatformRadius;
            changed = true;
        }
        if (this.luckyPillarGap <= 0) {
            this.luckyPillarGap = defaults.luckyPillarGap;
            changed = true;
        }
        if (this.luckyPillarColumnDepth <= 0) {
            this.luckyPillarColumnDepth = defaults.luckyPillarColumnDepth;
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
