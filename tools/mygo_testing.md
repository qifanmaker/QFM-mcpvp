# MyGO 隔离服务器验证

## MyGO 地图目录

MyGO 默认随机选图。OP 在排队后打开 `/pvp`，点击“立即开始”，会看到 Dust II、
Mirage、Inferno 和“随机地图”四个按钮；也可以执行 `/pvp start mirage`、
`/pvp start inferno` 或 `/pvp start random`。`/pvp mygo map <地图>`仍作为
命令入口保留。已经生成的对局会继续使用创建时绑定的蓝图。Mirage、Inferno 与 Dust II
都在服务端静态蓝图中携带各自的出生点、购买区、A/B 包点和阵亡等待室，
切图不会复用 Dust II 的坐标。

这些工具用于真实 Minecraft 1.21.1 协议回归，避免修改开发者现有的 `run/` 配置、世界和正在运行的 25565 服务。

先运行项目要求的 `./gradlew build`。验证器使用 Loom 已生成的运行类路径、Fabric Loader 与 JDK 21，并把编译输出快照放在 `build/mygo-server-test/mod-snapshot/`；验证期间再次构建不会改变已启动快照。

首次使用先准备启动配置与测试专用类路径，不启动普通服务器：

```sh
./gradlew -I tools/mygo_prepare_test.gradle mygoPrepareTestRuntime
```

该任务实际执行 Loom 的 `configureLaunch`。Loom 的 `argFiles/runServer` 在真正执行 `runServer` 时才生成，因此 `--dry-run` 不足以准备它；本任务直接解析 `runServer` 的运行类路径写入隔离目录。

如测试依赖尚不存在，将它安装在项目外：

```sh
npm install --prefix /tmp/pvp-protocol-check mineflayer@4.39.0
```

其他依赖位置可以通过 `MYGO_TEST_NODE_MODULES` 指定。JDK 21 可以通过 `MYGO_TEST_JAVA_HOME` 指定，默认查找 Gradle 下载的 JDK。

两个独立终端分别运行：

```sh
python3 tools/mygo_test_server.py
node tools/mygo_protocol_test.cjs
```

服务器绑定 `127.0.0.1:25575`，资源包 HTTP 绑定 `127.0.0.1:25576`，资源包超时为 10 秒，仅隔离测试服为六个 `MyGoCheckA` 至 `MyGoCheckF` 离线账户授予 OP。默认机器人为 A、B、C、D，所有 OP 指令、移动摆位、战绩均限定在隔离世界。

在机器人终端逐行输入 JSON；动作串行执行，证据写入 `build/mygo-server-test/protocol-results.jsonl`：

```jsonl
{"op":"gate"}
{"op":"smoke"}
{"op":"fullMatch"}
{"op":"shutdownSetup"}
```

- `gate`：拒绝、接受但未加载、加载超时、真实下载后再次入队、出队退包。
- `smoke`：商店 GUI、MP5 双发、友伤关闭、护甲、F 换弹、南瓜戴摘、安装与取消、拆除与取消、经济、新回合、`/hub` 恢复。
- `fullMatch`：真实开火连续赢七回合、封闭阵亡室与复活、第六回合后换边及经济重置、正常结束、独立战绩与原物品恢复。
- `shutdownSetup`：先保存并记录原金头盔与三个苹果，进入新场等待 LIVE、持 P9 潜行，输出 `shutdownReady`。随后在服务器终端输入 `stop`，再运行 `node tools/mygo_verify_saved_player.cjs`，从实际玩家存档 NBT 检查停服恢复。
- `explosion`：在 `shutdownSetup` 启动的 LIVE 场内实际下包、击杀所有进攻方，等待完整 C4 引信并验证进攻方仍能爆破获胜。

测试机器人保持站立，由服务端 OP `/tp` 摆位；它们不发送走动坐标，避免协议库延迟重生回包覆盖新回合位置。实际射击、换弹、潜行、购买点击、资源包状态、拆包均使用客户端协议包。

资源包会被真实 HTTP 下载，验证 SHA-1、ZIP CRC、所有 JSON、`pack_format=34` 与瞄准镜纹理，然后机器人报告 `DOWNLOADED` 和 `SUCCESSFULLY_LOADED`。机器人没有渲染器，因此这只验证服务端门禁与资产完整性；枪械模型显示、第一人称遮罩、音频播放仍需要原版 1.21.1 客户端验收。

Mineflayer 4.39.0 在 1.21.1 登录时可能输出配方组件 `ArmorTrimMaterial` 的 `PartialReadError`，但仍能正常登录与执行上述验证。该工具端问题应与 Fabric 服务器日志中的 Java 异常分开记录，不能将它视为客户端视觉验收通过。

## 2026-10-24 实测记录

最终无诊断快照在 Fabric 1.21.1 / Loader 0.16.14 / Java 21 下通过 43 个协议断言和 4 个玩家存档断言，失败数均为 0。覆盖商店 GUI、MP5 双发、友伤关闭、护甲伤害、F 换弹、南瓜戴摘、安装与取消、拆包与取消、回合经济、复位、退出恢复，以及进攻方全灭后继续等待完整 40 秒引信并爆破获胜。停服前再次确认 LIVE 持枪南瓜状态，正常控制台停服后从实际玩家 NBT 确认原金头盔、三个苹果恢复，临时南瓜与速度修饰符已移除。

独立完整对局还验证了 7:0 正常结束、第六回合后换边、半场现金与装备重置、阵亡室和复活、独立 MyGO 战绩增量。该完整局在修复选槽同步之前执行：第 1、2、7 回合使用 P9，第 3～6 回合实际使用 AK，原始日志中的通用 P9 标签不能代替 `status gun=` 记录。选槽同步缺陷随后修复，最终快照烟测使用更新后的代码。

资源包拒绝、只接受/下载未加载、10 秒超时、排队后 `DISCARDED` 自动出队、对局中 `DISCARDED` 自动离场均已实测。ZIP 为 66,400 字节，SHA-1 为 `c3908cb40cc5685ed23779e8f9d9f89286d85019`。

证据保存在隔离目录：`protocol-results.jsonl`、`final-server.log`、`final-bot-console.log`、`saved-player-results.json`、`second-pass-protocol-results.jsonl`、`seventh-pass-protocol-results.jsonl` 与 `final-verification.json`。原始失败尝试及定位停服并发清背包的日志也保留，未改写成通过记录。

最终服务器日志没有 Java 异常或 ERROR；测试机器人跨回合传送回包仍产生 3,395 条 `moved too quickly` 移动告警，登录配方解析报错见上文。它们属于当前协议驱动的已知限制，所以不能把本次结果写成“日志零警告”或“客户端渲染验收通过”。

## 外部资源包 URL 与旧配置迁移复验

2026-10-24 增加公网默认地址后，使用以下命令专门创建旧版“没有 `configVersion`、`packUrl` 为空”的隔离配置并启动：

```sh
python3 tools/mygo_test_server.py --run-dir build/mygo-external-pack-check --legacy-config
```

实测启动后配置迁移为 `configVersion: 1` 和 `packUrl: https://qfmaker.com/upload/files/wmcvlnck.zip`，本地 HTTP 端口 25576 没有监听。两名 1.21.1 协议客户端均收到完全一致的 HTTPS 地址，实际下载 66,400 字节 ZIP 并校验 SHA-1、CRC、资源格式后成功进入 MyGO 队列，6 个断言全部通过。

