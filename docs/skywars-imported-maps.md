# 空岛战争外部地图池

普通空岛和枪械空岛共用地图池；地图随模组打包，服务端开局无需联网。保留原有程序生成地图，并默认将两张网络地图加入随机抽取。

| 地图 ID | 游戏内名称 | 原作者 / 原作 | 来源出生岛 | 物资箱 |
| --- | --- | --- | --- | --- |
| `forest` | 林间群岛 | [Aquila4 — Skywars map forest theme](https://www.planetminecraft.com/project/skywars-map-forest-theme/) | 12 | 每岛 3 箱，中心 8 箱，共 44 箱 |
| `unresting_desert` | 不眠沙漠 | [Lord_Therd — The Unresting Desert](https://www.planetminecraft.com/project/free-download-skywars-map-the-unresting-desert/) | 12 | 每岛 3 箱，中途大岛 4 箱，中心 4 箱，共 44 箱 |

按当前空岛人数上限，支持 2～8 人开局；参赛者均匀分散在来源出生岛上。未分配出生岛继续保留地形和物资。外围箱使用出生岛物资，其余公共岛箱使用中岛物资，枪械空岛则使用对应枪械物资表。开局、补货共用全部真实箱位，丢弃原作者箱子库存和延迟 LootTable。

出生点位于可落脚的实心地面上，头顶为本服 5 秒职业选择玻璃笼保留空间。原地图高度与路线保留；地图边界、图腾救援、缩圈、赛后方块和掉落物清理都按实际地图范围处理。

## 管理配置

首次抽取地图时生成 `config/pvp/skywars/maps.json`。此文件独立于主配置的版本覆盖；每次新对局读取，修改后下一局生效。

```json
{
  "enabled": true,
  "generatedWeight": 2,
  "forcedMap": "",
  "maps": {
    "forest": 1,
    "unresting_desert": 1
  }
}
```

- 默认程序地图池占 50%，森林和沙漠各占 25%。程序地图池仍继续抽取原来的主题和结构。
- 把某张地图权重设为 `0` 可停用；`generatedWeight: 0` 可只抽网络地图。
- `enabled: false` 使用原有程序地图。
- `forcedMap: "forest"` 或 `"unresting_desert"` 固定网络地图，清空后恢复随机。固定图优先于权重。
- OP 明确指定原有主题时仍按指定主题生成地图。
- 地图损坏、缺失、出生点或箱位校验失败时跳过该图并记录日志；没有可用选项时回到程序地图。

## 来源、许可与适配

两张地图均从作者发布页的公开 schematic 下载；原始下载不包含插件代码，本项目仅转换静态建筑方块。

- Aquila4 允许服务器使用及整合入其他项目，要求完整署名。作者原文："You are allowed to use this build on your server or incorporate in your own projects as long as you give full credit."
- Lord_Therd 声明地图免费供所有人使用，服务器使用需署名（"FREE FOR EVERYONE TO USE" / "GIVE ME CREDIT"）。

完整作者名与原作链接会显示在对局开局聊天中，并保留在地图元数据中。地图著作权属于原作者；上述许可仅适用于这些地图，不改变其他模组代码或资源的许可。

适配包括：将旧数字方块 ID 转为 1.21.1 方块状态；清除原实体、原物资、床和编辑标记；定位安全出生点；登记全部物资箱；统一空岛物资与补货；对齐竞技场中心；按原图设置决战范围。

原始文件 SHA-256：

```text
forest.schematic
11c11c5f2c71f101b716225c6eb185886001980b22f3695ae34396eb99ba8807

desertskywars9429362.schematic
ca8d464f63a727bf6138ac1a6bec02a173eb24abdcdc92f7d7d81b543adc49aa
```

转换脚本：`tools/import_skywars_maps.py`。最终资源位于 `src/main/resources/maps/skywars/<地图ID>/`，包括 `metadata.json` 和压缩原生结构 `terrain.nbt`。结构仅含静态方块，不复制原地图方块实体 NBT 或实体。

## 验证

`tools/skywars_imported_maps_regression.cjs` 在独立 Fabric 1.21.1 服务器中验证地图选择、出生笼、真实箱位的开局/补货、枪械物资和离场清理。运行所需的 Mineflayer 依赖放在仓库外，由 `SKYWARS_TEST_NODE_MODULES` 指定；独立服务器路径和端口分别由 `SKYWARS_TEST_RUN_DIR`、`SKYWARS_TEST_PORT` 指定。
