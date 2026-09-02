# PvP 匹配 Mod（MC 1.21.1 · Fabric 服务端）

一个纯服务端的 PvP 匹配 Mod：支持 **1v1 / 2v2 / 自由乱斗(FFA) / 相扑(Sumo) / 1.8 经典PvP / 空岛战争(SkyWars) / 战桥(Bridge) / 幸运之柱(Lucky Pillar) / TNT 跑酷(TNT Run) / 心跳水立方(Heartbeat) / 烫手山芋(Hot Potato) / 起床战争(Bed Wars)** 共 12 种模式，支持**队列自动匹配**与**决斗挑战**，可自选**装备套件**（剑战 / 弓箭 / 全装备 / NoDebuff / 自定义 JSON 配置等）。每场比赛在**专用虚空竞技场维度**中生成独立地形，结束后销毁重建。

- 原版客户端即可加入，**无需安装客户端 Mod**
- **游戏内 GUI 菜单操作**（无需记指令）：进服自动发一把「PvP 竞技场」指南针，右键打开菜单
- 进服自动显示「PvP 匹配 Mod vX.X.X」版本号，方便确认是否最新
- 所有消息为中文
- 战绩（胜/负/场次）持久化保存

---

## 游戏模式

### PvP 对战（自选套件）
- **1v1 / 2v2 / FFA / 相扑 / 1.8 经典PvP**，选择套件后加入队列
- **自由乱斗**：凑齐 3 人开始 60 秒倒计时，期间达到 6 人自动加速到 10 秒内开赛（人数不限，每人用自己的套件）
- **相扑**：不吃伤害只吃击退，落到平台下方 20 格淘汰（套件带一组末影珍珠可救回），最后一个留在场上的人获胜
- **1.8 经典PvP**：模拟 1.8.9 手感——**无攻击冷却**（疯狂点按打满伤害）、剑**格挡减伤 50%**（右键格挡、攻击解除），配速度 II

### 空岛战争 (SkyWars)
每人随机一座出生空岛（3 箱），与**中岛群**（中央主岛 + 内外两环卫星岛共约 13 座，各岛配石柱/蜘蛛网/水池/矮墙等掩体）之间有一座**中途岛**作搭桥跳板；各岛间有 ±3 格高低差。**无套件、生存模式开箱搜装备**，采用 **1.8 低版本战斗**（无攻击冷却、剑可格挡、投掷物不继承投掷者速度）。
- 武器/护甲随机附魔（最高 II 级、击退最高 1 级）；**极低概率**开出鞘翅+烟花、秒人斧（锋利 666 金斧·耐久 1）、附魔金苹果、不死图腾（掉虚空自动救回中岛）；**追踪罗盘**指向最近敌人
- 每场随机抽取 **主世界 / 地狱 / 冰原 / 末地** 主题（地狱刷灵魂沙与岩浆、冰原全是冰、末地中岛为空心环）
- 开赛 **3 分钟后"物资刷新"**重置全图箱子；**3 分钟后缩圈**逼团（安全半径不小于主岛），最后幸存者获胜
- 凑齐 4 人开赛（最少 2 人、最多 8 人）

### 战桥 (The Bridge)
双基地隔虚空相望，中央一座 1 格宽桥，跳进对方基地中央的**球门洞**得 1 分，**先得 5 分获胜**；死亡立即原地重生，每个进球后全员回笼位 5 秒再放出，**玩家搭的方块不重置**（只能拆别人搭的，地图本体受保护）。配铁剑 / 弓+1 支箭（每 4 秒回 1 支）/ 效率 II 钻石镐 / 128 个队伍色陶瓦 / 8 金苹果 / 队伍色皮革甲。采用 1.8 低版本战斗。四种模式：**1v1 / 1v1v1v1 / 2v2 / 混战**（偶数人数平均分两队，2v2~4v4）。

