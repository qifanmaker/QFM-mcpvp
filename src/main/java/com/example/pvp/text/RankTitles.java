package com.example.pvp.text;

import com.example.pvp.config.EloRating;
import com.example.pvp.config.StatsStore;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.UUID;

/**
 * 玩家名的「段位前缀」：按 Elo 积分给出 Codeforces 式的等级名与颜色，
 * 例如 1400 分的 {@code user} 在聊天/GUI/计分板里一律显示为 {@code [specialist] user}。
 *
 * <p><b>为什么单独放一个类</b>：玩家名出现在几十处（聊天、动作栏、侧边栏、GUI 标题与
 * lore、排行榜、血量标签、tab 列表……）。全部走同一份等级表，改档位/颜色只改这里，
 * 不会出现"聊天里是 specialist、排行榜里还是新兵"。
 *
 * <p><b>颜色用原版 § 码而不是 CF 的十六进制</b>：这些字符串最终都拼进
 * {@code Messages.info(String)} / 计分板行 / GUI 标题（都是字符串），
 * 六位十六进制只能走 {@code Text} 组件，改造面会扩散到所有调用点。
 * 下面给的是 Codeforces 各段位颜色的<b>原版最接近色</b>：
 * pupil/CM 与原色完全相同，specialist 几乎相同，其余是同色系的近似色。
 *
 * <p><b>档位边界</b>（与 Codeforces 一致）：新号 1600 分落在 {@code expert}。
 * 合作模式（村庄保卫战）同胜同败不改分，因此段位也不会莫名跳动。
 */
public final class RankTitles {
    /** 一个段位：显示名 + 原版颜色码（如 {@code §3}）。 */
    public record Rank(String name, String color) {
        /** 用于文本组件的 {@code "[specialist] "}。 */
        public String prefix() {
            return this.color + "[" + this.name + "] ";
        }
    }

    // Codeforces 的 10 个段位（颜色取最接近的原版色）
    private static final Rank NEWBIE = new Rank("newbie", "§8");
    private static final Rank PUPIL = new Rank("pupil", "§2");
    private static final Rank SPECIALIST = new Rank("specialist", "§3");
    private static final Rank EXPERT = new Rank("expert", "§9");
    private static final Rank CANDIDATE_MASTER = new Rank("candidate master", "§5");
    private static final Rank MASTER = new Rank("master", "§6");
    private static final Rank INTERNATIONAL_MASTER = new Rank("international master", "§6");
    private static final Rank GRANDMASTER = new Rank("grandmaster", "§c");
    private static final Rank INTERNATIONAL_GRANDMASTER = new Rank("international grandmaster", "§c");
    private static final Rank LEGENDARY_GRANDMASTER = new Rank("legendary grandmaster", "§c");

    private RankTitles() {
    }

    /** 按积分取段位。 */
    public static Rank of(double rating) {
        if (rating < 1200) {
            return NEWBIE;
        }
        if (rating < 1400) {
            return PUPIL;
        }
        if (rating < 1600) {
            return SPECIALIST;
        }
        if (rating < 1900) {
            return EXPERT;
        }
        if (rating < 2100) {
            return CANDIDATE_MASTER;
        }
        if (rating < 2300) {
            return MASTER;
        }
        if (rating < 2400) {
            return INTERNATIONAL_MASTER;
        }
        if (rating < 2600) {
            return GRANDMASTER;
        }
        if (rating < 3000) {
            return INTERNATIONAL_GRANDMASTER;
        }
        return LEGENDARY_GRANDMASTER;
    }

    /** 按玩家 UUID 取段位（无档案按初始分 1600 = expert）。 */
    public static Rank of(UUID uuid) {
        return of(uuid == null ? EloRating.INITIAL : StatsStore.INSTANCE.getStats(uuid).getRating());
    }

    /** 字符串前缀，如 {@code "§3[specialist] "}（含末尾空格；名字会沿用这个颜色）。 */
    public static String prefix(UUID uuid) {
        return of(uuid).prefix();
    }

    /** {@code prefix + 名字}：名字本身也用段位色，和 Codeforces 的 handle 观感一致。 */
    public static String name(UUID uuid, String plainName) {
        return prefix(uuid) + plainName;
    }

    /** 前缀用段位色、名字保留调用方自己的颜色（例如 GUI 里高亮自己用 §a）。 */
    public static String name(UUID uuid, String plainName, String nameColor) {
        return prefix(uuid) + nameColor + plainName;
    }

    /**
     * 给已有的 {@link Text}（原版 {@code getDisplayName()} 的返回值）加段位前缀，
     * 保留原有样式（队伍颜色/点击事件/悬停信息）。
     *
     * <p>幂等：{@code getPlayerListName()} 会用到 {@code getDisplayName()} 的结果，
     * 两个方法都挂了同一个前缀，这里用"标签是否已在开头"来防止 tab 列表出现两层前缀。
     */
    public static MutableText styled(Text plainName, UUID uuid) {
        Rank rank = of(uuid);
        String raw = plainName.getString();
        // 我们自己拼的前缀是字面量（§ 码也在字符串里），原版的显示名是组件样式（不含 §），
        // 两种形态都要认，否则 tab 列表会叠成两层前缀。
        if (raw.startsWith(rank.prefix()) || raw.startsWith("[" + rank.name() + "] ")) {
            return plainName.copy();
        }
        return Text.empty().append(Text.literal(rank.prefix())).append(plainName);
    }
}