独立 HTTP 下载返回 `200`、`Content-Type: application/zip`，与内置 ZIP 逐字节一致。证据位于 `build/mygo-external-pack-check/response-headers.txt`、`verification.json`、`server-verification.json`、`protocol-results.jsonl` 和 `server-console.log`。复验结束后隔离服务器及机器人已停止。

常规测试仍会显式写入 `configVersion: 1` 与空 `packUrl` 以保留局域网 HTTP 测试夹具；这与新安装服务器的公网默认值不同。

## MyGO 主菜单入口复验

菜单场景使用三名协议客户端，运行 `{"op":"menu"}` 后紧接 `{"op":"quit"}`；启动时指定 `MYGO_TEST_BOTS=ABC` 和隔离目录 `build/mygo-menu-test`。最终 17 个断言全部通过：主菜单槽 10／11／12 分别为空岛／MyGO／战桥，趣味小游戏页及旧槽 19 不再提供 MyGO；主菜单点击完成 HTTPS 资源包下载及再次点击入队；两名 MyGO 玩家让主按钮显示数量 2，趣味分类不计入他们；第三名玩家加入幸运之柱时趣味分类只计 1。GUI 退出、人数刷新和退包均正确。

实测发现原铁镐图标受 `SimpleInventory.setStack` 的最大堆叠数 1 限制，人数 2 会被截为 1，最终改用可堆叠的火药图标后通过复验。首次失败及机器人排查等待期间的超时保留在 `build/mygo-menu-test/first-attempt-*`；最终场景完成后立即退出，无客户端超时、Java 异常或移动告警。最终服务器与机器人均已停止。

证据：`build/mygo-menu-test/verification.json`、`protocol-results.jsonl`、`server-console.log`。对应交付包 `dist/pvp-1.0.0-mygo-main-menu.jar` 的 SHA-256 为 `dc22294101612f8199b82bc0445acc5e1f4c86fbaf30ded38c3ed3624b0f9889`。该验证检查实际菜单协议及点击行为，不包含原版客户端截图验收。

## MyGO 军械库升级与新下载地址复验

修改原因：用户要求参考现成 Minecraft Counter-Strike 项目改进全部玩法和资源包。参考 CounterStrike4Minecraft 的纯服务端射击、阵营购买和反馈方式，按现有 1.21.1 会话接口实现；将 Pelmeshka_ 的 MIT Weapon Pack 1.1 转换为原版 CustomModelData 模型，保留来源和授权。新增十五枪军械库、投掷物、开火／换弹姿态、后坐力、命中反馈、MVP 和地图立面。相关源码、规则、模型、输入及结束清理路径均在编辑前后审查。

用户手动上传后的地址为 `https://qfmaker.com/upload/files/5df4ab7h.zip`。内置、交付和真实 HTTPS 下载的 ZIP 均为 184,399 字节，SHA-1 为 `93ddc25a6ff1b14a31076bbf6db433fdb20b3b02`；共 121 项文件、64 个模型 JSON、25 个 OGG。全部声音经 ffmpeg 解码通过。配置升级到版本 2，将旧空配置与上一版默认 CDN 地址迁移到新地址，同时保留显式选择的局域网 HTTP 和其他自定义地址。

`./gradlew build --warning-mode all` 构建通过，41 项单元测试全部通过，没有新增编译警告。使用最新构建快照启动真实 Fabric 1.21.1 隔离服务器：

```sh
python3 tools/mygo_test_server.py --run-dir build/mygo-arsenal-final --legacy-config
MYGO_TEST_RUN_DIR=build/mygo-arsenal-final node tools/mygo_protocol_test.cjs
```

四名 1.21.1 协议客户端依次执行 `smoke`、`arsenal`、`fullMatch`、`shutdownSetup`。111 项协议断言全部通过：基础购枪、护甲、友伤、换弹、安装取消和带钳拆包 28 项；新军械库、阵营限制、手雷携带上限、Uzi 连发／相机后坐力、S686 弹药、换弹模型、HE／闪光／烟幕及退出清理 20 项；真实七回合 7:0、半场现金与装备重置、阵亡室和战绩结算 53 项；停服准备和即时南瓜头检查 10 项。正常停服后读取玩家 NBT，原大厅金头盔、三颗苹果、无临时移速修饰符、无临时南瓜四项全部通过。隔离服务器和机器人均已停止，用户原有 25565 服务器保持运行。

首次实测在 `build/mygo-arsenal-test/` 暴露了后坐力传送 API 参数误用：`requestTeleport` 需要绝对服务端坐标，再自行编码相对回包。修复为当前位置与当前朝向加增量后，最终测试验证了相机旋转回包、连续射击及玩家仍在场内存活；保留首次失败记录。

最终服务器无 Java 异常堆栈或 ERROR。协议驱动依然有已知限制：跨位置回包产生 20,755 条 `moved too quickly` 告警，登录配方解码出现 8 次 `PartialReadError`，因此不宣称“日志零警告”。模型结构、贴图引用、姿态切换协议和音频解码已验，原版客户端的实际渲染、南瓜镜框画面、音频播放及操作手感仍需人工进服验收。射击采用短点射组，闪光采用原版失明／黑暗，姿态切换不等同于客户端骨骼动画。

证据：`build/mygo-arsenal-final/verification.json`、`protocol-results.jsonl`、`saved-player-results.json`、`server-console.log` 与 `bot-console.log`。交付包 `dist/pvp-1.0.0-mygo-arsenal-v2.jar` 的 SHA-256 为 `88fac365bc06bf0c89e40e1ea8724e823d5821bfb9e6ff0e476ea36ef50bfe78`；配套 ZIP 为 `dist/mygo-arsenal-v2-resource-pack.zip`，与用户已上传文件一致。

## 右键开枪触发“离开战场”修复

2026-10-24 用户反馈开枪后立即阵亡，并确认原因为“离开战场”。编辑前检查了开火、后坐力、地图边界和死亡路径，以及本地 1.21.1 的服务端／客户端传送实现。早期错误是把 `(0, 0, 0)` 作为相对坐标传给 `requestTeleport`；该方法实际接收绝对服务端坐标，所以会把射手移出地图。上一次坐标修正后的源码在本轮 15 项正常物理测试中通过，但后坐力仍依赖传送状态机；本次进一步移除该依赖，避免开枪改动坐标或覆盖正常传送确认。

`MyGoSession.fire` 现在只更新 yaw／pitch 并发送零 XYZ 相对位移的视角包，服务端位置和速度保持不变。视角包使用 `-1` 确认编号：原版正常传送编号非负，服务端会忽略此确认，不干扰换世界／换回合的正常传送。保留地图越界死亡判定，没有通过关闭边界检查掩盖问题。增加 `tools/mygo_recoil_regression.cjs`，保留 Mineflayer 的完整物理、位置回包和传送确认，不使用原有静止测试的坐标覆盖逻辑。

为确认测试能检出原问题，在独立 25585 服务器加载保留的早期错误 `MyGoSession*.class`：第一枪即从 100 HP 变为 0 HP，聊天明确出现 `MyGoCheckA 阵亡（离开战场）`，首枪存活断言失败；原始证据保存在 `build/mygo-recoil-original/`。这次只在隔离目录中恢复旧类，没有回写项目源码、构建产物或用户运行中的服务器。

