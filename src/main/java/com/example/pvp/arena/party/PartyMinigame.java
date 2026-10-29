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
    }),
    CAVE_PARKOUR(404, "洞穴跑酷 Cave Parkour", Items.CARROT_ON_A_STICK, new String[] {
            "穿过随机生成的洞穴路线，踩过检查点灯保存进度。",
            "右键回位棒或丢出它返回检查点；限时 200 秒，先到终点者排名更高。"
    }),
    PLATFORMS(414, "平台夺宝 Platforms", Items.DIAMOND, 4, 4, new String[] {
            "四人同时选择跳跃方向，在平台上收集钻石。",
            "完成六次选路后按钻石数排名；每颗钻石计两分。"
    }),
    WHACK_A_MOB(402, "怪物打地鼠 Whack-A-Mob", Items.NETHERITE_AXE, new String[] {
            "蓄满力挥斧击中升起的怪物；盔甲僵尸需要两次攻击。",
            "僵尸 1 分、猪灵 2 分、凋灵骷髅与盔甲僵尸 3 分、末影人 5 分，烈焰人扣 1 分。",
            "90 秒后停止生成，剩余目标消失后按得分排名。"
    }),
    MONSTER_SHOOT(403, "怪物射击 Monster Shoot", Items.BOW, new String[] {
            "用无限弓射击从天空落下的怪物，箭可以穿透目标。",
            "僵尸 1 分、苦力怕 2 分、骷髅 3 分、蠹虫 5 分。",
            "60 秒后停止生成，剩余目标消失后按得分排名。"
    }),
    RAFT_RACERS(405, "筏艇竞速 Raft Racers", Items.BAMBOO_RAFT, new String[] {
            "驾驶原生竹筏穿过冰面赛道，按顺序通过检查区域并完成两圈。",
            "右键重乘自己的筏艇；右键或丢出回位棒召回船只，冷却十秒。",
            "限时三分钟，先完赛者排名更高，其余按圈数与过圈顺序排名。"
    }),
    KNOCKOUT(406, "击退擂台 Knockout", Items.STICK, new String[] {
            "用击退 I 木棍把对手打入熔岩，每人三条命。",
            "首次击杀将木棍升级为击退 II；十五秒没有造成或受到伤害会发光。",
            "最后存活者获胜。"
    }),
    SKY_FLY(407, "穿环滑翔 Hoopin’ n’ Swoopin’", Items.ELYTRA, new String[] {
            "使用鞘翅沿三十六个随机圆环一路向下滑翔，每人一次机会。",
            "穿过更多房间获得更高进度；错过圆环落在铁栏上会失去鞘翅。",
            "所有人抵达底部后，按所在房间进度排名。"
    }),
    WEAPON_DEVOLUTION(410, "武器降级 Weapon Devolution", Items.TRIDENT, new String[] {
            "用激光枪、下界合金斧、三叉戟、木剑和锐利雪球依次取得击杀。",
            "每次击杀进入下一种武器；死亡可以复活，最后阶段完成者领先。",
            "限时两分钟，按完成阶段和取得进度的先后排名。"
    }),
    TEDIOUS_TASKS(411, "繁琐任务 Tedious Tasks", Items.BELL, 2, 4, new String[] {
            "完成八项随机且不重复的任务：战斗、珍珠、敲钟、合成、交易、跑图、调音与回血。",
            "任务使用真实交互；死亡重置当前任务，可以干扰其他玩家。",
            "限时四分钟，按完成任务数和取得进度的先后排名。"
    }),
    HORSE_RACE(416, "要塞赛马 Horse Race", Items.SADDLE, new String[] {
            "驾驶自己的亡灵马穿过随机下界要塞赛道，按空格蓄力跳过障碍。",
            "经过金块保存检查点，右键或丢出回位旗返回检查点，完成两圈。",
            "按路线限时进行，按检查点进度与取得进度的先后排名。"
    }),
    SPEED_BUILDERS(417, "速建专家 Speed Builders", Items.GOLDEN_PICKAXE, new String[] {
            "对照旁边的彩色模型，在建造区按原方向完整复制。",
            "四幅模型随机且不重复，每幅颜色独立随机；金镐可拆除混凝土。",
            "按钮清空并重试当前模型，限时三分钟，按完成数量和进度先后排名。"
    }),
    STEVE_SAYS(418, "史蒂夫说 Steve Says", Items.APPLE, new String[] {
            "听清史蒂夫的指令，只有带有正确前缀的指令才应执行。",
            "在教室完成跳跃、蹲下、调音、切换道具等随机动作。",
            "完成二十二条指令后，按累计得分排名。"
    }),
    POWER_TOWERS(419, "速度之巅 Power Towers", Items.NOTE_BLOCK, new String[] {
            "面向自己的音符盒连续右键调音，十秒内尽量升高彩色塔。",
            "倒计时结束后拆塔，按真实落下的彩色方块计分。"
    });

    private final int sourceId;
    private final String title;
    private final Item selectionItem;
    private final int minimumPlayers;
    private final int maximumPlayers;
    private final String[] rules;

    PartyMinigame(int sourceId, String title, Item selectionItem, String[] rules) {
        this(sourceId, title, selectionItem, 2, 10, rules);
    }

    PartyMinigame(int sourceId, String title, Item selectionItem, int minimumPlayers, int maximumPlayers, String[] rules) {
        this.sourceId = sourceId;
        this.title = title;
        this.selectionItem = selectionItem;
        this.minimumPlayers = minimumPlayers;
        this.maximumPlayers = maximumPlayers;
        this.rules = rules;
    }

    public int sourceId() { return this.sourceId; }
    public String title() { return this.title; }
    public Item selectionItem() { return this.selectionItem; }
    // Platforms 的原命令链只有四个座位；人数不足不能作为可投票项目，否则会等不到第四次选择。
    public boolean supportsPlayers(int count) { return count >= this.minimumPlayers && count <= this.maximumPlayers; }
    public String playerRequirement() {
        return this.minimumPlayers == this.maximumPlayers ? this.minimumPlayers + " 人"
                : this.minimumPlayers + "~" + this.maximumPlayers + " 人";
    }
    public String[] rules() { return this.rules.clone(); }
}
