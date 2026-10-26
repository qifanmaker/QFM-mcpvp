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