修复版通过 `./gradlew build --warning-mode all`，42 项单元测试全部通过，无新增编译警告。真实 Fabric 1.21.1 的四客户端专项测试 18 项全部通过：首枪保持原位置及 100 HP、两发连射、霰弹、双方手枪、南瓜开镜、边走边射、跳射、换弹、正常传送确认、真正越界仍阵亡、退赛清理。随后运行既有 `smoke` 场景，购枪、命中伤害、友伤、护甲、安装取消、带钳拆包及回合经济 28 项全部通过；本轮共 46 项实服断言通过。

```sh
MYGO_TEST_RUN_DIR=build/mygo-recoil-fixed node tools/mygo_recoil_regression.cjs
```

最终服务器无 Java 异常堆栈和 ERROR。两个协议驱动仍出现配方解码的 16 次 `PartialReadError`，原有静止驱动跨传送回包产生移动告警（合计 1,538 条），这些限制沿用前文记录，不等同于客户端画面人工验收。证据为 `build/mygo-recoil-fixed/verification.json`、`recoil-results.jsonl`、`protocol-results.jsonl` 和对应控制台日志。

修复包：`dist/pvp-1.0.0-mygo-recoil-fix.jar`，SHA-256 `3c82812cfa34483e30f5be601e6fcf8d13dfc81d845b236802bedabd1547adaa`。资源 ZIP 与下载地址保持原值，SHA-1 仍为 `93ddc25a6ff1b14a31076bbf6db433fdb20b3b02`，无需重新上传。替换旧 JAR 后需要完整重启服务器，`/reload` 不会更新已经加载的 Java 类。

## 左键开枪、右键开镜与 AWM 放大

修改原因：用户要求改为左键开枪、右键开镜，并提高 AW 的开镜倍率。编辑前核对了原版 1.21.1 左键空气／方块／实体的数据包路径、右键重复输入和 FOV 计算。左键统一从主手挥手包进入枪机，实体攻击和挖掘回调不额外发射，避免一击重复耗弹或叠加原版工具伤害；仅限 MyGO，会话结束和其他模式不会触发。右键切换开／关镜，连续右键使用包去抖；切枪、阵亡、回合结束自动关镜，Shift 保留潜行及 C4 操作。GUI、商店、侧栏、入场提示及 README 同步更新。

AWM 开镜发送客户端 FOV 计算基准的能力包副本，实际服务端能力与移速不变，权限标记完整保留。实测服务端 `walkSpeed=0.1`、开镜移动属性 `0.065`，客户端基准 `walkingSpeed=0.2`；原版视野乘数为 `(0.065/0.2+1)/2=0.6625`。默认基础视野 70°、视野效果 100% 时约为 46.375°，比原先约 57.75° 更窄；关镜、换枪、阵亡和退赛恢复真实基准。客户端关闭「视野效果」时原版会关闭该放大，因此不宣称所有客户端设置下固定倍率，也未将协议检查视为画面截图验收。

`./gradlew build --warning-mode all` 通过，42 项单元测试全部通过。隔离 Fabric 1.21.1 服务器的四客户端验证：正常物理与位置回包的鼠标／后坐力专项 28 项、既有经济爆破 `smoke` 28 项、通过两轮实际回合奖励购买两把 AWM 的缩放专项 20 项（含资源下载校验），共 76 项断言通过。覆盖右键不耗弹及重复包不闪镜、左键空气／方块／实体各触发一组、刀仍造成 40 近战伤害、非主手不触发、购买阶段禁射、真正越界仍阵亡；AWM 的放大、权限及真实速度保持、左键一发、其他枪镜和退出／死亡恢复均通过。初次启动测试客户端早于服务器就绪的连接失败保留在 `build/mygo-mouse-controls/startup-race-*`，最终验证在新目录完成。

证据：`build/mygo-controls-final/verification.json`、`recoil-results.jsonl`、`protocol-results.jsonl` 和控制台日志。该阶段无服务器 Java 异常或 ERROR；原有协议工具仍有 9,546 条跨传送移动告警和 16 次配方 `PartialReadError`，限制同前。

## 快捷栏上方金钱 HUD

修改原因：用户希望金钱更明显，并显示在快捷栏上方的小标题位置。统一 MyGO 的 Action Bar 输出，使用加粗金色 `$ 1,600` 及千位分隔作为首要信息；常驻 HUD、购买结果、命中、换弹、安装／拆除进度、错误反馈和阵亡等待都保留当前余额。扣款成功提示不再重复一遍余额，HP／弹药等原有信息继续显示。每次输出读取当前 Fighter 现金，不新增一套经济状态。

最终代码再次通过完整构建与 42 项单元测试。四客户端实服 `smoke` 36 项全部通过，其中新增八项直接检查收到的 `system_chat` Action Bar 标记、加粗金色及实际金额：初始 `$ 1,600`、购买后 `$ 350`、命中与换弹保留余额、安装进度及奖励后的 `$ 1,900`、拆包进度、拆除与回合奖励后的 `$ 3,750`。最终捕获 346 个 Action Bar 包。首个测试过早读取到回合初始化、常驻 HUD 首 tick 尚未发送时的失败保留在 `build/mygo-money-hud/before-hud-wait-*`；测试随后等待首个 HUD 到达，没有更改服务器实现来放宽断言。

最终证据：`build/mygo-money-hud/verification.json`、`protocol-results.jsonl` 和控制台日志。最终服务器无 Java 异常或 ERROR，协议驱动有已记录的 1,034 条移动告警与 8 次配方解码错误；实际客户端画面仍需人工验收。所有本轮隔离服务器与机器人已停止，原有 25565 服务保持运行。

统一交付包：`dist/pvp-1.0.0-mygo-controls-hud.jar`，SHA-256 `78d7a3090baa185afa77480e6f7b34ae6e24810181afb1548bb8a901566eea0c`，包含鼠标映射、AWM 放大和金钱 HUD。资源包 SHA-1 仍为 `93ddc25a6ff1b14a31076bbf6db433fdb20b3b02`，无需重新上传；替换旧 JAR 后完整重启服务器。


## 2026-10-24 — 原存档 Dust II 与出生配置

原因：用户要求用互联网下载的真实 CS 地图替代程序仓储场，并重点检查出生点。
来源为 LeComte / LesRaisins Studio 的 Dust II，CurseForge 文件 5637196 / dust II-0.1；
原 ZIP SHA-256 为 3debce04b6fb9183038b88a0a751927a7b431e23a3a52ee4d9fa570710085803。
只提取建筑，不导入实体、方块实体 NBT、玩家数据或函数；完整非商业署名条款随地图 NOTICE 保留。

提取命令：`python3 tools/extract_mygo_map.py --archive /path/to/dust2.zip`。
最终蓝图 196,187 方块 / 770 状态 / 562,129 bytes，SHA-256
`2afc6fd9c58584fff4df603870029607be19d2ab483ef5cefff5f64e6da06670`；
稳定砂砾/粉末、保留干燥状态和持续树叶，先放建筑支撑再放附着装饰。
布局与来源见 `tools/assets/mygo-dust2/`，双方各 5 个独立出生及沿街朝向、购买区和两个真实包点均集中配置。
低层裸石外围 7,377 列设为越界，避免绕到地图背面；`python3 tools/audit_mygo_map.py`
对 20 条“出生→A/B”路线检查通过。该算法是近似碰撞格，最多跳 1 格 / 下落 3 格，
不能当作原版客户端逐路跑图或渲染录像。

