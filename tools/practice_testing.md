# PvP 人机练习验证

新增玩法需要验证真实玩家实体的移动、战斗、投掷物、容器 GUI 和断线生命周期。单元测试只检查难度选择与公平性约束；协议回归在独立的 Fabric 1.21.1 服务端执行，不使用正常 `run/` 的世界或 25565 端口。

## 运行

```bash
./gradlew build -I tools/mygo_prepare_test.gradle mygoPrepareTestRuntime
npm install --prefix /tmp/pvp-practice-protocol --cache /tmp/pvp-practice-npm-cache --no-audit --no-fund mineflayer@4.39.0
python3 tools/mygo_test_server.py --port 25637 --run-dir build/mygo-practice-test
```

在另一个终端运行：

```bash
PRACTICE_TEST_PORT=25637 node tools/practice_regression.cjs
PRACTICE_TEST_PORT=25637 PRACTICE_TEST_SUPPLEMENTAL=1 node tools/practice_regression.cjs
```

沿用隔离服务端脚本中的测试 OP（`MyGoCheckA`～`F`）。需要项目 `run/eula.txt` 已记录 EULA 接受，且本机有 JDK 21。可通过 `PRACTICE_TEST_NODE_MODULES` 和 `PRACTICE_TEST_RUN_DIR` 指定测试依赖及隔离目录。完成后用测试 OP 的 `/stop` 正常关闭服务端。

## 验证范围

- 分类 → 五档难度 → 八种套件，返回导航及 GUI 实际开局。
- 五档人机生成、使用完整套件物品栏、倒计时保护、人机不占在线人数。
- 人机真实移动与近战命中；玩家真实攻击、伤害和击退；弓箭实体与命中。
- 第三人伤害隔离，致死胜负结算、自动重开及退出/断线后的回收。
- 八种套件均可入场；金苹果、治疗喷溅药水和速度药水真实使用与库存消耗。
- 铁套 PvP 保留第九格岩浆桶，水桶灭火与水源回收，鱼竿实际投掷。
- 旧回合投掷物加载时移除；普通弓箭保持有效。
- 搭路、珍珠练习的补给与原退出工具不变。

结果保存在隔离目录的 `practice-protocol-results.json`、`practice-supplemental-results.json`，服务端日志在 `logs/latest.log`。检查日志中是否有异常堆栈。测试客户端可能输出非致命的 `SlotComponent` 解码警告，补给验证通过服务端原生 `/data get` 查询，不能把客户端警告当作 Mod 异常。

这组测试通过原版协议操作容器和战斗，覆盖服务端行为；不验证客户端实际渲染、人物皮肤或真人主观操作手感。实际游戏还可通过「其他游戏 → 练习模式 → PvP 人机 1v1」体验难度差异，使用 `/pvp practice leave`、`/hub` 或 `/pvp tpout` 离开。

## 近战伤害与真实暴击回归

只检查“掉血”无法发现挥手动画提前清空攻击蓄力的问题。专用探针只编译进隔离快照，记录原版伤害事件、武器属性、下落状态与扣血量，不进入发布的 Mod。测试收集自然 AI 攻击，关闭自然回复，并在受伤后的下一 tick 回血以避免回合提前结束。

```bash
python3 tools/practice_combat_server_test.py --port 25647 --run-dir build/mygo-practice-combat
```

另一个终端运行，完成后驱动器会关闭隔离服务端：

```bash
PRACTICE_TEST_PORT=25647 PRACTICE_TEST_RUN_DIR=build/mygo-practice-combat node tools/practice_combat_regression.cjs
```

验证满蓄力无附魔铁剑对完整无附魔铁甲的普攻为 6 点原始伤害、实际扣血约 3.12 点；暴击为 9 点原始伤害、实际扣血约 5.22 点（1 心 = 2 点生命）。暴击还必须有真实的腾空、正下落距离、非冲刺状态和原版暴击动画，不能仅以动作栏文案判断。另验证拉弓后切回剑的第一轮攻击，防止沿用弓的空手属性或换手冷却尚未更新就挥剑。

每次运行使用新的隔离目录以保留证据，结果见该目录的 `practice-combat-results.json` 和 `practice-combat-events.jsonl`。修复前的专项复现中，24 次铁剑攻击均只有 1.20768 点原始伤害、实际扣血约 0.51224 点，且没有真实暴击。