### 趣味小游戏（空手开局）
- **幸运之柱 (Lucky Pillar)**：出生在 1 格宽、40 格高的基岩柱顶（间距 8 格需搭桥跨柱），柱顶下 40 格有一整圈"安全平台"（掉出平台下方 20 格才淘汰），**每局平台方块随机 10 种风格**（岩浆块/岩浆炼药锅/雪块细雪/蜘蛛网/沙子仙人掌/树叶/活版门/半砖/粘液蜂蜜/纯虚空），平台方块可自由破坏；**每 3 秒**全员随机获得 1 个物品（全物品纯随机），每 **45 秒**触发随机事件（一击必杀/箭雨/雷击/TNT 雨/位置交换/补给潮）；最后幸存者获胜
- **TNT 跑酷 (TNT Run)**：5 层彩色羊毛平台叠放（层距 6 格），**踩过的方块 0.25 秒后消失**（不会爆炸，只能靠踩落）；地面随机刷新火焰弹与 TNT 掉落物（捡起来砸人/炸人）；掉出底层淘汰，最后幸存者获胜
- **心跳水立方 (Heartbeat)**：高空出发台往下跳，5 层"心跳"棋盘格地板周期性**出现 3 秒 → 消失 1.5 秒**（配心跳音效与心形粒子），卡节奏逐层下落；底部 5 个圆形水坑，**落进水坑即到达、掉出平台边缘淘汰**；按到达顺序排名，第一个到达者获胜
- **烫手山芋 (Hot Potato)**：圆形石砖平台+玻璃围墙+随机障碍物；唯一一颗山芋开局随机发放，**固定寿命（默认 20 秒）**，**左键攻击其他玩家传递**（不刷新倒计时），时间到爆炸淘汰持有者（纯特效无破坏）；传递冷却 0.2 秒，持有者获得速度 I；最后幸存者获胜

以上小游戏均为凑齐 4 人开赛（最少 2 人、最多 8 人）。

### 起床战争 (Bed Wars)
加载 **Hypixel Solo-Doubles 风格 8 人地图**（1.21.1 世界存档，放在 `config/pvp/bedwars/maps/<地图名>/`，自动探测 8 队床位置）；**Solo（每队 1 人，最多 8 队）/ 双人（每队 2 人，最多 16 人）**两种模式。开局在等待大厅倒计时，然后传送到各自队伍岛；**摧毁敌方床**（该队无法复活）并**淘汰所有人**获胜。
- **有床死亡延迟 5 秒复活**（旁观者回本队岛等待+标题倒计时，复活后抗性 V 保护；等待期间床被摧毁则直接淘汰）、无床淘汰变幽灵；**本队床被摧毁时全队标题警告**
- **每队铁（1 秒/次，概率一次掉 2~4 个）/金（2 秒/次，概率多掉 1 个）生成器**定时刷资源；中央岛刷钻石/绿宝石
- **右击商店实体购买**：村民=道具商店（分类标签页），僵尸=团队升级（按序逐级购买）
- 地图本体不可破坏（床除外），可自由搭方块；采用 1.8 低版本战斗；凑 2 人即开赛（按人数动态启用队伍）

---

## 服务端安装

