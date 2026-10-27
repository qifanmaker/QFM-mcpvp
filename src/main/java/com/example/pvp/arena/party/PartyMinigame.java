package com.example.pvp.arena.party;

import net.minecraft.item.Item;
import net.minecraft.item.Items;

/**
 * 已可运行的原版小游戏。编号对应原包 listminigamenumbers.mcfunction；
 * 未移植的条目仅记录在 party/catalog.json，不作为可玩的占位模式展示。
 */
public enum PartyMinigame {
    SPLEEF(408, "掘战 Spleef", Items.SNOWBALL, new String[] {
            "用铁锹挖掉对手脚下的雪、沙或泥土；弓箭也能破坏雪块。",
            "掉到场地下方即出局，最后留在场上的玩家获胜。"
    }),
    TILE_CAPTURE(412, "领地争夺 Tile Capture", Items.WHITE_WOOL, new String[] {
            "踩过白色羊毛占领领地，占领后会变为你的颜色。",
            "无法跳跃，也不能跨过或抢夺其他玩家的领地。",
            "右键回位棒返回最近的安全位置；时间结束时占领格数最多者获胜。"
    }),
    RED_LIGHT_GREEN_LIGHT(424, "红灯绿灯 Red Light, Green Light", Items.RED_DYE, new String[] {
            "绿灯前进，红灯立即停下；红灯期间移动会回到起点。",
            "最先跑完 145 格赛道者获胜，其余玩家继续争夺排名。",
            "90 秒结束时，未完赛者按最远进度和当前进度排名。"
    }),
    DROPPER(401, "跳水 Dropper", Items.WATER_BUCKET, new String[] {
            "穿过随机彩环落入塔底水池；失败会回到塔顶继续尝试。",
            "限时 90 秒，按最深进度排名，越早完成越好。"
    }),
    RAINBOW_RUN(413, "彩虹躲避 Rainbow Run", Items.WIND_CHARGE, new String[] {
            "躲避从侧面射来的风弹，留在彩虹平台上。",
            "风弹随时间增多，禁止跳跃，最后存活者获胜。"
    }),
    RUNNER(415, "疾跑危机 Runner", Items.LEATHER_BOOTS, new String[] {
            "不断前进！走过的地面会变色并在两秒后坠落。",
            "停步或蹲行会出局；下层也会受到落块影响，最后存活者获胜。"
    }),
    MEMORY_LANE(425, "记忆之路 Memory Lane", Items.DIAMOND_ORE, new String[] {
            "记住十五格矿石图案，再按手中矿石点击对应按钮。",
            "答错会暂停并再次展示原图；完成最多、到达进度更早者排名更高。"
    }),
    MEAT_SLAP(409, "肉掌接力 Meat Slap", Items.PORKCHOP, new String[] {
            "持肉者获得速度 IV，以蓄满力的一击把肉传给另一名玩家。",
            "传出后会被送到远处；两分钟内累计持肉时间最少者获胜。"
    }),
    ONE_SHOT_WONDER(428, "一箭奇迹 One Shot Wonder", Items.BOW, new String[] {
            "每次复活获得铁剑、弓和一支箭，箭命中即致死。",
            "击杀会补一支箭；射空就要用剑，90 秒内击杀最多者获胜。"
    });

    private final int sourceId;
    private final String title;
    private final Item selectionItem;
    private final String[] rules;

    PartyMinigame(int sourceId, String title, Item selectionItem, String[] rules) {
        this.sourceId = sourceId;
        this.title = title;
        this.selectionItem = selectionItem;
        this.rules = rules;
    }

    public int sourceId() { return this.sourceId; }
    public String title() { return this.title; }
    public Item selectionItem() { return this.selectionItem; }
    public String[] rules() { return this.rules.clone(); }
}
