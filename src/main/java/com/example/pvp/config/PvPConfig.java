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
    /** 竞技场内恶魂火焰弹爆炸击退横向倍率（1.0=原版；纵向见下）。 */
    public float fireballKnockbackHorizontal = 2.0f;
    /** 竞技场内恶魂火焰弹爆炸击退纵向倍率（1.0=原版）。 */
    public float fireballKnockbackVertical = 2.0f;
    /** 恶魂火焰弹爆炸威力（默认 1=原版恶魂；0=禁用爆炸破坏方块）。 */
    public int fireballExplosionPower = 1;

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
    /** 开赛多少秒后触发"物资刷新"事件：清空并重新塞满全图所有箱子（默认 180s=3 分钟）。 */
    public int skywarsRefillSeconds = 180;

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
    // ---------- 村庄保卫战 (Village Defense) ----------
    /** 最少/触发开赛倒计时/最多人数。可单人开；默认凑齐 2 人开赛。 */
    public int villageDefenseMinPlayers = 1;
    public int villageDefenseStartPlayers = 2;
    public int villageDefenseMaxPlayers = 8;
    /** 开赛倒计时（秒）；不足开赛人数时等待填充的最长时间（秒）。 */
    public int villageDefenseCountdownSeconds = 30;
    public int villageDefenseFillTimeoutSeconds = 60;
    /** 区域覆盖边长（生成/清理边界；地图从 maps/villagedefense/<name>/ 导入）。 */
    public int villageDefenseSize = 176;
    /** 对局超时（秒）：超过后按已到波次判定（合作局一般不会触发）。 */
    public int villageDefenseTimeoutSeconds = 1800;
    /** 要保护的村民数量（村民出生点不足时循环复用）。 */
    public int villageDefenseVillagers = 10;
    /** 胜利目标波次：对齐 VD config Limit.Wave.Game-End=200（Unlimited=false 时）。 */
    public int villageDefenseWinWave = 200;
    /** 波间冷却（秒）：每波打完后到下一波开始。 */
    public int villageDefenseWaveCooldownSeconds = 25;
    /** 开局发放的货币(orbs)。 */
    public int villageDefenseOrbsStart = 20;
    /** 单波同时在场僵尸数上限；超出部分折算为溢出僵尸加血。 */
    public int villageDefenseZombieCap = 75;
    /** 使用的村庄地图名（对应服务器根目录 maps/villagedefense/<name>/ 下的世界文件夹）。 */
    public String villageDefenseMap = "VD-Quarry";

    // ---------- 色盲派对 (Colorblind Party) ----------
    /** 最少/触发开赛倒计时/最多人数（对齐 Hypixel：最多 16 人）。 */
    public int colorblindMinPlayers = 2;
    public int colorblindStartPlayers = 4;
    public int colorblindMaxPlayers = 16;
    /** 开赛倒计时（秒）；不足开赛人数时等待填充的最长时间（秒）。 */
    public int colorblindCountdownSeconds = 30;
    public int colorblindFillTimeoutSeconds = 60;
    /** 对局超时（秒）：兜底用，正常会先跑完 25 回合。 */
    public int colorblindTimeoutSeconds = 900;
    /** 彩色地板边长（方形，默认 48）。 */
    public int colorblindSize = 48;
    /** 总回合数（Hypixel 为 25）。 */
    public int colorblindRounds = 25;
    /** 每回合从 16 色里随机抽几种铺地板（默认 8）。 */
    public int colorblindColorsPerRound = 8;
    /**
     * 回合时限整体缩放系数（默认 1.0 = 照抄 Hypixel 从 4.5s 递减到 0.5s 的表）。
     * 中文 Stroop 标题需要读字+辨色，末几回合 0.5s 实测很可能不公平，用它整体放宽。
     */
    public double colorblindTimeScale = 1.0;
    /** 地板上同时出现的加成信标数量上限。 */
    public int colorblindBeaconCount = 3;
    /** 每回合开始时刷出加成信标的概率（百分比，0~100）。 */
    public int colorblindBeaconChance = 35;
    /** Hyper 模式下每回合触发灾难事件的概率（百分比，0~100；第 1 回合永远是普通局）。 */
    public int colorblindHyperEventChance = 60;
    /** 开局 Normal/Hyper 投票时长（秒）。 */
    public int colorblindVoteSeconds = 10;
    /** 强制模式：auto（投票决定）/ normal / hyper。单人自测时可直接指定。 */
    public String colorblindForceMode = "auto";

    // ---------- 死斗 (Deathmatch) ----------
    /** 最少/触发开赛倒计时/最多人数。 */
    public int deathmatchMinPlayers = 2;
    public int deathmatchStartPlayers = 4;
    public int deathmatchMaxPlayers = 16;
    /** 开赛倒计时（秒）；不足开赛人数时等待填充的最长时间（秒）。 */
    public int deathmatchCountdownSeconds = 30;
    public int deathmatchFillTimeoutSeconds = 60;
    /** 单局时长（秒）：到时按人头结算。 */
    public int deathmatchDurationSeconds = 300;
    /** 场地边长（复用 FFA 平地竞技场布局：方形平台 + 四周围墙）。 */
    public int deathmatchSize = 64;
    /** 对局超时（秒）：兜底用，正常会先跑完 5 分钟。须大于单局时长。 */
    public int deathmatchTimeoutSeconds = 420;
    /** 助攻窗口（秒）：最后被某人伤害后多少秒内死亡，人头仍算给他。 */
    public int deathmatchAssistSeconds = 5;

    // ---------- 亦可赛艇 (Boat Race) ----------
    // 玩法：原版船 + 冰面 + 程序化随机生成的闭环赛道，多圈竞速。
    // 每场 Match 用独立随机 Seed 生成赛道（见 com.example.pvp.arena.race.RaceTrackGenerator），
    // Seed 会打进日志，出 Bug 时可以用同一个 Seed 复现同一张图。
    /** 最少/触发开赛倒计时/最多人数。 */
    public int boatRaceMinPlayers = 2;
    public int boatRaceStartPlayers = 4;
    public int boatRaceMaxPlayers = 8;
    /** 排队凑人倒计时（秒）：凑齐 startPlayers 后开始倒数。 */
    public int boatRaceQueueCountdownSeconds = 30;
    /** 不足开赛人数时等待填充的最长时间（秒），超时按当前人数开赛。 */
    public int boatRaceFillTimeoutSeconds = 60;
    /**
     * 开赛倒计时（秒）。地图生成被切成"暂存 → 分帧落盘"，铺图完成之前不会开始数秒，
     * 所以这 5 秒玩家一定已经站在起跑格位上了（船上，起跑线挡板后面）。
     */
    public int boatRaceCountdownSeconds = 5;
    /** 圈数（1~3 手感最好；每圈走同一条赛道）。 */
    public int boatRaceLaps = 3;
    /**
     * 赛道宽度（格，建议 9~15）。
     * 船在冰上速度极快、转向半径大，过窄会变成"撞墙比赛"，过宽则失去走线意义。
     */
    public int boatRaceTrackWidth = 12;
    /**
     * 赛道两侧的减速缓冲带宽度（格）：缓冲带用高摩擦方块（默认雪块 0.6），冲出赛道会被吃掉速度。
     * 原版船的"地面摩擦"取船底 1mm 切片 ±1 格内所有方块 slipperiness 的<b>平均值</b>，
     * 所以缓冲带紧贴冰面本身就构成"跑宽了就掉速"的天然惩罚，4~5 格足够。
     */
    public int boatRaceRunoffWidth = 5;
    /** 缓冲带外侧护栏高度（格）。船跳不过 2 格，所以 2 格足够，也省方块。 */
    public int boatRaceBarrierHeight = 2;
    // 赛道长度：生成器围绕 targetLength 造形，落在 [min,max] 之外即判非法并换 Seed 重来。
    // 上限受竞技场区域间距（ArenaTemplate.REGION_SPACING=384）限制，详见 RaceTrackValidator.MAX_BOUNDING_RADIUS。
    public int boatRaceMinTrackLength = 500;
    public int boatRaceMaxTrackLength = 820;
    public int boatRaceTargetTrackLength = 700;
    /** Checkpoint 数量；0 = 按赛道长度自动（约每 60 格一个，限制在 6~18）。 */
    public int boatRaceCheckpoints = 0;
    /** 最大生成尝试次数：每次失败后 seed+1 重来，直到出现合法赛道。 */
    public int boatRaceMaxGenerationAttempts = 24;
    /** 是否随机生成赛道。关掉则使用确定性的"安全椭圆"（调试用，玩家体验会差很多）。 */
    public boolean boatRaceEnableRandomTrack = true;
    /**
     * 中心线允许的最小曲率半径（格）。
     *
     * <p>由原版船物理反推：船的推力只有 0.04 格/tick²，速度方向能被扭转的最大速率给出
     * 最小可行路径半径 {@code R = v² / a}；反过来半径 R 的弯最多带 {@code v = 0.2·sqrt(R)} 格/tick。
     * 冰面（0.98）极速 2.0 格/tick（40 格/秒），所以：
     * <ul>
     *   <li>R=25 → 1.0 格/tick（20 格/秒）= 极速的一半，属于"必须刹车的慢弯"；</li>
     *   <li>R=100 → 2.0 格/tick = 可以全油门过的弯；</li>
     *   <li>R=330 → 蓝冰极速 3.64 格/tick 才过得去（本模式区域放不下，所以默认冰面而不是蓝冰）。</li>
     * </ul>
     * 低于该值的候选赛道会被校验器直接拒掉。
     */
    public double boatRaceMinCornerRadius = 25.0;
    /** 非相邻赛道段之间的最小净空（格）：防止赛道自贴/合并，等于消灭"看不清走哪条"的严重自交。 */
    public double boatRaceMinClearance = 26.0;
    /**
     * 冰面方块。packed_ice = 原版冰面滑度 0.98 → 极速 40 格/秒；
     * blue_ice = 0.989 → 极速 72.7 格/秒，但过弯半径需要 ~330 格，本模式的区域尺寸放不下
     * （会变成"一路撞墙"），所以默认用浮冰。想要蓝冰请同时大幅放长赛道并调大最小弯半径。
     */
    public String boatRaceSurfaceBlock = "minecraft:packed_ice";
    /** 缓冲带方块（高摩擦，冲出赛道时吃掉速度）。 */
    public String boatRaceRunoffBlock = "minecraft:snow_block";
    /** 护栏方块。 */
    public String boatRaceBarrierBlock = "minecraft:ice";
    /** 对局超时（秒）：兜底，防止卡死的对局永久占用场地。 */
    public int boatRaceTimeoutSeconds = 900;
    /**
     * 掉出赛道/船被毁后自动回位的冷却（秒）。
     * 回位后给玩家一点时间开出去，冷却期内不再判定"静止卡住"，避免在检查点原地反复传送。
     */
    public int boatRaceRecoveryCooldownSeconds = 3;
    /** 连续静止多少秒算"卡住"（船被毁/卡在护栏上）→ 回位。 */
    public int boatRaceStuckSeconds = 6;
    /** 距中心线多远算"离开赛道"（格，在赛道半宽 + 缓冲带之外再留一点余量）→ 回位。 */
    public int boatRaceOffTrackMargin = 3;
    /** 竞技场区域边长（生成/清理边界；必须 2*size/2 = size < REGION_SPACING 才不串场）。 */
    public int boatRaceSize = 336;

    // ---------- 亦可赛艇：氮气加速 ----------
    /**
     * 每隔多少秒补 1 个氮气（只发给比赛进行中、还没冲线的人）。
     * 默认 15 秒，配合下面的"手上最多 3 个"，一场 3 圈的比赛大约能用 6~8 次。
     */
    public int boatRaceNitroIntervalSeconds = 15;
    /** 手里最多囤几个氮气（防止前半段攒一堆、最后连喷）。 */
    public int boatRaceNitroMaxStack = 3;
    /** 一个氮气的加速持续时长（秒）。 */
    public int boatRaceNitroBoostSeconds = 3;
    /**
     * 氮气期间把船底冰面换成什么方块（倍率由方块滑度自动推算，不用手填）。
     *
     * <p><b>为什么只能"换方块"、不能"改船速"</b>（这版是实测结论，不是推测）：
     * 船是被玩家骑的载具，原版 {@code ServerPlayNetworkHandler.onVehicleMove} 每 tick 都
     * {@code updatePositionAndAngles(客户端上报坐标)} —— 船的位置/速度由<b>客户端权威</b>。
     * 服务端 {@code setVelocity} 的结果下一 tick 就被丢掉：实测给静止的船持续叠加推力，
     * 船速始终精确等于"刚加的那一点推力"（0.02 格/tick），既不会累积也推不动船；
     * 而客户端那侧 {@code onEntityVelocityUpdate -> setVelocityClient} 虽然无条件执行，
     * 也改不动本机正在驾驶的船。客户端唯一真正读的物理输入是<b>方块滑度</b>。
     *
     * <p>倍率由滑度反推：极速 = 推力 / (1 − 滑度)。
     * 浮冰 0.98 → 2.0 格/tick（40 格/秒）；蓝冰 0.989 → 3.64 格/tick（72.7 格/秒）→
     * <b>1.82 倍</b>。原版只有这两档，所以能做出来的倍率只有 1.0 与 1.82；
     * 想要任意倍率（例如 1.6）必须配客户端 Mod。
     */
    public String boatRaceNitroBlock = "minecraft:blue_ice";
    /**
     * 每张图必须有的"大直道"最小长度（格）。
     *
     * <p>不是评分项而是<b>硬性生成要求</b>：校验器会拒掉最长直道不够长的候选（换 seed 重试），
     * 生成器还会把起终点线钉在大直道头上，所以每张图的<b>发车直道就是大直道</b>。
     * 原因：氮气把极速抬到 72 格/秒，弯道极限却只有 20~36 格/秒 —— 没有直道这道具就是废的。
     *
     * <p><b>当前只能硬性保证 40 格</b>：现有极坐标傅里叶曲线实测的"最长直道"只有
     * min 40 / avg 83 / max 168 格，把要求往上抬就开始大量兜底成"正圆"（那种图根本没有直道）：
     *
     * <pre>
     *   要求 ≥40  → 兜底  0.0%      要求 ≥80  → 兜底 21.7%
     *   要求 ≥60  → 兜底  2.0%      要求 ≥90  → 兜底 64.0%
     *   要求 ≥70  → 兜底  6.7%      要求 ≥100 → 兜底 84.7%
     * </pre>
     *
     * <p>所以这里先取"能 100% 保证"的 40 格（≈ 冰面极速 1 秒、氮气 0.6 秒）。
     * 要真正做出 110~150 格的<b>大直道</b>，必须换赛道构建方式 ——
     * 极坐标曲线在数学上做不出长直线段，得改成"直线段 + 圆弧"拼接（见后续计划），
     * 而不是继续在这里调阈值。
     */
    public double boatRaceMinStraightLength = 40.0;

    // ---------- 起床战争 (Bed Wars) ----------
    /** 区域覆盖边长（生成/清理边界，需覆盖整张地图；Hypixel 图约 100 格）。 */
    public int bedWarsSize = 200;
    /** 每队铁生成器间隔（秒）。 */
    public int bedWarsIronInterval = 1;
    /** 每队金生成器间隔（秒）。 */
    public int bedWarsGoldInterval = 2;
    /** 铁生成器每次掉 2 个的概率（0~1，默认 0.35；与 3/4 个概率为累计阈值）。 */
    public double bedWarsIronExtraChance = 0.35;
    /** 铁生成器每次掉 3 个的概率（0~1，默认 0.15；须不大于掉 2 个概率）。 */
    public double bedWarsIronTripleChance = 0.15;
    /** 铁生成器每次掉 4 个的概率（0~1，默认 0.05；须不大于掉 3 个概率）。 */
    public double bedWarsIronQuadChance = 0.05;
    /** 金生成器每次额外多掉 1 个的概率（0~1，默认 0.25）。 */
    public double bedWarsGoldExtraChance = 0.25;
    /** 死亡后复活延迟（秒）。 */
    public int bedWarsRespawnSeconds = 5;
    /** 开局初始羊毛数量（每队玩家）。 */
    public int bedWarsStartWool = 16;
    /** 对局超时（秒）：超过后按存活队伍/床数判定。 */
    public int bedWarsTimeoutSeconds = 900;
    /** 开赛倒计时（秒，大厅等待）。 */
    public int bedWarsCountdownSeconds = 5;

    // ---------- 名字下方血量显示 ----------
    /** 总开关：关掉会把已有的血量标签实体全部拆掉。 */
    public boolean healthTagEnabled = true;
    /** 心形字符（U+2764 在部分资源包里可能不是红心，可换 ♥ U+2665）。 */
    public String healthTagHeart = "❤";
    /** 字号缩放，1.0 = 与原版玩家铭牌一致。 */
    public double healthTagScale = 1.0;
    /**
     * 在玩家碰撞箱顶部基础上的<b>世界竖直方向</b>偏移，正数往上、负数往下，单位格。
     *
     * <p>为什么需要它：显示实体的文字是<b>以下边缘为锚点往上涨</b>的，而原版铭牌是<b>以上边缘为锚点
     * 往下挂</b>的。头顶到铭牌文字下沿只有 0.275 格，而一行字本身就有 0.25 格高 —— 锚点重合时
    * 两者只差 1 个像素。默认偏移 0.275 格，将血量行放到原姓名高度。
     *
    * <p>标签使用竖直 billboard，因此偏移沿世界 Y 轴，不会随观察者俯仰变成屏幕方向的位移。
     */
    public double healthTagHeightOffset = 0.275;
    /** 文本刷新间隔（tick）：越大越省包；展示实体位置仍每 tick 同步。 */
    public int healthTagUpdateIntervalTicks = 2;
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
        // 兼容旧配置：新版本新增字段在旧 config.json 中缺失时用默认值补齐
        boolean migrated = INSTANCE.migrateOldConfig();
        // 无条件回写：Gson 是走构造函数建对象的，缺失的字段会保留字段初始值（不一定是 0），
        // migrateOldConfig 因此不一定能识别出"新增字段"。不写回的话，新版本新增的配置项
        // 永远不出现在 config.json 里，用户就没法照着文件改（例如 colorblindTimeScale）。
        // 回写是幂等的：文件内容 = 解析结果 + 补齐的默认值。
        if (migrated) {
            LOGGER.info("[PvP] 配置已补齐新字段并回写 {}", getConfigPath());
        }
        save();
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
        } else if (this.skywarsRefillSeconds == 300 || this.skywarsRefillSeconds == 240) {
            // 旧默认 300s(5 分钟)/240s(4 分钟) 改为新默认 180s(3 分钟)
            this.skywarsRefillSeconds = defaults.skywarsRefillSeconds;
            changed = true;
        }
        if (this.villageDefenseMinPlayers <= 0) {
            this.villageDefenseMinPlayers = defaults.villageDefenseMinPlayers;
            changed = true;
        }
        if (this.villageDefenseStartPlayers <= 0) {
            this.villageDefenseStartPlayers = defaults.villageDefenseStartPlayers;
            changed = true;
        }
        if (this.villageDefenseMaxPlayers <= 0) {
            this.villageDefenseMaxPlayers = defaults.villageDefenseMaxPlayers;
            changed = true;
        }
        if (this.villageDefenseCountdownSeconds <= 0) {
            this.villageDefenseCountdownSeconds = defaults.villageDefenseCountdownSeconds;
            changed = true;
        }
        if (this.villageDefenseFillTimeoutSeconds <= 0) {
            this.villageDefenseFillTimeoutSeconds = defaults.villageDefenseFillTimeoutSeconds;
            changed = true;
        }
        if (this.villageDefenseSize <= 0) {
            this.villageDefenseSize = defaults.villageDefenseSize;
            changed = true;
        }
        if (this.villageDefenseTimeoutSeconds <= 0) {
            this.villageDefenseTimeoutSeconds = defaults.villageDefenseTimeoutSeconds;
            changed = true;
        }
        if (this.villageDefenseVillagers <= 0) {
            this.villageDefenseVillagers = defaults.villageDefenseVillagers;
            changed = true;
        }
        if (this.villageDefenseWinWave <= 0) {
            this.villageDefenseWinWave = defaults.villageDefenseWinWave;
            changed = true;
        } else if (this.villageDefenseWinWave == 25) {
            // 旧默认 25 改为对齐 VD Game-End=200
            this.villageDefenseWinWave = defaults.villageDefenseWinWave;
            changed = true;
        }
        if (this.villageDefenseWaveCooldownSeconds <= 0) {
            this.villageDefenseWaveCooldownSeconds = defaults.villageDefenseWaveCooldownSeconds;
            changed = true;
        }
        if (this.villageDefenseOrbsStart < 0) {
            this.villageDefenseOrbsStart = defaults.villageDefenseOrbsStart;
            changed = true;
        }
        if (this.villageDefenseZombieCap <= 0) {
            this.villageDefenseZombieCap = defaults.villageDefenseZombieCap;
            changed = true;
        }
        if (this.colorblindMinPlayers <= 0) {
            this.colorblindMinPlayers = defaults.colorblindMinPlayers;
            changed = true;
        }
        if (this.colorblindStartPlayers <= 0) {
            this.colorblindStartPlayers = defaults.colorblindStartPlayers;
            changed = true;
        }
        if (this.colorblindMaxPlayers <= 0) {
            this.colorblindMaxPlayers = defaults.colorblindMaxPlayers;
            changed = true;
        }
        if (this.colorblindCountdownSeconds <= 0) {
            this.colorblindCountdownSeconds = defaults.colorblindCountdownSeconds;
            changed = true;
        }
        if (this.colorblindFillTimeoutSeconds <= 0) {
            this.colorblindFillTimeoutSeconds = defaults.colorblindFillTimeoutSeconds;
            changed = true;
        }
        if (this.colorblindTimeoutSeconds <= 0) {
            this.colorblindTimeoutSeconds = defaults.colorblindTimeoutSeconds;
            changed = true;
        }
        if (this.colorblindSize <= 0) {
            this.colorblindSize = defaults.colorblindSize;
            changed = true;
        }
        if (this.colorblindRounds <= 0) {
            this.colorblindRounds = defaults.colorblindRounds;
            changed = true;
        }
        if (this.colorblindColorsPerRound <= 0) {
            this.colorblindColorsPerRound = defaults.colorblindColorsPerRound;
            changed = true;
        }
        if (this.colorblindTimeScale <= 0) {
            this.colorblindTimeScale = defaults.colorblindTimeScale;
            changed = true;
        }
        if (this.colorblindBeaconCount <= 0) {
            this.colorblindBeaconCount = defaults.colorblindBeaconCount;
            changed = true;
        }
        if (this.colorblindBeaconChance < 0) {
            this.colorblindBeaconChance = defaults.colorblindBeaconChance;
            changed = true;
        }
        if (this.colorblindHyperEventChance < 0) {
            this.colorblindHyperEventChance = defaults.colorblindHyperEventChance;
            changed = true;
        }
        if (this.colorblindVoteSeconds <= 0) {
            this.colorblindVoteSeconds = defaults.colorblindVoteSeconds;
            changed = true;
        }
        if (this.colorblindForceMode == null || this.colorblindForceMode.isBlank()) {
            this.colorblindForceMode = defaults.colorblindForceMode;
            changed = true;
        }
        if (this.deathmatchMinPlayers <= 0) {
            this.deathmatchMinPlayers = defaults.deathmatchMinPlayers;
            changed = true;
        }
        if (this.deathmatchStartPlayers <= 0) {
            this.deathmatchStartPlayers = defaults.deathmatchStartPlayers;
            changed = true;
        }
        if (this.deathmatchMaxPlayers <= 0) {
            this.deathmatchMaxPlayers = defaults.deathmatchMaxPlayers;
            changed = true;
        }
        if (this.deathmatchCountdownSeconds <= 0) {
            this.deathmatchCountdownSeconds = defaults.deathmatchCountdownSeconds;
            changed = true;
        }
        if (this.deathmatchFillTimeoutSeconds <= 0) {
            this.deathmatchFillTimeoutSeconds = defaults.deathmatchFillTimeoutSeconds;
            changed = true;
        }
        if (this.deathmatchDurationSeconds <= 0) {
            this.deathmatchDurationSeconds = defaults.deathmatchDurationSeconds;
            changed = true;
        }
        if (this.deathmatchSize <= 0) {
            this.deathmatchSize = defaults.deathmatchSize;
            changed = true;
        }
        if (this.deathmatchTimeoutSeconds <= 0) {
            this.deathmatchTimeoutSeconds = defaults.deathmatchTimeoutSeconds;
            changed = true;
        }
        if (this.deathmatchAssistSeconds <= 0) {
            this.deathmatchAssistSeconds = defaults.deathmatchAssistSeconds;
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
        if (this.healthTagHeart == null || this.healthTagHeart.isBlank()) {
            this.healthTagHeart = defaults.healthTagHeart;
            changed = true;
        }
        if (this.healthTagScale <= 0.0) {
            this.healthTagScale = defaults.healthTagScale;
            changed = true;
        }
        // 迁移旧版默认值；0.275 是当前默认值，其他值保留为用户自定义偏移。
        if (this.healthTagHeightOffset == -0.1 || this.healthTagHeightOffset == 0.0
            || this.healthTagHeightOffset == 0.2) {
            this.healthTagHeightOffset = defaults.healthTagHeightOffset;
            changed = true;
        }
        if (this.healthTagUpdateIntervalTicks <= 0) {
            this.healthTagUpdateIntervalTicks = defaults.healthTagUpdateIntervalTicks;
            changed = true;
        }
        // ---------- 亦可赛艇 (Boat Race) ----------
        if (this.boatRaceMinPlayers <= 0) {
            this.boatRaceMinPlayers = defaults.boatRaceMinPlayers;
            changed = true;
        }
        if (this.boatRaceStartPlayers <= 0) {
            this.boatRaceStartPlayers = defaults.boatRaceStartPlayers;
            changed = true;
        }
        if (this.boatRaceMaxPlayers <= 0) {
            this.boatRaceMaxPlayers = defaults.boatRaceMaxPlayers;
            changed = true;
        }
        if (this.boatRaceQueueCountdownSeconds <= 0) {
            this.boatRaceQueueCountdownSeconds = defaults.boatRaceQueueCountdownSeconds;
            changed = true;
        }
        if (this.boatRaceFillTimeoutSeconds <= 0) {
            this.boatRaceFillTimeoutSeconds = defaults.boatRaceFillTimeoutSeconds;
            changed = true;
        }
        if (this.boatRaceCountdownSeconds <= 0) {
            this.boatRaceCountdownSeconds = defaults.boatRaceCountdownSeconds;
            changed = true;
        }
        if (this.boatRaceLaps <= 0) {
            this.boatRaceLaps = defaults.boatRaceLaps;
            changed = true;
        }
        if (this.boatRaceTrackWidth <= 0) {
            this.boatRaceTrackWidth = defaults.boatRaceTrackWidth;
            changed = true;
        }
        if (this.boatRaceRunoffWidth <= 0) {
            this.boatRaceRunoffWidth = defaults.boatRaceRunoffWidth;
            changed = true;
        }
        if (this.boatRaceBarrierHeight <= 0) {
            this.boatRaceBarrierHeight = defaults.boatRaceBarrierHeight;
            changed = true;
        }
        if (this.boatRaceMinTrackLength <= 0) {
            this.boatRaceMinTrackLength = defaults.boatRaceMinTrackLength;
            changed = true;
        }
        if (this.boatRaceMaxTrackLength <= 0) {
            this.boatRaceMaxTrackLength = defaults.boatRaceMaxTrackLength;
            changed = true;
        }
        if (this.boatRaceTargetTrackLength <= 0) {
            this.boatRaceTargetTrackLength = defaults.boatRaceTargetTrackLength;
            changed = true;
        }
        if (this.boatRaceCheckpoints < 0) {
            this.boatRaceCheckpoints = defaults.boatRaceCheckpoints;
            changed = true;
        }
        if (this.boatRaceMaxGenerationAttempts <= 0) {
            this.boatRaceMaxGenerationAttempts = defaults.boatRaceMaxGenerationAttempts;
            changed = true;
        }
        if (this.boatRaceMinCornerRadius <= 0) {
            this.boatRaceMinCornerRadius = defaults.boatRaceMinCornerRadius;
            changed = true;
        }
        if (this.boatRaceMinClearance <= 0) {
            this.boatRaceMinClearance = defaults.boatRaceMinClearance;
            changed = true;
        }
        if (this.boatRaceSurfaceBlock == null || this.boatRaceSurfaceBlock.isBlank()) {
            this.boatRaceSurfaceBlock = defaults.boatRaceSurfaceBlock;
            changed = true;
        }
        if (this.boatRaceRunoffBlock == null || this.boatRaceRunoffBlock.isBlank()) {
            this.boatRaceRunoffBlock = defaults.boatRaceRunoffBlock;
            changed = true;
        }
        if (this.boatRaceBarrierBlock == null || this.boatRaceBarrierBlock.isBlank()) {
            this.boatRaceBarrierBlock = defaults.boatRaceBarrierBlock;
            changed = true;
        }
        if (this.boatRaceTimeoutSeconds <= 0) {
            this.boatRaceTimeoutSeconds = defaults.boatRaceTimeoutSeconds;
            changed = true;
        }
        if (this.boatRaceRecoveryCooldownSeconds <= 0) {
            this.boatRaceRecoveryCooldownSeconds = defaults.boatRaceRecoveryCooldownSeconds;
            changed = true;
        }
        if (this.boatRaceStuckSeconds <= 0) {
            this.boatRaceStuckSeconds = defaults.boatRaceStuckSeconds;
            changed = true;
        }
        if (this.boatRaceOffTrackMargin <= 0) {
            this.boatRaceOffTrackMargin = defaults.boatRaceOffTrackMargin;
            changed = true;
        }
        if (this.boatRaceSize <= 0) {
            this.boatRaceSize = defaults.boatRaceSize;
            changed = true;
        }
        if (this.boatRaceNitroIntervalSeconds <= 0) {
            this.boatRaceNitroIntervalSeconds = defaults.boatRaceNitroIntervalSeconds;
            changed = true;
        }
        if (this.boatRaceNitroMaxStack <= 0) {
            this.boatRaceNitroMaxStack = defaults.boatRaceNitroMaxStack;
            changed = true;
        }
        if (this.boatRaceNitroBoostSeconds <= 0) {
            this.boatRaceNitroBoostSeconds = defaults.boatRaceNitroBoostSeconds;
            changed = true;
        }
        if (this.boatRaceMinStraightLength <= 0) {
            this.boatRaceMinStraightLength = defaults.boatRaceMinStraightLength;
            changed = true;
        }
        if (this.boatRaceNitroBlock == null || this.boatRaceNitroBlock.isBlank()) {
            this.boatRaceNitroBlock = defaults.boatRaceNitroBlock;
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

    /** 亦可赛艇：冰面方块（默认浮冰）。 */
    public Block getBoatRaceSurfaceBlock() {
        return parseBlock(this.boatRaceSurfaceBlock, Blocks.PACKED_ICE);
    }

    /** 亦可赛艇：缓冲带方块（默认雪块，高摩擦）。 */
    public Block getBoatRaceRunoffBlock() {
        return parseBlock(this.boatRaceRunoffBlock, Blocks.SNOW_BLOCK);
    }

    /** 亦可赛艇：护栏方块（默认冰）。 */
    public Block getBoatRaceBarrierBlock() {
        return parseBlock(this.boatRaceBarrierBlock, Blocks.ICE);
    }

    /** 亦可赛艇：氮气方块（默认蓝冰）。 */
    public Block getBoatRaceNitroBlock() {
        return parseBlock(this.boatRaceNitroBlock, Blocks.BLUE_ICE);
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