`./gradlew build` 通过，47 项单元检查全通过。隔离 Fabric 1.21.1：
- `build/mygo-dust2-final/`，25575/25576，`tools/mygo_map_regression.cjs`：104 项通过、0 失败。
  十人正常物理/传送回执、原图方块样本、10 出生/朝向/净空、购买区越区归位、出生处开镜射击、
  6 回合结算至 3:3、第 7 回合全员换边与经济装备重置、两个高度和低顶半砖通道 C4 丢弃拾回、
  A/B 各实际安装拆除、退赛快照恢复均通过。清场后强制加载样本区块，A/B地板和等待室均为空气，
  区域非玩家实体为空；控制台记录 MYGO_CLEAR_A/B/ROOM/ENTITIES_OK。
- `build/mygo-dust2-controls/`，25585/25586，正常物理回归 29 项通过，经济/HUD smoke 36 项通过。
  验证步行/跳跃开枪、实体/方块左键射击、右键开镜、后坐力与正常传送、低层裸地边界、
  2v2 购买、友伤免疫、换弹、安装/拆除取消与金钱刷新、退赛恢复。
  最初近身命中场景沿北方向行走后将目标放到了原图墙后，真实遮挡导致预期伤害断言失败；
  已修正测试准备，把枪手与目标放回审计空旷枪线，再测全部通过（归档 before-lane-reset-*）。

两服均无 Java ERROR/异常堆栈。Mineflayer 登录配方仍有已知 PartialReadError，
跨回合/冻结阶段 TP 回包仍会产生 moved too quickly 告警；不宣称日志零警告。
枪械资源 ZIP 与外部托管地址不变，未宣称通过原版客户端画面/音效验收。
原有 25565 服务未停止或修改；隔离测试服已在测试后关闭。


## 2026-10-24 — 枪械紫黑材质与新 CDN

原因：用户实际客户端报告紫黑格。下载站旧包与源码旧包逐字节一致，排除上传损坏：
旧 SHA-1 `93ddc25a6ff1b14a31076bbf6db433fdb20b3b02`。
根因是 26 张自定义贴图处于 `textures/mygo/`，1.21.1 默认物品/方块图集只收录
`textures/item/`、`textures/block/` 等目录。此前只检查 PNG 存在，漏掉客户端图集规则。

修复把枪械、刀、投掷物、C4、拆包工具、烟幕和镜图标材质移到 `textures/item/mygo/`，
同步模型与粒子引用，保留 Weapon Pack 1.1 (MIT) 的 15 种枪械样式和正常/开火/换弹姿态。
声音路径 `sounds/mygo/` 和南瓜叠层 `textures/misc/pumpkinblur.png` 保持原用法。
两次生成完全一致：184,882 bytes，SHA-1 `a3f95f988abba397710d5bd411661b5d385a882e`。
用户手动上传到 `https://qfmaker.com/upload/files/zevadwr3.zip` 后已再次下载逐字节比对一致。
默认配置升至版本 3，旧默认 5df4ab7h.zip / wmcvlnck.zip 自动迁移；保留自定义地址和显式本地 HTTP。

`./gradlew build` 通过，51 项单元检查、0 失败：
- 新 `MyGoAtlasLoadingTest` 使用原版 1.21.1 ResourceManager / DirectoryAtlasSource / JsonUnbakedModel：
  旧托管包复现 0 个自定义 sprite、26 张有效 PNG 未收录；新包 26 个 sprite 全部收录，
  58 个模型与 3,116 条继承模型面、粒子材质全部正确解析。
- 原有资源、经济、地图、枪械与配置迁移检查全通过。

Fabric 1.21.1 隔离测试：
- `build/mygo-texture-fixed-server/`：内置 HTTP 下载新包，36 项 smoke 通过，
  包含进入模式、GUI 购买、射击/开镜、换弹、C4 安拆与取消、金钱 HUD 和快照恢复。
- `build/mygo-texture-cdn-migration/`：用 `--previous-cdn-config` 写入真实版本 2 旧 CDN，
  启动后变为版本 3 + zevadwr3.zip，不启动内置 HTTP；客户端实际从 HTTPS 下载，
  校验新 SHA-1，拒绝/等待/超时/成功回执和退出退包共 7 项通过。
  首次 gate 的状态查询比服务端处理 LOADED 回执早 1 ms，已归档 before-ack-wait-*；
  测试现等待服务端“资源包加载成功”确认后再查状态，复验 7/7 通过。

服务器无 Java ERROR/异常堆栈，协议机器人仍有已知配方解析和跨回合传送移动告警。
图集和模型解析验证不启动 OpenGL，不宣称人工客户端画面验收。
两个隔离服务器已关闭，原 25565 / PID 83605 未重启。

## 2026-10-24 — 双人自动匹配与鼠标提示同步

原因：用户要求最低两人开局，并指出入队消息仍错误提示“右键点射、Shift 瞄准”。
MyGO 的队列、建场和 OP 提前开局统一读取 `MatchType.MYGO.requiredPlayers()`，最低值改为 2。
保留 60 秒等员、满 10 人立即开局、双方等员及少于 4 人不计战绩的现有规则。
命令/GUI 入队、主菜单、开局消息、商店帮助和侧栏统一显示“左键点射、右键瞄准/关镜、F 换弹”。

`./gradlew build --console=plain` 通过，51 项单元检查、0 失败、0 跳过。
Fabric 1.21.1 隔离测试服 `build/mygo-two-player-test/`，协议复验日志
`build/mygo-two-player-retry/protocol-results.jsonl`：23 项检查通过、0 失败。
两名原版协议客户端实际下载资源包，单人留在大厅；第二人入队后无需 `/pvp start`，
66.911 秒进入首回合购买阶段（包含 60 秒等员、铺图及入场倒计时），各一名 T/CT、100 HP、$1600。
两人右键只开镜且弹药不变，左键消耗一发且仍存活；退出恢复原世界、金头盔和 3 个苹果，移除枪械与镜框。
命令入队、开局以及实际点击 GUI 入队的消息均验证为当前鼠标操作。

首次测试已实际自动开局，但测试把 Mineflayer 的 `game.dimension` 类型当成世界键，误判竞技场。
保留该次日志；测试改从原版 login/respawn 包 `worldState.name` 读取世界键，复验确认 `pvp:arena`。
这次测试修正未改动产品代码。服务器无 Java ERROR/异常堆栈；协议机器人仍会报告已知配方解析问题，
不宣称人工客户端画面或音效验收。隔离测试服已正常关闭，原 25565 服务未重启。

交付 `dist/pvp-1.0.0-mygo-2players-hints.jar`，1,624,405 bytes，SHA-256
`6daaae57fd8a7d28db9b8c89f47e9c1868ffe72f789cc29ac9e8009e244b2570`。
默认资源地址仍为 zevadwr3.zip，资源包 SHA-1 仍为 `a3f95f988abba397710d5bd411661b5d385a882e`，无需重新上传。

