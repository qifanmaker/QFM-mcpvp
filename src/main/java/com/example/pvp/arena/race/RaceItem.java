package com.example.pvp.arena.race;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 「亦可赛艇」的道具种类。
 *
 * <p><b>识别方式</b>：一律看 {@code CUSTOM_DATA} 里那个唯一 NBT key 的值（和项目其它自研物品一致），
 * 不看物品类型也不看名字 —— 玩家手里一个普通的雪球/墨囊绝不会被误认成道具。
 *
 * <p><b>为什么道具都做成"物品栏里的一件东西"</b>：船的位移是客户端权威的（服务端改速度会被
 * 每 tick 的载具位置包覆盖，实测无效），所以真正能生效的只有"改脚下方块滑度""改赛道几何"
 * "服务端传送"三条路。物品本身只是<b>触发器</b>，右键与空格两条路径都已经验证可用
 * （按住 W 时右键会被客户端吞掉，所以空格是主触发键）。
 *
 * @see BoatRaceSession#useItem
 */
public enum RaceItem {
    /** 氮气加速：把脚下（含前方 2 格）的冰面换成蓝冰，极速 ×1.82。 */
    NITRO("nitro", "氮气加速", Items.BLAZE_POWDER, Formatting.AQUA,
            "脚下冰面换成蓝冰，极速 ×§f1.82"),
    /**
     * 速冻胶：在身后铺一条<b>短</b>雪带。
     *
     * <p>短是关键：雪块滑度 0.6（极速 2 格/秒）与原版唯一的高滑度档 0.98（40 格/秒）之间没有
     * 中间档，所以"减速"的力度只能靠<b>你在雪上待几 tick</b> 来调 —— 3 格长只待 1~2 tick，
     * 速度掉到 10 格/秒左右就回到冰面，几秒内恢复满速，绝不会停住（5 格长就会一路衰减到 2 格/秒）。
     */
    TRAP("trap", "速冻胶", Items.SNOWBALL, Formatting.WHITE,
            "在身后铺一条短雪带：压上去顿一下掉速，但停不下来"),
    /** 墨水弹：让前一名玩家短暂失明（纯观感效果，但对高速走线很致命）。 */
    INK("ink", "墨水弹", Items.INK_SAC, Formatting.DARK_PURPLE,
            "让前一名玩家短暂失明");

    /** 道具标记的 NBT key；值是 {@link #id()}。 */
    public static final String ITEM_TAG = "pvp.boatrace_item";

    private final String id;
    private final String displayName;
    private final Item icon;
    private final Formatting color;
    private final String usage;

    RaceItem(String id, String displayName, Item icon, Formatting color, String usage) {
        this.id = id;
        this.displayName = displayName;
        this.icon = icon;
        this.color = color;
        this.usage = usage;
    }

    /** NBT 里存的值（小写英文，不随显示名变）。 */
    public String id() {
        return this.id;
    }

    public String displayName() {
        return this.displayName;
    }

    /** 带颜色的短名，聊天/行动栏里用。 */
    public String coloredName() {
        return this.color.toString() + "§l" + this.displayName;
    }

    public String coloredShortName() {
        return this.color.toString() + this.displayName;
    }

    public Item icon() {
        return this.icon;
    }

    /** 一句话用法（lore 第一行内容）。 */
    public String usage() {
        return this.usage;
    }

    /** 造一个空白道具（名字 + 标记 + 附魔光效）；lore 由 {@code BoatRaceSession} 按配置补。 */
    public ItemStack create() {
        ItemStack stack = new ItemStack(this.icon);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(this.coloredName()));
        NbtCompound nbt = new NbtCompound();
        nbt.putString(ITEM_TAG, this.id);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        // 1.21 的附魔光效覆盖：比"塞一个附魔"干净，不需要服务器注册表
        stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, Boolean.TRUE);
        return stack;
    }

    /** 按 NBT 标记识别；不是本模式道具返回 null。 */
    public static RaceItem of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        NbtComponent nbt = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (nbt == null) {
            return null;
        }
        NbtCompound compound = nbt.copyNbt();
        if (!compound.contains(ITEM_TAG)) {
            return null;
        }
        return byId(compound.getString(ITEM_TAG));
    }

    public static RaceItem byId(String id) {
        for (RaceItem item : values()) {
            if (item.id.equals(id)) {
                return item;
            }
        }
        return null;
    }

}
