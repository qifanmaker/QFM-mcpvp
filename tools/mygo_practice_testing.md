# MyGO 5v5 人机练习验证

真人加入、离开和断线会更换同一席位的实际玩家实体，需要在真实 Fabric 1.21.1 服务端检查队伍人数、资源包握手、GUI、枪械入口和回合状态。测试只运行在 `build/` 内的隔离快照，不使用正常 `run/` 世界或 25565 端口。

## 准备与运行

```bash
./gradlew build -I tools/mygo_prepare_test.gradle mygoPrepareTestRuntime
npm install --prefix /tmp/pvp-practice-protocol --cache /tmp/pvp-practice-npm-cache --no-audit --no-fund mineflayer@4.39.0
python3 tools/mygo_test_server.py --port 25657 --run-dir build/mygo-practice-5v5
```

项目 `run/eula.txt` 必须已记录 Minecraft EULA 接受，并有 JDK 21。另一个终端运行：

```bash
MYGO_PRACTICE_PORT=25657 MYGO_PRACTICE_RUN_DIR=build/mygo-practice-5v5 node tools/mygo_practice_regression.cjs
```

核心检查完成后，可在同一服务端观察人机实战；专项模式需要含 `gun` 的最新诊断字段：

```bash
MYGO_PRACTICE_PORT=25657 MYGO_PRACTICE_RUN_DIR=build/mygo-practice-5v5 MYGO_PRACTICE_MODE=observe node tools/mygo_practice_regression.cjs
```

观察上限默认 180 秒，可用 `MYGO_PRACTICE_OBSERVE_MS` 调整；`MYGO_PRACTICE_MAP`、`MYGO_PRACTICE_DIFFICULTY` 分别选择地图和难度。它检查至少六名存活人机实际行走超过十格、消耗弹药、造成虚拟 HP 伤害、击杀及推进回合。移动度量要求前后快照均存活、同一回合、均为 LIVE 阶段，且相邻快照位移小于十二格，排除死亡等待室传送和回合重生造成的假阳性。正常枪声有传播距离，观察者站在远处不必收到声音包；这不等价于检查人物模型和音效实际渲染。

另可在新的普通地图、尚未占用的专家房间运行安拆包专项：

```bash
MYGO_PRACTICE_PORT=25657 MYGO_PRACTICE_RUN_DIR=build/mygo-practice-5v5 MYGO_PRACTICE_MODE=objective node tools/mygo_practice_regression.cjs
```

该模式先用五名静止真人填满 CT，让五名 T 人机实际走路、到包点并完成安装。安装后给 T 警戒人机施加原版失明效果以隔离拆包动作，再让持真实拆包器的 CT 真人在 C4 旁离场，由接管的 CT 人机完成原版拆包引导。所有目标动作仍通过正式 MyGO 会话实现，不直接设置爆破成功或胜负。可用 `MYGO_PRACTICE_OBJECTIVE_MAP` 切换地图；首次运行应使用没有既有专家房间的隔离服务端，以保持购买阶段起始资金。

受伤、阵亡和 C4 席位交接专项：

```bash
MYGO_PRACTICE_PORT=25657 MYGO_PRACTICE_RUN_DIR=build/mygo-practice-5v5 MYGO_PRACTICE_MODE=handover node tools/mygo_practice_regression.cjs
```

该模式将五个 T 席位先替换为真人，检查 C4 没有丢失，再让携包真人离开。为了可重复地产生受伤状态，仅用 OP 将接管的 T 人机和一名 CT 人机摆到地图既有射击测试点附近，实际伤害仍来自原有枪械。随后检查接替受伤席位不会回血、接替阵亡席位不会复活或重置回合、阵亡真人离开补回的人机仍然阵亡。可用 `MYGO_PRACTICE_TRANSFER_MAP` 指定地图，默认 Mirage；同样应使用未存在该新手房间的隔离服务端。

可用 `MYGO_PRACTICE_NODE_MODULES` 指定测试依赖目录。驱动器使用 `MyGoCheckA`～`L`，其中 A～F 沿用隔离服务端已有 OP，其他账号只行使普通玩家的加入、选队和退出权限。并发真实连接不超过隔离服务端的 12 人限制；人机不得消耗在线连接名额。

## 核心检查

- 错误难度、队伍和地图不会入场；首次资源包加载前不能接管席位，下载 SHA-1 必须吻合。
- 练习分类 → 五档难度 → 三张地图 → T / CT / 自动，实际通过 GUI 开局。
- 每个房间始终十席、每队五席；一真人对应九人机，两真人对应八人机。
- 同一房间每个参赛 UUID 唯一，选队生效；冻结购买阶段接管保留生命和弹药。
- 退出和断线重新补回人机；第五名 T 真人可加入，第六名必须被拒绝。
- 全房十名真人时不剩人机，第十一名不能挤进房间；其他队有空位时仍可加入。
- PvP/MyGO 练习不占战斗退出物品槽，状态命令与 `/pvp practice leave`、`/hub`、`/pvp tpout` 可用。
- 正式 MyGO 仍能通过原有队列启动双人比赛、查看枪械状态并打开军械库。

测试通过仅限 OP 的 `/pvp mygo practiceinfo` 获取结构化房间及席位状态；普通输入仍走真实客户端协议。结果保存在隔离目录的 `mygo-practice-core-results.json`、`mygo-practice-observe-results.json`、`mygo-practice-objective-results.json`、`mygo-practice-handover-results.json`，服务端异常以 `logs/latest.log` 为准。协议客户端的物品组件解码警告不能替代服务端验证。

这组检查不代表客户端资源包的画面、枪械音效或真人主观难度手感已经验收。测试结束退出全部测试客户端；使用测试 OP 的 `/stop` 正常关闭隔离服务器。