1. 准备一个 **Fabric Loader** 的 1.21.1 服务端：到 [fabricmc.net](https://fabricmc.net/use/server/) 下载并运行 `fabric-server-launch.jar`（选择 1.21.1），生成 `mods/` 目录。
2. 将本 Mod 的 jar 放到服务端 `mods/` 目录，同时放一份 **Fabric API**（`fabric-api-0.116.x+1.21.1.jar`，从 [Modrinth](https://modrinth.com/mod/fabric-api) 或 CurseForge 下载，版本匹配 1.21.1）。
3. 启动服务端。首次启动会在 `config/pvp/` 生成 `config.json` 和 `kits.json`（默认配置）。
4. 启动日志出现 `[PvP] 竞技场世界已创建` 即表示正常。

> 竞技场使用独立的虚空维度 `pvp:arena`，运行时创建、不写入存档，每次重启都是全新虚空。
>
> **战斗机制**：饱食度按原版规则消耗（战斗/冲刺会饿），**吃东西可以回血**（套件自带熟牛肉）。

---

## 游戏内 GUI

进服后快捷栏自动发放 **§6PvP 竞技场** 指南针（第 9 格优先），**右键**打开菜单；也可输入 `/pvp` 或 `/pvp menu`。

主菜单：
- 🗡 **PvP 对战** → 子菜单：**1v1 / 2v2 / FFA / 相扑 / 1.8 经典PvP** → 选择套件即加入队列
- 🏝 **空岛战争 (Beta)** → 无套件，点击直接加入队列
- 🧱 **战桥** → 子菜单：**1v1 / 1v1v1v1 / 2v2 / 混战**（装备固定），点击直接加入队列
- 🎮 **趣味小游戏** → 子菜单：**幸运之柱 / TNT 跑酷 / 心跳水立方 / 烫手山芋**（空手开局），点击直接加入队列
- 🛏 **起床战争** → 子菜单：**Solo（每队 1 人）/ 双人（每队 2 人）**，点击直接加入队列
- 🤺 **向玩家发起决斗** → 选择在线玩家 → 选择套件 → 发送决斗邀请
- 📊 **我的战绩**、📦 **查看套件列表**
- 🚪 **离开队列**（排队中才显示）

> 菜单为服务端实现的原版容器界面（箱子），原版客户端直接可用。
>
> **排队时**快捷栏会出现红色「§c离开排队§r」红石粉，右键即可退出排队（开赛时自动移除）。
>
> **OP 立即开赛**：排队中打开主菜单，OP(2 级+) 会看到「§a立即开始」按钮（或用 `/pvp start`），可跳过倒计时直接以当前队列人数开赛。
>
> **大厅保护**：不在对局中的玩家为冒险模式、无敌、饱食度不掉（可在配置中关闭）。

---

## 命令

| 命令 | 说明 |
|---|---|
| `/pvp` 或 `/pvp menu` | 打开游戏内 GUI 菜单 |
| `/pvp join <1v1\|2v2\|ffa\|sumo\|1.8> <套件>` | 加入匹配队列 |
| `/pvp join skywars` | 加入空岛战争（无需套件） |
| `/pvp join bridge1v1\|bridge1v1v1v1\|bridge2v2\|bridge` | 加入战桥（`bridge`=混战，需偶数人数） |
| `/pvp join luckypillar` | 加入幸运之柱 |
| `/pvp join tntrun` | 加入 TNT 跑酷 |
| `/pvp join heartbeat` | 加入心跳水立方 |
| `/pvp join hotpotato` | 加入烫手山芋 |
| `/pvp join bedwars\|bedwars2` | 加入起床战争（Solo/双人） |
| `/pvp leave` | 离开队列 |
| `/pvp tpout` | 从竞技场返回主城（幽灵旁观离开；活跃玩家视为弃权） |
| `/pvp tpin` | 从主城进入竞技场（有对局回到对局，无对局访客观看） |
| `/hub` | 返回主城（同 `/pvp tpout`） |
| `/watch` | 进入竞技场（同 `/pvp tpin`） |
| `/pvp start [参数]` | OP 专用：立即用当前队列人数开赛；空岛可指定主题（`主世界`/`地狱`/`冰原`/`末地`），床战可指定地图名 |
| `/pvp queue` | 查看自己排队状态 |
| `/pvp list` | 查看进行中的比赛与队列 |
| `/pvp stats [玩家]` | 查看战绩 |
| `/pvp top` | 胜场排行榜 |
| `/pvp kit list` | 查看可用套件 |
| `/pvp reload` | 重载配置（OP 2 级） |
| `/pvp debug skywars [轮数]` | 生成随机空岛并传送查看（OP 2，测试用） |
| `/pvp debug skywars theme <主题>` | 生成指定主题的空岛 |
| `/pvp debug skywars all` | 4 种主题各生成一张（区域 900~903） |
| `/pvp debug luckypillar [柱子数]` | 生成幸运之柱地图并传送查看（OP 2） |
| `/pvp debug tntrun` | 生成 TNT 跑酷地图并传送查看（OP 2） |
| `/pvp debug heartbeat` | 生成心跳水立方地图并传送查看（OP 2） |
| `/pvp debug hotpotato` | 生成烫手山芋地图并传送查看（OP 2） |
| `/pvp debug bedwars` | 加载第一张床战地图并传送到上空查看（OP 2） |
| `/pvp bedwars edit [地图名]` | 床战地图标记模式（OP 2） |
| `/pvp bedwars save` | 保存标记并退出编辑 |
| `/pvp bedwars cancel` | 取消标记并退出编辑 |
| `/duel <玩家> [套件]` | 向指定玩家发起 1v1 决斗 |
| `/duel accept [挑战者]` | 接受决斗 |
| `/duel deny [挑战者]` | 拒绝决斗 |

内置套件 ID：`sword`（剑战）、`bow`（弓箭）、`full_gear`（全装备）、`iron_pvp`（铁套PVP）、`sumo`（相扑击退棍）、`no_debuff`（NoDebuff 药水PvP）、`gapple`（金苹果）、`axe`（斧战）、`legacy_1_8`（1.8 经典：钻石剑+速度II+金苹果），以及 `kits.json` 中自定义的套件（用其 `name` 字段调用）。**空岛战争/战桥/趣味小游戏/起床战争不需要套件**。

---

## 床战地图标记

Bed Wars 地图的商店 / 铁金生成点 / 钻石绿宝石生成点需要精确标注，用游戏内标记模式操作（OP 2 级）：

1. `/pvp bedwars edit <地图名>`：加载地图到竞技场，传送到地图上空（创造+飞行），快捷栏发放标记物品
2. 用快捷栏物品标记（**左键标记，右键取消**；标记点放在点击方块的上方一格，用粒子+方块可视化）：
   - 🟢 木棍 = 普通商店
   - 🟣 烈焰棒 = 团队升级商店
   - ⬜ 铁锭 = 铁生成点
   - 🟡 金锭 = 金生成点
   - 🔵 钻石 = 中央岛钻石生成点
   - 🟢 绿宝石 = 中央岛绿宝石生成点
   - 彩色羊毛 = 标记该队颜色（点击床标记）
3. **纸**（快捷栏第 9 格）右键 = 保存并退出（或 `/pvp bedwars save`）

> **手动标记所有点位**——每队都要标记普通商店/升级商店/铁/金点（队伍性质点保存时自动认领最近的床，顺序按床角度排序）；钻石/绿宝石是全局点，标几个存几个。
> 保存后生成 `map.json`，正式对局自动读取精确点位；队伍颜色优先读配置，无配置时按队伍索引分配（红蓝黄绿青白粉黑）。

### 商店
- **道具商店**：每队一个**村民**实体，右击打开；顶部 7 个**分类标签页**（方块 / 近战武器 / 盔甲 / 工具 / 远程 / 药水 / 实用道具），点击即时切换，用铁/金/绿宝石购买
- **团队升级**：每队一个**僵尸**实体，右击打开；每种升级占一格、配**专属图标**（锋利=钻石剑、保护=铁胸甲、疯狂矿工=金镐、锻炉=熔炉、治愈池=信标、末影龙增益=龙首、陷阱=绊线钩），**按等级顺序逐级购买**（钻石），界面实时显示当前等级与下一级价格，已购带附魔光效、满级无法再买：

| 升级 | 各级钻石价 | 效果 |
|---|---|---|
| 锋利之剑 I~IV | 4→8→16→24 | 全队剑附加锋利 |
| 保护 I~IV | 2→4→8→16 | 全队盔甲保护 |
| 疯狂矿工 I~II | 2→4 | 全队急迫 |
| 锻炉 I~IV | 2→4→6→8 | 资源生成器每次额外多掉 N 份 |
| 治愈池 | 1 | 基地床附近持续回血 |
| 末影龙增益 | 5 | 全队短暂力量+抗性 |
| 陷阱 | 1 | 敌人进入基地触发警报与减速 |

---

## 配置文件

### `config/pvp/config.json`

```jsonc
{
  "ffaMinPlayers": 3,           // 自由乱斗凑齐人数开始倒计时
  "ffaCountdownSeconds": 60,    // 自由乱斗开赛倒计时（秒）
  "ffaEarlyStartPlayers": 6,    // 达到该人数倒计时加速
  "ffaEarlyStartSeconds": 10,   // 加速后的最短倒计时（秒）
  "ffaMaxPlayers": 16,          // 自由乱斗人数上限
  "countdownSeconds": 5,        // 开战倒计时（秒）
  "maxConcurrentMatches": 4,    // 同时进行的最大场数
  "duelExpirySeconds": 30,      // 决斗挑战过期时间（秒）
  "lobbyProtection": true,      // 大厅保护：冒险模式 + 无敌 + 饱食度不掉
  "matchTimeoutSeconds": 600,   // 对局超时（秒），超时强制平局结束
  "floorBlock": "minecraft:polished_deepslate",  // 平台地板方块
  "wallBlock": "minecraft:glass",                // 平台围墙方块
  "duel1v1Size": 51,            // 1v1 平台边长
  "duel2v2Size": 71,            // 2v2 平台边长
  "ffaSize": 101,               // FFA 平台边长
  "sumoSize": 11,               // 相扑平台边长

  // ---- 空岛战争 (SkyWars) ----
  "skywarsMinPlayers": 2,       // 最少人数（2 人即可开）
  "skywarsStartPlayers": 4,     // 凑齐该人数开始开赛倒计时
  "skywarsMaxPlayers": 8,       // 最多人数，达到立即开赛
  "skywarsCountdownSeconds": 30,// 开赛倒计时（秒）
  "skywarsFillTimeoutSeconds": 60, // 人数不足时的最长等待填人时间（秒）
  "skywarsSize": 176,           // 地图覆盖边长（生成/清理边界）
  "skywarsIslandRadius": 5,     // 出生岛半径
  "skywarsMiddleRadius": 80,    // 中岛群覆盖半径（中央主岛+卫星岛）
  "skywarsIslandGap": 50,       // 出生岛到中岛群的空隙（格），越大越远、越难偷袭
  "skywarsChestsPerIsland": 3,  // 每座出生岛箱子数
  "skywarsMiddleChests": 10,    // 中岛群箱子总数
  "skywarsMidIslandChests": 3,  // 每座中途岛的箱子数
  "skywarsTimeoutSeconds": 600, // 空岛对局超时（秒）
  "skywarsShrinkStartSeconds": 180, // 开赛后多少秒开始缩圈
  "skywarsShrinkIntervalSeconds": 30, // 每圈间隔（秒）
  "skywarsShrinkBlocksPerStage": 4,   // 每圈塌掉几格
  "skywarsShrinkMinRadius": 8,  // 最小安全半径
  "skywarsRefillSeconds": 180,  // 开赛多少秒后触发"物资刷新"事件（重置全图箱子）

  // ---- 战桥 (Bridge) ----
  "bridgeSize": 101,            // 区域覆盖边长（生成/清理边界）
  "bridgeBaseRadius": 6,        // 基地半宽（基地边长 13）
  "bridgeGap": 35,              // 两基地内沿的虚空间隔（搭桥区），越大桥越长
  "bridgeWinScore": 5,          // 先得 X 分获胜
  "bridgeArrowRegenSeconds": 4, // 箭矢回复间隔（秒）
  "bridgeTeamMinPlayers": 4,    // 混战最少人数（需偶数，总人数/2 分两队）
  "bridgeTimeoutSeconds": 300,  // 对局超时（秒），超时比分高者胜

  // ---- 幸运之柱 (Lucky Pillar) ----
  "luckyPillarMinPlayers": 2,       // 最少人数
  "luckyPillarStartPlayers": 4,     // 凑齐该人数开始开赛倒计时
  "luckyPillarMaxPlayers": 8,       // 最多人数，达到立即开赛
  "luckyPillarCountdownSeconds": 30,// 开赛倒计时（秒）
  "luckyPillarFillTimeoutSeconds": 60, // 人数不足时的最长等待填人时间（秒）
  "luckyPillarSize": 101,           // 地图覆盖边长
  "luckyPillarItemIntervalSeconds": 3,  // 随机物品发放间隔（秒）
  "luckyPillarEventIntervalSeconds": 45, // 随机事件间隔（秒）
  "luckyPillarEvents": true,        // 是否开启随机事件
  "luckyPillarOneHitSeconds": 10,   // 一击必杀事件持续时长（秒）
  "luckyPillarTimeoutSeconds": 600, // 对局超时（秒），超时击杀最多者胜，无击杀平局
  "luckyPillarHeight": 40,          // 柱顶高度（高于地图中心）
  "luckyPillarGap": 8,              // 相邻柱子的间隙（格）
  "luckyPillarPlatformGap": 40,     // 柱顶下方多少格有一圈大平台（掉出平台下方 20 格死亡）

  // ---- TNT 跑酷 (TNT Run) ----
  "tntRunMinPlayers": 2,            // 最少人数
  "tntRunStartPlayers": 4,          // 凑齐该人数开始开赛倒计时
  "tntRunMaxPlayers": 8,            // 最多人数，达到立即开赛
  "tntRunCountdownSeconds": 30,     // 开赛倒计时（秒）
  "tntRunFillTimeoutSeconds": 60,   // 人数不足时的最长等待填人时间（秒）
  "tntRunSize": 31,                 // 平台边长（每层方形）
  "tntRunLayerCount": 5,            // 层数
  "tntRunLayerGap": 6,              // 层间距（格）
  "tntRunVanishTicks": 5,           // 踩过的方块多少 tick 后消失（0.25 秒）
  "tntRunDropIntervalTicks": 40,    // 地面掉落物刷新间隔（tick）
  "tntRunTimeoutSeconds": 600,      // 对局超时（秒），超时击杀最多者胜，无击杀平局

  // ---- 心跳水立方 (Heartbeat) ----
  "heartbeatMinPlayers": 2,         // 最少人数
  "heartbeatStartPlayers": 4,       // 凑齐该人数开始开赛倒计时
  "heartbeatMaxPlayers": 8,         // 最多人数，达到立即开赛
  "heartbeatCountdownSeconds": 30,  // 开赛倒计时（秒）
  "heartbeatFillTimeoutSeconds": 60,// 人数不足时的最长等待填人时间（秒）
  "heartbeatSize": 21,              // 塔区覆盖边长（每关正方形塔的宽度）
  "heartbeatLevels": 5,             // 关卡总数（塔并排，第 1 关最易 → 最后一关最难）
  "heartbeatFloorGap": 35,          // 层间距（格）
  "heartbeatBaseFloors": 3,         // 第 1 关玻璃地板层数（每过一关 +1）
  "heartbeatTimeoutSeconds": 300,   // 对局超时（秒）

  // ---- 烫手山芋 (Hot Potato) ----
  "hotPotatoMinPlayers": 2,         // 最少人数
  "hotPotatoStartPlayers": 4,       // 凑齐该人数开始开赛倒计时
  "hotPotatoMaxPlayers": 8,         // 最多人数，达到立即开赛
  "hotPotatoCountdownSeconds": 30,  // 开赛倒计时（秒）
  "hotPotatoFillTimeoutSeconds": 60,// 人数不足时的最长等待填人时间（秒）
  "hotPotatoSize": 61,              // 圆形平台直径
  "hotPotatoExplodeSeconds": 20,    // 山芋寿命（秒，传递不刷新）
  "hotPotatoWarnSeconds": 5,        // 爆炸倒计时最后几秒开始红色警告
  "hotPotatoHolderSpeed": true,     // 持有者是否获得速度 I
  "hotPotatoRespawnSeconds": 2,     // 山芋爆炸后多久重新随机发放（秒）
  "hotPotatoTimeoutSeconds": 600,   // 对局超时（秒），超时当前持有者爆炸淘汰

  // ---- 起床战争 (Bed Wars) ----
  "bedWarsSize": 200,               // 区域覆盖边长（生成/清理边界，需覆盖整张地图）
  "bedWarsIronInterval": 1,         // 每队铁生成器间隔（秒）
  "bedWarsGoldInterval": 2,         // 每队金生成器间隔（秒）
  "bedWarsIronExtraChance": 0.35,   // 铁每次掉 2 个的概率（累计阈值）
  "bedWarsIronTripleChance": 0.15,  // 铁每次掉 3 个的概率（须 ≤ 掉 2 个概率）
  "bedWarsIronQuadChance": 0.05,    // 铁每次掉 4 个的概率（须 ≤ 掉 3 个概率）
  "bedWarsGoldExtraChance": 0.25,   // 金每次多掉 1 个的概率
  "bedWarsRespawnSeconds": 5,       // 死亡后复活延迟（秒）
  "bedWarsStartWool": 16,           // 开局初始羊毛数量（每队玩家）
  "bedWarsTimeoutSeconds": 900,     // 对局超时（秒），超时按存活队伍/床数判定
  "bedWarsCountdownSeconds": 5      // 开赛倒计时（秒，大厅等待）
}
```

### `config/pvp/kits.json`

自定义套件，示例：

```jsonc
{
  "kits": [
    {
      "name": "铁套测试",
      "items": [
        { "id": "minecraft:iron_sword" },
        { "id": "minecraft:bow" },
        { "id": "minecraft:arrow", "count": 32 }
      ],
      "armor": [
        { "id": "minecraft:iron_helmet" },
        { "id": "minecraft:iron_chestplate" },
        { "id": "minecraft:iron_leggings" },
        { "id": "minecraft:iron_boots" }
      ],
      "effects": [
        { "effect": "minecraft:speed", "duration": 6000, "amplifier": 0 }
      ],
      "food": 20,
      "saturation": 20.0,
      "gamemode": "adventure"
    }
  ]
}
```

- `items`：按顺序放入主手与快捷栏（最多 9 格）
- `backpack`：放入主背包的物品（适合不堆叠的物品，如岩浆/水桶）
- `armor`：顺序为 头盔/胸甲/护腿/靴子
- `effects`：战斗中的状态效果
- 修改后执行 `/pvp reload` 生效

### `config/pvp/stats.json`

战绩数据，由 Mod 自动维护，无需手动编辑。

---

## 对局流程

1. 玩家加入队列（或接受决斗），凑齐人数后自动开赛。
2. 快照每位玩家的背包/位置/生命/效果 → 传送到竞技场 → 发放套件 → 5 秒倒计时。
3. 开战后：1v1/2v2 以整队被淘汰判定胜负；FFA 等乱斗模式最后一个存活者获胜。
4. 结束：公布胜负 → 恢复玩家原状态并传回 → 记录战绩 → 清空场地，等待下一场。
5. 中途掉线判负；掉入虚空判负；淘汰后转为旁观视角观战。

---

## 对战体验细节

- **UI 工具不可丢弃**：主菜单指南针、排队红石、观战物品均无法扔出（防止误丢）。
- **组队关闭友伤**：2v2 等组队模式下队友之间无法造成伤害（也不会击退队友）；FFA/空岛战争/幸运之柱仍为全员互伤。
- **胜利庆祝**：一方获胜后不会立刻结算，先在胜者头顶**发射烟花**并显示「你赢了！」大字，5 秒后结算恢复。
- **死亡幽灵**：**死亡不弹死亡界面**，直接转为**冒险模式幽灵**——空物品栏（身上物品**爆落在地**供其他玩家拾取）、无敌、**隐身**、**可自由飞行**，**无法与对局任何交互**；若场上玩家往幽灵脚下搭方块或幽灵靠近掉落物，幽灵会被**向上弹开**，等比赛结束自动恢复回主城。
- **右侧信息栏**：对局中屏幕右侧计分板实时显示——模式（按类型着色）、比赛计时、各队成员与存活状态（存活绿色●、淘汰灰色✝）；空岛战争额外显示缩圈倒计时/安全半径。侧边栏只对参战玩家显示，回主城自动隐藏。
- **登录清空**：加入服务器时先清空背包再发放 UI 工具，不会出现背包挤满导致拿不到指南针。
- **可抛 TNT**：竞技场内拿到 TNT 后，**对空中右键把 TNT 抛射出去**（4 秒引信、落地/命中即爆）；对准方块右键仍是原版放置。
- **主城 TNT 无害**：主世界的 TNT 爆炸不破坏方块、不误伤玩家（保留声音/粒子）；只有竞技场内才会炸地形。
- **可抛火焰弹**：火焰弹右键即发射，命中时把周围 5 格玩家（含投掷者自己）沿"爆炸→自身"方向统一径向震开——往脚底扔会被弹上天（火焰弹跳），炸旁边的人会被震落岛。
- **粘液球击退 III**：空岛战争开出的粘液球是近战武器，左键命中把目标强力击退（约击退 III）。
- **不死图腾救场**：持有不死图腾掉入虚空时自动消耗一个，传送回中岛群中央主岛（仅掉虚空触发，缩圈淘汰无效）。
- **敌人追踪罗盘**：空岛战争开出的追踪罗盘实时指向最近的敌人。
- **战绩弱势补偿**：每局把玩家按胜率从低到高排名，胜率最低的 1~2 名的出生岛/中途岛装备会有轻微提升（不补偿神器），仅作追赶机制。

---

## 从源码构建（可选）

需要网络下载依赖（首次约 5-10 分钟）。机器需可运行 Gradle 9（Java 21 会自动下载）：

```bash
./gradlew build
```

产物位于 `build/libs/pvp-1.0.0.jar`。
