# Party2 常驻地图

为减少每轮等待，28个已接入小游戏的33套固定地图配置在开服后后台分帧预热。113个几何资源预先解码缓存；按人数变化的Spleef、Tile、Runner使用各自配置。随机路线、玩家平台、NPC、装备、积分和五轮赛制继续按实际回合生成。

场地位于竞技场维度的独立Z带。等待台保留原比赛区域；每场租用独占场地，同一小游戏并发时分配另一个副本。空闲场地持有level33 FULL票，保留区块而不永久运行方块/实体tick；对局额外持有level31活动票，并保持到复原完成。

每次原生方块写入前记录首次旧状态，含流体、活塞及动态模板写入。归还时恢复改动、原有方块实体NBT，清临时实体与计划tick，保留地图主体。只有复原成功才允许再次出租；失败副本隔离。即使没有活动比赛，常驻地图仍受交互、随机tick、流体和爆炸保护。所有预热/复原队列共用20ms地图预算及本tick开始后40ms的截止时间。

缓存命中仍保留生成器顺序：如果之前排队的AIR清除与模板相交，重新排入该模板；large Spleef清屏障时也可读取已常驻的世界状态。

本机Fabric1.21.1验证：33配置全部原生逐格校验通过，首次后台预热约48.9s（资源解码及服务器启动另计）；直接原生接口少量Rocket改动复原39ms，原槽复租0.02ms。真实3玩家两局Rocket复用同一场地，第二次选定地图到PLAY为8.105s，其中准备倒计时占8s；没有再次粘贴1293万格。数据随硬件、负载和启动缓存变化，不等同于对外网客户端延迟的承诺。

验证命令：

```sh
./gradlew build -I tools/mygo_prepare_test.gradle mygoPrepareTestRuntime
python3 tools/party2_resident_maps_server.py --run-dir build/mygo-resident-maps-check
NODE_PATH=/tmp/pvp-practice-protocol/node_modules node tools/party2_resident_session_regression.cjs
```

隔离测试使用独立端口、不可变构建快照。pool回归包含完整33配置地形、不同租约隔离、预热取消、原方块及原BE仅NBT变更、新BE、延迟tick、实体回收、FULL票保持与复租；玩家回归使用真实GUI/协议，原图终点传送和一个AIR格的改动明确标作测试fixture。正式JAR不包含诊断入口。
