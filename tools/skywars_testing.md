# 空岛战利品与清场回归

这组测试在独立 Fabric 1.21.1 服务端运行，覆盖普通空岛和枪械空岛共用的地图、物资及清场流程。
它会把测试入口编译到 `build/` 下的 Mod 快照，临时添加快照内的入口声明；生产源码、生产 JAR、
正常 `run/` 世界及 25565 服务均不改动。测试完成后自动正常停服，结果保存在隔离目录。

先按 AGENTS.md 构建并准备 Loom 类路径：

```sh
./gradlew build --warning-mode all
./gradlew -I tools/mygo_prepare_test.gradle mygoPrepareTestRuntime
python3 tools/skywars_server_test.py --run-dir build/mygo-skywars-fixed-1 --port 25593
```

为保留证据，已有 `skywars-results.json` 的目录不能重复使用；复跑时换一个新目录名。

基线复现使用固定旧版本的 `SkyWarsLayout`、`SkyWarsLootPolicy` 和 `SkyWarsMapGenerator`，
只编译进独立快照，不回退工作区：

```sh
python3 tools/skywars_server_test.py --baseline --baseline-ref 80eadba \
  --run-dir build/mygo-skywars-baseline-1 --port 25593
```

`baseline-commit.txt` 记录完整提交号。基线报告中“通过”表示成功复现旧错误，不能当作修复验收。

验证内容：

- 同地图种子生成两次实际普通/枪械地图：地形写入完全一致，箱子库存不同，补货再次抽取。
- 在真实区块放置箱子、木桶、潜影盒和散落物，移除区块票并确认实体区块确实卸载。
- 通过清场区块票等待旧物品从实体存储载入，执行共享清场，检查不因拆箱或邻居更新生成新掉落。
- 再次卸载和加载区域，检查旧物品不重现；相邻区域的物品保留，下局新掉落及正常拆箱继续有效。
- 普通/枪械完整地图分别经 `MapBuildQueue.flush` 分帧写入世界，再按真实地图半径清场，记录等待和清场耗时。

2026-10-26 实测：基线 13 项诊断全部通过，复现重复库存及清场后重现物品；修复版 30 项检查全部通过。
完整地图半径 110，普通/枪械的区块票等待分别为 4/1 tick，清场分别为 64/51 ms。
证据在 `build/mygo-skywars-baseline-1/` 与 `build/mygo-skywars-fixed-1/` 的
`skywars-results.json`、`server-console.log`。两次服务器均正常停止。

此工具直接验证真实服务端地图/库存/实体生命周期，不连接渲染客户端，也不声称验证客户端画面或操作手感。

## 完整对局流程

另用两个真实协议客户端验证生产入口和 `MatchManager` 的延迟清场（不注入测试入口）：

```sh
python3 tools/mygo_test_server.py --run-dir build/mygo-skywars-matches --port 25627
# 在另一终端执行；Mineflayer 依赖安装说明见 mygo_testing.md。
MYGO_TEST_RUN_DIR=build/mygo-skywars-matches MYGO_TEST_PORT=25627 \
  node tools/skywars_match_regression.cjs
```

客户端完成后自动退出，并撤销测试使用的强制加载；服务器终端输入 `stop` 正常停服。
2026-10-26 实测 15/15 通过：普通和枪械空岛分别排队、`/pvp start OVERWORLD` 开赛、
`/hub` 退赛触发胜负、回大厅、延迟清场；第二场复用同一区域。重新加载区块后，
两局标记掉落和箱内 7 颗下界之星均不存在，第二场再次检查第一场也无残留。
资源包通过本地 HTTP 下载并校验 SHA-1，客户端仅报告无渲染加载。

结果见 `build/mygo-skywars-matches/skywars-match-verification.json`、
`skywars-match-results.jsonl`、`server-console.log`。服务端没有 ERROR/Java 异常，
有一条协议客户端传送移动警告；客户端有已知配方解析警告。首次中文命令参数解析失败的
证据保留在 `first-chinese-command-*`，最终测试使用等价英文主题参数。

## 弹药数量与背包移动

修改原因：用户反馈拾取、换弹或移动枪械后弹药减少。检查真实物品的弹匣、备弹和散装弹药总和，
并单独检查没有新开火输入时是否仍在扣弹；不能只把背包里消失的散装弹药视为丢失。

```sh
./gradlew build --warning-mode all
./gradlew -I tools/mygo_prepare_test.gradle mygoPrepareTestRuntime
python3 tools/mygo_test_server.py --run-dir build/mygo-skywars-ammo-final --port 25633
# 另一终端；依赖安装见 mygo_testing.md。
MYGO_TEST_RUN_DIR=build/mygo-skywars-ammo-final MYGO_TEST_PORT=25633 \
  node tools/skywars_ammo_regression.cjs
```

每次复跑使用新隔离目录。机器人完成后退出，服务器终端输入 `stop` 正常停服。
测试用两个真实协议客户端完成排队、资源包门禁和开赛，在隔离世界注入明确数量的枪械、弹药，
执行实际物品实体拾取、F 换弹、背包点击、数字键交换、Q 丢枪及拾回，记录客户端物品组件和
服务端 `/data get` 的库存。还检查手持枪优先补弹、数量说明、相同型号/弹量的枪不会继承换弹、
单击/双击/切枪后停止扣弹、连续输入可射击，以及同格更换物品不会重置尚未到期的射击冷却。

2026-10-26 基线快照取自 `7444050`（修改前准备）：22 项检查中 6 项失败，
复现 AK 单击 30→27、双击 30→26、快速切回后继续扣弹、相同枪交换后继续扣弹、
拾取弹药先流入备用枪、没有弹药数量说明。拾取、换弹、移槽和丢拾的基础总数守恒检查通过。
证据在 `build/mygo-skywars-ammo-baseline/skywars-ammo-verification.json` 和
`skywars-ammo-results.jsonl`，首轮证据保留为 `first-pass-*`。

修复取消没有真实输入的续射，切槽取消换弹，运行时绑定真实枪物品并保留同槽冷却；
散装弹药优先补充手持的匹配枪，物品说明显示弹匣与备弹。此协议测试没有客户端渲染器，
不能代替原版客户端的画面和按键手感验收。

修复版实服 24/24 项通过：AK 单击 30→29、双击 30→28，停下、切槽和同弹量换枪后不再额外扣弹；
拾取、换弹、移动、丢拾均保持弹药总数，备弹去向与说明正确。构建通过，143 项单测中
142 项通过、1 项跳过，无失败。最终快照与交付编译类逐字节一致；结果在
`build/mygo-skywars-ammo-final/skywars-ammo-verification.json`、`skywars-ammo-results.jsonl`、
`ammo-bot-console.log` 和 `server-console.log`。服务器无 ERROR、Java 异常或移动警告，
仅有预期的离线模式警告；协议客户端仍有已知配方解码警告。测试服务器均已正常停止。
