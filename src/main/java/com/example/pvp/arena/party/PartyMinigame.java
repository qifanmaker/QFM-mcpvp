package com.example.pvp.arena.party;

import net.minecraft.item.Item;
import net.minecraft.item.Items;

/**
 * 第一阶段可运行的原版小游戏。编号对应原包 listminigamenumbers.mcfunction；
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