## 2026-10-24 — CS:GO 军械、瞄具、经济与经典时限

原因：用户在规则差异审计后，要求将枪械体系、开镜、经济和时间按 CS:GO 修改。
保留最低两人等员匹配、原版工具承载与南瓜镜框；枪械表改为 34 种 CS:GO 武器，
双方默认 Glock-18 / USP-S，副枪独立购买、丢弃和拾取，取消旧 AK/M4 的强制三连发。
枪械属性依据公开游戏物品数据及 Valve 后续平衡补丁，来源和继承解析结果保存于
`src/test/resources/mygo/csgo-weapon-facts.json`。射程按一格一米换算，射击周期向上量化至 20 Hz；
换弹动画时长、输入、散布与命中盒仍为 Minecraft 适配，不宣称完整 Source 引擎复刻。

MR15：先 16 胜，第 16 回合换边，15:15 平局；冻结 15 秒、解冻后购买 20 秒、作战 115 秒，
安装 3.2 秒、C4 40 秒、无钳/有钳拆除 10/5 秒，回合末保留 7 秒活动、射击与捡枪。
败补从 $1900 档开始，在 $1400/$1900/$2400/$2900/$3400 间升降；胜利下降一档，按胜因和武器发奖。
护甲有消耗、补甲与衣盔套装价格，半场重置 $800 和装备；整场保护时限延长至 120 分钟。
审查补齐冻结期交枪/交 C4 拾取，以及丢瞄具枪后同 tick 购买无镜枪的瞄准状态清理。

仅 AUG/SG 553 一级、四种狙击两级；AWP/SSG 开火暂退镜，周期结束后恢复原镜位，切枪/换弹取消。
`MyGoScope` 对客户端能力包副本计算视野基准，不改真实权限或移动属性，也不使用会吞左键的望远镜。
原版客户端方法字节码/渲染器数值测试的范围、普通 JUnit 引导限制及 FOV 设置要求，见
`tools/mygo_scope_testing.md`。该验证不包含 OpenGL 画面或音效验收。

新 ZIP 为仓库原创 MIT 的 34 种轮廓、基础/开火/换弹姿态与 44 个合成声音：273,030 bytes、182 项，
SHA-1 `d191135ce9ed995f22564ee916ce4237beed01f1`。用户手动上传至
`https://qfmaker.com/upload/files/uxv5wsbc.zip` 后已下载逐字节校验；配置版本 4 迁移已知旧默认地址，
保留自定义 CDN 和显式本地 HTTP。原图 Dust II 蓝图及许可未改。

`./gradlew build --console=plain` 通过，69 项单元检查、0 失败、0 跳过。原版图集检查解析新版
11 张物品图集 PNG、所有模型及继承姿态，开镜数值、34 枪事实表、护甲/经济/MR15 边界均通过。

Fabric 1.21.1 普通 20 TPS 实服：`build/mygo-csgo-classic/`，25575，使用
`tools/mygo_test_server.py --texture-fix-cdn-config` 从真实版本 3 / zevadwr3 配置升级，
启动后为版本 4 / uxv5wsbc，无内置 HTTP 下载；四名协议客户端从 HTTPS 实际下载并校验新 ZIP。
复验日志 `build/mygo-csgo-retry/protocol-results.jsonl`，`csgo` 操作 61 项检查通过、0 失败。
三回合只靠正常奖励攒钱，验证默认枪、54 格商店、副枪替换、阵营限制、15 秒冻结、
115 秒作战和解冻后 20 秒购买截止，安装/无钳/有钳拆包实际计时，首轮 $1900 败补及下包 $800，
AK 单发无镜、真实命中消耗护甲、残甲跨回合保留、$650 补甲/$350 满甲加盔、
AUG 一级与 AWP 两级的实际能力包、正的真实移速、镜内左键、射后退镜及自动恢复、
切枪取消恢复、AWP 枪杀 $100 和退出快照/能力/退包均通过。
首次打甲用例中 Mineflayer 本地 look 与服务端 look 包不同步；已显式发送普通 look 包再挥手，
修正测试准备后全程复验通过，没有通过修改伤害或移除地图遮挡来迁就测试。

交付 `dist/pvp-1.0.0-mygo-csgo-classic.jar`，1,716,081 bytes，SHA-256
`4f34f6d5820711aaf7ed6db758236df6ef2589bc19a753b0b208062adf65f5f8`。

独立半场回归 `build/mygo-csgo-halftime/`，25585，`csgoHalftime` 操作 58 项通过、0 失败。
初局以正常 20 TPS 验证冻结期交接 C4、真实购买钳和闪；随后用 `/tick rate 200` 加速重复回合，
每局通过正常越界淘汰使原 CT 连赢，保存 MP9/甲盔/钳/闪至第 15 局 POST，验证 $16000 封顶。
第 15 局 POST 恢复 20 TPS，确认尚未提前换边；第 16 局 BUY 四人正式换边、$800、败补档 1、
正确新默认手枪及装备清理；最后第 16 胜正常结束，完整恢复四人原背包、能力和资源包状态。
第一次退出断言早于最后一名客户端的库存包，保留初次日志；等待完整恢复条件后复验 58/58。
测试在 finally 恢复 20 TPS。两个隔离服均无 Java ERROR/异常堆栈，原 25565 服务未重启；
协议机器人仍有已知配方解析和传送移动警告，不宣称日志零警告或人工客户端画面验收。

## 2026-10-24：右键误开火、战术区域与地图安全

原因：原版 1.21.1 收到发给自己的挥手动画时会回发 `HandSwingC2S`，旧处理把右键
开镜后的这次回包当作左键；门、活板门、栅栏门、拉杆还有客户端预测挥手。
原布局把 T 放在 A 小、CT 放在 B2，A 标记偏到其他庭院、B 标记偏箱后，且 7,377 列
隐藏禁行区会直接淘汰走到正常地面的玩家。

右键改为 `CONSUME` 并在回调中保留原始结果，MyGO 不再向本人发送右键挥手动画；
稳定预测的装饰方块按同连接顺序仅消费一次使用挥手，不采用时间窗口屏蔽真正左键。
原图按钮保留同状态的无碰撞 `block_display` 外观。展示实体拥有每场独立标签，重载时
按 UUID 更新引用，结束后重载的孤儿实体自动丢弃，防止跨局重复。

T/CT、购买区、A/B 均已重配，坐标及地形依据见 `tools/assets/mygo-dust2/layout-notes.md`。
压缩蓝图仍为同一份 196,187 个建筑方块、770 个状态，原始 `blocks` 和 `palette` 与
修改前逐项一致；只更换玩法布局并移除隐藏禁行列。完整建筑及屋顶跳跃净空均合法，
掉出外围框或触发全局虚空兜底只返回最近真实支撑的安全点，保留 HP 与回合。

`./gradlew build` 通过，76 项单元检查，0 失败/错误/跳过；包含原图红色 A 地标、
B 默认箱前的上半台阶支撑、整图合法和一次性输入顺序边界。

实服使用 Fabric 1.21.1 的独立 Loom 输出快照，不接触 25565：

- `build/mygo-input-map-fix/protocol-results.jsonl`：首次 `inputRegression` 43 项通过，
  继而 `csgo` 额外 61 项通过（文件内累计 104）。验证右键空气/墙/玩家、连续开关镜、
  右键后立即左键、预测装饰交互，以及三局枪械、护甲、经济、安拆时长和 AWP 镜位。
