# MyGO 服务端开镜适配

需要修正旧版任意枪械都可开镜、AWP 只有一级浅倍率的问题。枪械目录决定镜位，`MyGoScope` 只发送视角能力包，不改变玩家真实能力或移动属性，南瓜继续提供镜框。

## 原版客户端依据

已查阅 Loom 缓存内 Minecraft 1.21.1、Yarn build.3 的合并源码：

- `AbstractClientPlayerEntity.getFovMultiplier` 第 76 行使用 `(movementSpeed / abilities.walkSpeed + 1) / 2`，第 98 行按用户 FOV 效果设置混合。
- `ClientPlayNetworkHandler.onPlayerAbilities` 第 1825 行只将包内 `walkSpeed` 写入客户端能力，不修改移动属性。`PlayerAbilitiesS2CPacket` 第 42 行按原始浮点读取，允许有限负值。
- 全部源码中，能力的 `getWalkSpeed` 除上述视角与协议外，仅在玩家读取 NBT 时初始化移动属性；服务器的实际能力没有被本适配改动。
- `GameRenderer.updateFovMultiplier` 第 789 行将视角系数下限设为 0.1；本功能最深 10/70 高于该下限。
- 不使用望远镜物品：其视角分支固定返回 0.1；`MinecraftClient.handleInputEvents` 第 2057–2069 行会在使用物品时吞掉左键输入，松开右键还会停止物品使用。

从原版公式反解包内基准为 `movementSpeed / (2 * targetFov / 70 - 1)`。深倍率对应负的包内基准，客户端真实移动仍读取正的移动属性。每 tick 在移动属性更新之后检查，只有镜位或所需基准变化时才发包。关闭镜位、切枪、死亡及退出使用原版能力更新恢复。

## 验证与限制

`MyGoScopeTest` 从测试 classpath 中真实 1.21.1 客户端类提取 `getFovMultiplier` 方法字节码，只将实体输入方法和属性注册表查找换成轻量适配器，保留原始计算指令；同时直接执行原版 `GameRenderer.getFov` 方法，覆盖 45、40、15、10 度及四种不同移动属性。普通 JUnit 没有 Fabric 的注册表访问扩展，不能完整引导实体层，因而使用这个明确受限的适配器。原版包 codec 往返验证负基准完整传输、权限不变及服务器能力维持正常。此为客户端数值逻辑验证，不包含完整客户端启动或 OpenGL 画面验收。

2026-10-24 执行 `./gradlew test --tests '*MyGoScopeTest'`：4 项通过，0 失败；日志位于 `build/mygo-csgo-scope-test.log`。

对应角度以玩家基础 FOV 70、FOV 效果 100% 为参考。玩家关闭 FOV 效果时不会获得放大，服务器无法强制覆盖客户端设置；Minecraft 和 CS:GO 投影及画幅定义也不相同，不应把这里的角度表述为逐像素复刻。

实服协议验收应同时检查镜位、能力包中的 `walkingSpeed`、正的实际移动属性和左键弹药消耗；二级 AWP 应出现负的能力基准，关闭镜位后应恢复正常正基准。服务器能力值本身应始终保持原值。
