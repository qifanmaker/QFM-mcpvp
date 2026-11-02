# 建图与清图性能验证

原先 Party 每 tick 固定写入4ms，闲服也受限。现在所有 MapBuildQueue 共享最多20ms工作预算，并检查本 tick 实际开始后40ms的截止时间。繁忙时自动退让，活跃队列均分，实际超支也计入总账。原生单次写入、GC或系统调度仍可能超过预算；这不是硬实时保证。普通 Match 不再在20秒后无界强制写完整图，慢建图完成后倒计时也不会误报异常。

快照保留原生区块计数、四种高度图、光照与方块实体；不直接覆盖 ChunkSection。RLE逐段解码，快照复用坐标并省去重复的比赛查询。建图先等实际场景的异步区块票就绪。

2026-11-02，本机独立 Fabric1.21.1 完整 Rocket 场景12,933,322格：

| 阶段 | 原固定4ms | 自适应+异步预载 | 提速 |
| --- | ---: | ---: | ---: |
| 建图（含区块等待） | 41.73s | 11.50s | 3.63倍 |
| 清图 | 43.78s | 11.29s | 3.88倍 |

每阶段后等待20原生tick，再逐格核对全部13M真实世界状态；清图要求全AIR。另验证6,038,297格X轴RLE的Take Flight。30ms/tick真实CPU负载下，队列自动缩到约9ms；该负载用例所有flush均小于14ms。冷进程、GC与运行机器会影响耗时，不把本机数字视为服务器承诺。

运行（需先`./gradlew build -I tools/mygo_prepare_test.gradle mygoPrepareTestRuntime`）：

```sh
python3 tools/party2_scene_throughput.py --run-dir build/mygo-throughput-rocket --budget-ms adaptive --wait-chunks
python3 tools/party2_scene_throughput.py --run-dir build/mygo-throughput-flight --scene take_flight --budget-ms adaptive --wait-chunks
python3 tools/party2_scene_throughput.py --run-dir build/mygo-throughput-loaded --budget-ms adaptive --wait-chunks --load-ms 30
python3 tools/party2_scene_queue_server_test.py --run-dir build/mygo-scene-queue-check
NODE_PATH=/tmp/pvp-practice-protocol/node_modules node tools/map_build_lifecycle_regression.cjs
```

生命周期用两个实际协议客户端，完成真实空岛建图后显式把 ticks/buildTicks 同加400，复现旧版倒计时看门狗边界；不改变真实倒计时、地形、队列或状态。随后验证进入ACTIVE、胜负、回城和延迟清理。完整Rocket协议回归还覆盖动态新增方块和实体清理。所有诊断entrypoint只加入隔离快照，正式JAR仅有PvPMod。
