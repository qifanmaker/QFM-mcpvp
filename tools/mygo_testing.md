# MyGO 隔离服务器验证

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