- `build/mygo-input-map-final/protocol-results.jsonl`：最终含实体生命周期修复的版本，
  输入复验 43 项、MR15 半场/终局 106 项，累计 149 项全部通过。半场夹具使用真实副枪
  射击淘汰，而非已移除的越界淘汰；第 16 局双方正确换边、补弹、重置经济装备，随后
  真正打到 16 胜并完整恢复大厅装备。200 TPS 只用于重复回合推进；输入与瞄具检查
  保持 20 TPS，不能将加速回合当成真实耗时证明。
- `tools/mygo_map_safety_regression.cjs` 与 `build/mygo-map-safety-final/safety-results.jsonl`：
  十个客户端保留正常物理及位移回复，检查全部出生地板/净空/朝向、冻结归位、T/CT
  自然步行、旧误杀低地、最高屋顶跳跃、两种深度虚空救援、旧 A 区拒绝安装与真 A/B
  安拆包，并检查装饰重载、跨局单实例及大厅快照恢复。
  最终 63 项全部通过：主动离开远端区块、保存并等待后，同一实体 UUID 的网络 ID
  从 777 变为 845，确认实际服务器重载；重载后和下一场均恰好一个按钮展示实体。

首次地图测试把开局说明“阵亡后……”当作阵亡事件，修正为精确玩家阵亡格式并保留日志；
第二轮发现卸载对象引用失效造成装饰重复，已修复所有权/重载清理后再用最终快照复测。
客户端离开视图不能单独证明服务器卸载，测试分别记录实体 UUID、实体 ID 和单实例条件。
最终输入测试最初早于服务器就绪产生连接拒绝，已归档为 `startup-connection-attempt.jsonl`，
等待就绪后重连复验通过；该记录不是游戏功能断言失败。

这些是协议客户端和真实服务端验证；输入脚本显式模拟原版动画反馈及预测包，未运行
OpenGL 人工画面、声音或完整原版客户端输入验收。资源包内容未改，继续使用 uxv5wsbc.zip，
SHA-1 `d191135ce9ed995f22564ee916ce4237beed01f1`，无需重新上传。

交付 `dist/pvp-1.0.0-mygo-input-map-fix.jar`，1,708,072 bytes，SHA-256
`77f26e8cf0d15ffc5f55d287a5738fb5136d93b4c8ce3c54a7cf4dbbab17f660`。
最终地图 SHA-256 `9c31ad02513b05f1bae8f76a08ee0b852e494f8990e3b3892835d93ebcc216c6`；
构建及各实服记录汇总在 `build/mygo-input-map-verification.json`。原 25565 服务没有重启或更换 Mod。

## 2026-10-24：复核 T/CT 是否对调

结论：阵营布尔、默认手枪、C4、制服、购买区和第 16 回合换边没有反；T 在真实匪家高台后墙内。
但上次 CT 五点实际位于 `source z=39` 的开阔 CT mid，只检查地板/净空/连通性不足以确认
战术区域，称其为“桥下”不准确。

对照 CS:GO 原始 overview 的 A/B、T/CT 标记及原图桥板后，CT 调至原存档
`(95,11,z)`，z 为 `54/57/60/63/66`；原 T 坐标、两个包点和建筑方块完全不变。
CT 购买区调整到 `(90,10,49)`～`(100,14,70)`，前两格面向中门/B，后三格面向 A 坡。
详情和原始雷达文件链接在 `tools/assets/mygo-dust2/layout-notes.md`。

`MyGoMapAssetsTest` 现在还要求每个 CT 点上方的原图桥板 `spruce_stairs` 存在，防止
再次用可站立的开阔道路代替 CT 家。`./gradlew build` 成功，76 项单元检查全部通过，
日志 `build/mygo-ct-spawn-build.log`；静态 20 条出生到包点路线审计通过。
实服回归使用 `build/mygo-ct-spawn-check/`，十客户端正常物理及位移回复，
包括新 CT 出生、购买、自然步行、A/B 安拆、安全救援、实体重载与跨局恢复。
63 项全部通过，无 Java 异常堆栈；实体重载同 UUID、网络 ID `103 → 130`，下一场仍无重复。
25585 已正常关闭并保存，原 25565 未改动。定位、构建与测试汇总见
`build/mygo-ct-spawn-check/verification.json`；示意图 `build/mygo-spawn-orientation-corrected.png`。

本次交付 `dist/pvp-1.0.0-mygo-ct-spawn-fix.jar`，1,708,070 bytes，SHA-256
`b8095784dcce9808cb5835539496668510ce110a63ca4c37404c3be03ccece1f`。
新版地图 SHA-256 `a0df8b4f9ea070c9e7496134488061b73d75fd8a338862c2bde774e8246d839c`。
资源包不变，替换服务器 Mod 后重启才会加载新出生配置。

## 2026-10-25 原版 CS:GO 伤害核对

修改原因：静态枪械属性已经接近原版，但运行时未区分腹部，且小数 HP/护甲一直保留到下一枪，
会改变击杀门槛和残甲效果。先审查 `MyGoWeapon`、`MyGoRules`、`MyGoSession.fire` 和现有回归，
再独立核对 legacy CS:GO 的分发数据（不是 CS2 武器定义）：

