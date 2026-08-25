# PvP 匹配 Mod（MC 1.21.1 · Fabric 服务端）

一个纯服务端的 PvP 匹配 Mod：支持 **1v1 / 2v2 / 自由乱斗(FFA)** 对战，支持**队列自动匹配**与**决斗挑战**，可自选**装备套件**（剑战 / 弓箭 / 全装备 / 自定义 JSON 配置）。每场比赛在**专用虚空竞技场维度**中生成独立平台，结束后销毁重建。

- 原版客户端即可加入，**无需安装客户端 Mod**
- **游戏内 GUI 菜单操作**（无需记指令）：进服自动发一把「PvP 竞技场」指南针，右键打开菜单
- 所有消息为中文
- 战绩（胜/负/场次）持久化保存

---

## 服务端安装

1. 准备一个 **Fabric Loader** 的 1.21.1 服务端：
   - 到 [fabricmc.net](https://fabricmc.net/use/server/) 下载并运行 `fabric-server-launch.jar`（选择 1.21.1），生成 `fabric-server-launch` 启动方式与 `mods/` 目录。
2. 将本 Mod 的 jar 放到服务端的 `mods/` 目录，同时放一份 **Fabric API**（`fabric-api-0.116.x+1.21.1.jar`，从 [Modrinth](https://modrinth.com/mod/fabric-api) 或 CurseForge 下载，版本匹配 1.21.1）。
3. 启动服务端。首次启动会在 `config/pvp/` 生成 `config.json` 和 `kits.json`（默认配置）。
4. 服务端启动日志出现 `[PvP] 竞技场世界已创建` 即表示正常。

> 竞技场使用一个独立的虚空维度 `pvp:arena`，运行时创建、不写入存档，每次重启都是全新虚空。
>
> **战斗机制**：饱食度按原版规则消耗（战斗/冲刺会饿），**吃东西可以回血**（套件自带熟牛肉）。

---

## 游戏内 GUI

进入服务器后快捷栏会自动发放一把 **§6PvP 竞技场** 指南针（快捷栏第 9 格优先），**右键**即可打开菜单；也可以直接输入 `/pvp` 或 `/pvp menu`。

主菜单支持：
- 🗡 **1v1 / 2v2 / FFA 匹配** → 进入套件选择页 → 点击套件即加入队列
- 🤺 **向玩家发起决斗** → 选择在线玩家 → 选择套件 → 发送决斗邀请
- 📊 **我的战绩**、📦 **查看套件列表**
- 🚪 **离开队列**（排队中才显示）

> 菜单为服务端实现的原版容器界面（箱子），原版客户端直接可用，无需任何客户端 Mod。
>
> **排队时**快捷栏会出现一个红色「§c离开排队§r」红石粉，**右键即可退出排队**（开赛时自动移除）。
>
> **大厅保护**：不在对局中的玩家为冒险模式、无敌、饱食度不掉（可在配置中关闭）。

## 命令

| 命令 | 说明 |
|---|---|
| `/pvp` 或 `/pvp menu` | 打开游戏内 GUI 菜单 |
| `/pvp join <1v1\|2v2\|ffa> <套件>` | 加入匹配队列 |
| `/pvp leave` | 离开队列 |
| `/pvp queue` | 查看自己排队状态 |
| `/pvp list` | 查看进行中的比赛与队列 |
| `/pvp stats [玩家]` | 查看战绩 |
| `/pvp top` | 胜场排行榜 |
| `/pvp kit list` | 查看可用套件 |
| `/pvp reload` | 重载配置（需要 OP 2 级） |
| `/duel <玩家> [套件]` | 向指定玩家发起 1v1 决斗 |
| `/duel accept [挑战者]` | 接受决斗 |
| `/duel deny [挑战者]` | 拒绝决斗 |

内置套件 ID：`sword`（剑战）、`bow`（弓箭）、`full_gear`（全装备），以及 `kits.json` 中自定义的套件（用其 `name` 字段调用）。

---

## 配置文件

### `config/pvp/config.json`

```jsonc
{
  "ffaPlayerCount": 4,          // 自由乱斗每场人数
  "countdownSeconds": 5,        // 开战倒计时（秒）
  "maxConcurrentMatches": 4,    // 同时进行的最大场数
  "duelExpirySeconds": 30,      // 决斗挑战过期时间（秒）
  "lobbyProtection": true,      // 大厅保护：冒险模式 + 无敌 + 饱食度不掉
  "matchTimeoutSeconds": 600,   // 对局超时（秒），超时强制平局结束
  "floorBlock": "minecraft:polished_deepslate",  // 平台地板方块
  "wallBlock": "minecraft:glass",                // 平台围墙方块
  "duel1v1Size": 21,            // 1v1 平台边长
  "duel2v2Size": 31,            // 2v2 平台边长
  "ffaSize": 41                 // FFA 平台边长
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
2. 快照每位玩家的背包/位置/生命/效果 → 传送到竞技场平台 → 发放套件 → 5 秒倒计时。
3. 开战后：1v1/2v2 以整队被淘汰判定胜负；FFA 最后一个存活者获胜。
4. 结束：公布胜负 → 恢复玩家原状态并传回 → 记录战绩 → 清空平台地形，等待下一场。
5. 中途掉线判负；掉入虚空判负；淘汰后转为旁观视角观战。

---

## 从源码构建（可选）

需要网络下载依赖（首次约 5-10 分钟）。机器需可运行 Gradle 9（Java 21 会自动下载）：

```bash
./gradlew build
```

产物位于 `build/libs/pvp-1.0.0.jar`。