- [items_game.txt 固定快照](https://github.com/SteamTracking/GameTracking-CS2/blob/e63fc7fdb8dfb4f1873f0db214c0cc16613d5beb/csgo/scripts/items/items_game.txt)，
  6,562,804 bytes，SHA-256 `469cf0ef8a2c53503f0845b60d3c0dc576625dfbbfb9aff885ee8282b45beaec`；
  递归解析 prefab 后，34 把枪的伤害、爆头倍率、armor ratio、range modifier、弹丸数、射程共 204 项与现有数据一致。
  独立报告 `build/mygo-damage-data-audit.json`，事实表更新来源说明，武器记录没有修改。
- [国服官方基础资料](https://www.csgo.com.cn/tips/miscellaneous/20161220/198787.shtml)说明最终生命伤害舍弃小数，
  距离取眼睛到实际命中点；[原版伤害说明](https://steamcommunity.com/sharedfiles/filedetails/?id=274953650)列出部位倍率和护甲公式。
- [3kliksphilip 的原版 CS:GO 护甲实验](https://youtu.be/n5xdMo6kj00)在 6:21–7:10 验证生命伤害向下取整、
  护甲消耗向上取整，在 1:08–1:28 说明头盔与防弹衣共用护甲耐久。

实现：头部使用各枪自己的倍率，胸/臂 1、腹 1.25、腿 .75；腿不减伤、头盔只保护头部。
按实际射线距离指数衰减，再处理护甲和甲不足的余伤，最终结算整数生命/护甲；甲归零同步清除头盔。
HE 与刀经过同一整数生命结算，避免遗留小数 HP。商店展示部位倍率和保留两位小数的穿甲百分比。

Minecraft 命中盒按当前姿态高度分区：`.38` 以下腿、`.38–.55` 腹、`.55–.75` 胸/臂、`.75` 以上头；
这只是 Minecraft 适配，不等于 Valve 动画命中盒。霰弹同枪弹丸保留中间精度，合计后取整；
此选择有 [Valve 公开 Source SDK](https://github.com/ValveSoftware/source-sdk-2013/blob/master/src/game/shared/baseentity_shared.cpp)
合并弹丸伤害的实现依据，但尚未取得 CS:GO 分支专属的逐弹丸取整实测证据，不能据此声称百分之百引擎等价。
当前也仍未实现穿墙/人体穿透与历史位置回溯。

启动验证另发现已有 `40e2ac1` 的末影珍珠减伤注入匹配不到调用点。
核对 1.21.1 字节码，`EnderPearlEntity.onCollision` 的实际 owner 是 `Entity.damage`，
故把错误的 `LivingEntity.damage` 注入目标修正为 `Entity.damage`，保留 `amount * .5F`。
初次失败日志留在 `build/mygo-csgo-damage-server-console.log`。第二次启动因资源包端口 25586
与用户测试服冲突而停止，记录在 `build/mygo-csgo-damage-final-console.log`；最终验证改用 25588 / 25589。
用户的 25586 测试服保持运行。

最终 `./gradlew build` 成功，81 项单测、0 失败，日志 `build/mygo-csgo-damage-build.log`。
实服快照在 `build/mygo-csgo-damage-release/`，Fabric 1.21.1 / Loader 0.16.14 / Java 21，
使用四名 1.21.1 协议客户端执行 `damageRegression`，四回合现金全部由正常胜负获得。
58 项伤害/弹药/护甲/资源包检查全部通过，含潜行腹部；近距离 Glock 胸 13、腹 17、腿 21，
AK 戴甲胸 27、腹 34、腿 26；M4A4 戴盔爆头后剩 8 HP，AK 近距离戴盔爆头击杀，
AWP 打腿 86 留 14 HP 且不耗甲，满血胸部一枪击杀。上述数值包含各自实际射线距离衰减。
最终原始记录 `protocol-results.jsonl`，控制台 `build/mygo-csgo-damage-release-console.log`。

首轮末尾 AWP 胸部断言失败的原因是旧 14 HP 靶人仍站在新靶人的射线上，实际被旧靶人挡弹；
移开旧靶人后完整重跑 58 项通过，首次记录保留为 `first-attempt-results.jsonl`。
附加珍珠检查最初在大厅执行，受到大厅无敌保护；转入普通剑战并去掉护甲后，原始生命包在
`2026-10-25T06:27:59.466Z` 明确从 20 变为 17.5，证明实际落点伤害由 5 减为 2.5。
晚 1 秒的快照断言观察到和平难度回血后的 18.5，因此该附加晚快照断言仍保留为失败，
验证报告按首次实际伤害包判定，不将它改写成成功，也不混入 58 项伤害回归的通过数。

实服没有 Java ERROR/异常堆栈；Mineflayer 配方解析及跨传送移动告警仍是工具限制，
不能视为真实客户端画面验收。25588/25589 已正常停服保存，25586 未重启。
构建输出与本次实服类/资源快照逐文件一致；资源包 SHA-1 仍为
`d191135ce9ed995f22564ee916ce4237beed01f1`，不需重新上传。
交付 `dist/pvp-1.0.0-mygo-csgo-damage.jar`，1,711,774 bytes，SHA-256
`0e8d2a0a989a5faba91c81d6e3a5fcdcd6dbca6c57a94c8d7f4b4e387b0a3bb9`。

## 2026-10-25 CS 风格投掷物与定位音效

修改原因：旧投掷物只有右键一种固定速度，烟弹在半空也定时起烟且只有 12 秒，
玩家脚步仍由原版方块声决定，无法提供 CS 风格的高/低抛、静步和材质声音。
先审查 `MyGoSession` 输入与库存、`MyGoUtilities`、资源包生成器、声音事件和配置迁移。

实现与范围：

- 左键大力高抛、右键小力低抛；共用库存和 20 tick 冷却，投出后自动切回主枪/副枪/刀。
  右键继续按住不会消耗第二颗闪光。最多携带 HE 1、闪光 2、烟雾 1，合计 4。
- [Valve 的 2014 低抛更新](https://blog.counter-strike.net/2014/02/9585/)证实主/副键强弱投掷；
  legacy `items_game.txt` 的三类投掷物 `throw velocity=750` 换算到一格一米、20 tick/s。
  强抛约 `.857 block/tick`、弱抛约 `.257`，重力约 `.02032 block/tick²`；玩家实际位移参与动量，
  传送跳变不继承。出手点先扫掠墙面，飞行用子步扫掠/多次碰撞剩余时间，避免贴墙穿透和卡角。
- HE/闪光 30 tick 引信；HE 高斯型距离衰减、射线遮挡、护甲和 98/57 上限，保留友伤关闭/自伤有效。
  闪光按距离、视线遮挡与朝向影响自己、队友和敌人。烟弹引信后仍须落稳 4 tick 才起烟，
  烟幕 360 tick（18 秒），掉进虚空飞行超过 20 秒只清除，不凭空起烟。
  这些物理、HE 曲线、状态效果闪光和烟幕卡片是明确的 Minecraft 适配，未宣称复刻 Source 每帧行为、
  完整白屏淡出或耳鸣混音；纯服务端操作为点击即抛，未移植原版按住拔栓/松键抛出和双键中等力度。
- 实际位移驱动脚步：普通步距 1.2 格、最短 7 tick，疾跑 6 tick；地面速度低于 `.11 block/tick` 或潜行静音。
  五类石地/金属/木板/软地/水声，水中水平移动和梯子/藤蔓竖直攀爬单独取样，起跳、落地、切枪都有定位音。
  传送、救援、冻结和回合开始重置采样，避免假脚步/假落地。

音源与资源包：

- [Valve 公开政策](https://store.steampowered.com/video_policy)不支持把游戏资产拆出后单独分发，
  因此没有将网上提取的原声装入 ZIP；默认新音效全部是本项目原创 MIT 合成。
  生成器新增 `--authorized-audio-dir` / `--audio-license-file`，可导入管理员已有再分发权的
  单声道 OGG，保留授权/署名文件，不下载或提取 Valve 文件。
- 61 个声音事件、89 个单声道 Vorbis OGG；每种地面 4 个脚步变体、2 个落地变体；
  拔栓、3 类反弹、烟雾消散、起跳与切枪补齐。两个连续构建字节一致，全部 OGG 经 ffprobe/ffmpeg 解码检查，
  136 个原枪械模型/姿态/枪声文件字节不变。报告 `build/mygo-audio-pack-verification.json`。
- 资源包加载期间替换 110 个原版移动声音，避免客户端自预测与服务端脚步叠音；
  方块破坏/放置/击打和生物声不静音。入队等待时移动声也安静，退包后恢复。
- 新 ZIP `dist/mygo-csgo-utilities-sound-resource-pack.zip`，474,141 bytes，SHA-1
  `18a10f62921da47d16ffd66e2ed4c203e12b4f8d`；用户手动上传至
  `https://qfmaker.com/upload/files/lf192hqd.zip`，真实 HTTPS 下载后与内置 ZIP 逐字节一致。
  配置版本升为 5，旧默认 uxv5wsbc 自动升级；自定义 CDN 和显式本机 HTTP 保留。

首轮隔离实服 `build/mygo-utilities-sound-final/` 已通过 55 项检查：真实 HTTPS 下载/加载门禁、
正常回合经济购雷、携带限制/冻结拒绝、左右键力度与出手高度、自动切枪、HE 自伤/友伤/护甲/掩体、
1.5 秒引信、闪光正背面时长、落稳烟幕及 18 秒消散、实际 W 移动三类材质声音、潜行/站立静音、起跳落地。
随后审查发现原版梯子步音已屏蔽，而水平步距没有覆盖爬梯；补上竖直攀爬取样与木质脚步，并增加单测/实服梯子用例。
最终构建 `build/mygo-utilities-sound-build-final.log`，93 项单测、0 失败。

最终快照 `build/mygo-utilities-sound-release/` 在 25588 启动，`--classic-cdn-config` 从真实版本 4 / uxv5wsbc
迁移至版本 5 / lf192hqd；四名客户端全部经 HTTPS 下载新 ZIP、校验 SHA-1/CRC/JSON 并确认加载后入队。
`utilitiesRegression` 最终 **56 项通过、0 失败，无需重试**。新增梯子用例实际上升 2.58 格，
脚步计数 `12 → 15`，另一玩家收到木质定位步音；其余投掷物、烟幕、移动与静音用例再次全部通过。
原始协议记录 `build/mygo-utilities-sound-release/protocol-results.jsonl`，
控制台 `build/mygo-utilities-sound-release-console.log`，无服务器 Java ERROR/异常堆栈。
Mineflayer 配方解析和传送移动告警仍为工具限制；协议包和解码检查不能代替人工耳听/客户端渲染验收。
运行类/资源快照与最终构建输出逐文件一致；机器人已退出，25588 正常保存停服，原 25586 本地测试服没有重启。

交付 `dist/pvp-1.0.0-mygo-csgo-utilities-sound.jar`，1,918,265 bytes，SHA-256
`c29466aeea11b73667b5c21029383eb685664d286fea1855879a84492e1ad5a9`。
资源 ZIP 已由用户上传并核验，安装新版 JAR 后完整重启即可自动使用新地址；无需再次上传同一资源包。

### 点位核对来源

Mirage 和 Inferno 的初始 T/CT/A/B 相对位置按 Valve 游戏文件提取的 radar_info 对照：
[Mirage radar_info](https://raw.githubusercontent.com/MurkyYT/cs2-map-icons/main/data/radar_info/de_mirage.txt)、
[Inferno radar_info](https://raw.githubusercontent.com/MurkyYT/cs2-map-icons/main/data/radar_info/de_inferno.txt)。
根据用户提供的 Mirage Minecraft 俯视标注图，Mirage 最终点位以红色手绘中心为准，并吸附到可站立方块：
T=(5,-71)、A=(43,-25)、B=(-17,19)、CT=(29,28)。Inferno 的 T/CT 出生点保持原校验结果，
并按用户提供的 Inferno 俯视标注图将包点调整为 A=(-55,-41)、B=(10,50)。
两张地图再用服务端出生点校验和离线路线审计确认可站立、可到达。

## 2026-10-26 火雷与投掷物槽位修复

修改原因：燃烧瓶与燃烧弹共用热键 8，旧背包同步按枚举逐个写入，未持有的燃烧弹会覆盖
已买的燃烧瓶，而服务端库存仍占携带额度。右键也在遇到燃烧瓶枚举后直接返回，导致 CT
燃烧弹不能低抛。诱饵弹所在热键 9 还会被商店覆盖。

阅读并审查 `MyGoSession`、`MyGoUtilities.Type.heldAt`、`MyGoRoundEquipment` 和输入回调后，
改为每个投掷槽只同步一次，左右键均按实际库存选类型；热键 9 优先显示诱饵弹，用完恢复
商店。`/pvp mygo shop` 始终提供购买入口，携带上限和回合购买额度保持原有规则。

修复前独立 Fabric 1.21.1 服务器复现：T 买燃烧瓶后余额 $400、`MOLOTOV=1`，但背包槽为空；
CT 买燃烧弹后余额 $200、`INCENDIARY=1`，LIVE 右键后仍 `throwType=none`、`activeGrenades=0`。
记录位于 `build/mygo-fire-before-20261026/protocol-results.jsonl`。

交付代码在独立工作树构建，排除其他正在进行的空岛修改：`./gradlew build --warning-mode all`
成功，141 项单测、0 失败，无新增编译警告。四名真实协议客户端通过正常 $800 经济购买，
`fireUtilityRegression` 49 项全部通过：双方火雷真实背包槽/模型编号、冻结禁投、四颗上限、
T/CT 各自高抛与低抛、实体生成/引信/一次消耗一颗/切回手枪、真实方块右键无普通火球，
以及两颗诱饵弹依次使用后恢复商店。协议测试不等于客户端渲染或人工音效验收。

最终运行类/资源快照与独立构建一致，无服务器 Java ERROR/异常堆栈；临时测试服务器正常停服。
记录：`build/mygo-fire-release-20261026/protocol-results.jsonl`、`verification.json`，
构建/服务端日志：`build/mygo-fire-isolated-build.log`、`build/mygo-fire-release-console.log`。
交付 `dist/pvp-1.0.0-mygo-fire-fix.jar`，SHA-256
`afffaffe0af5630ff47bf5a3730e27037f8aaaa22e5520b817b81bdba56141fd`。
资源包 SHA-1 仍为 `88d5c5163c10774d5408d1bb8780d72794d74551`，无需重新上传。

## 2026-10-27 首页与“其他游戏”布局复验

按指定顺序把九个常玩入口放到首页，其余玩法归入“其他游戏”。`menu` 场景已同步新槽位，并覆盖 MyGO 选队步骤；上方旧槽位记录属于历史验证。

- 构建：`./gradlew build -I tools/mygo_prepare_test.gradle mygoPrepareTestRuntime` 通过；143 项单元测试中 142 项通过、1 项跳过，无失败或新增编译警告。
- 本地服务：`python3 tools/mygo_test_server.py --port 25635 --run-dir build/mygo-gui-layout-test`，加载本次编译快照。
- 协议客户端：设置 `MYGO_TEST_BOTS=ABC`、`MYGO_TEST_PORT=25635`、`MYGO_TEST_RUN_DIR=build/mygo-gui-layout-test` 后运行 `node tools/mygo_protocol_test.cjs`，依次输入 `{"op":"menu"}`、`{"op":"quit"}`。
- 49 项断言全部通过：九个首页入口顺序、其他游戏完整性、战桥/床战/练习返回层级、PvP 套件返回、MyGO 资源包及二人计数、分类实时计数、各首页快捷入队、退出队列、OP 主题选择，以及枪械空岛与普通空岛队列隔离。
- 首次启动遇到资源包 HTTP 端口被占用，停止本次测试服后换到空闲端口 25635/25636；最终服务日志无异常堆栈，只有本地离线验证所需的 offline-mode 提示。测试服与机器人均已停止。

证据：`build/gui-layout-validation/verification.json`、`build/gui-layout-validation/server-console.log`、`build/mygo-gui-layout-test/protocol-results.jsonl`。交付包：`dist/pvp-1.0.0-gui-layout.jar`。本次验证真实服务端菜单内容、点击与排队协议，不包含原版客户端画面验收。
