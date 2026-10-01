package com.example.pvp.hud;

import com.example.pvp.config.PvPConfig;
import com.example.pvp.mixin.DisplayEntityInvoker;
import com.example.pvp.mixin.TextDisplayEntityInvoker;
import com.mojang.logging.LogUtils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.AffineTransformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 在玩家名字下面显示「血量 + 红心」。
 *
 * <p><b>为什么不用原版 {@code below_name} 记分板槽位</b>：原版
 * {@code PlayerEntityRenderer#renderLabelIfPresent} 确实支持它（渲染成「20 ❤」并画在名字下方），
 * 但那段代码外面套了 {@code if (squaredDistanceToCamera < 100.0)} —— <b>只有 10 格以内才显示</b>，
 * 而名字本身 64 格内可见。为了让血条跟名字一样远，改用原版 {@code text_display} 实体：
 * 它是 0×0 碰撞箱的纯展示实体，不挡射线、不参与战斗，而且客户端不需要装本 mod。
 *
 * <p><b>位置靠「骑在玩家身上」，不是每 tick 发坐标包</b>：第一版是服务端自己算世界坐标 +
 * {@code refreshPositionAndAngles}，玩家一走动血条就明显拖在后面（2 tick 一发包 + 客户端 2 tick 插值）。
 * 现在改成 {@code display.startRiding(player)}：乘客的位置由<b>客户端每 tick</b> 用
 * {@code ClientWorld.tickEntity → tickPassenger → vehicle.updatePassengerPosition} 从载具（玩家）
 * 的位置现算——和玩家模型同源、零延迟、零位置包；服务端算的是同一个公式，所以实体跟踪器看不到位移，
 * 连一个移动包都不发。{@code EntityTrackerEntry#tick} 里对 {@code entity.hasVehicle()} 也是直接跳过位置同步。
 *
 * <p>用哪一点吸附：{@code EntityAttachmentType.PASSENGER} 对玩家是 {@code (0, 身高, 0)}（碰撞箱顶端，
 * 实测 1.8），而原版铭牌画在 {@code NAME_TAG 附着点 + 0.5}，所以骑上去落在铭牌下方 0.5 格。
 * {@code TextDisplayEntityRenderer} 内部同样是 {@code -0.025} 缩放，所以 transformation scale = 1.0
 * 时字号和原版铭牌一模一样。
 *
 * <p><b>为什么还要往下压 healthTagHeightOffset</b>（反编译 {@code EntityRenderer#renderLabelIfPresent}
 * 与 {@code DisplayEntityRenderer$TextDisplayEntityRenderer#render} 对出来的账）：
 * <ul>
 *   <li>显示实体：先 {@code rotateY(π)} 再 {@code scale(-0.025)}，等价于 {@code scale(0.025, -0.025, 0.025)},
 *       然后 {@code translate(…, -lineCount * 10, 0)} —— 文字是<b>以下边缘为锚点向上</b>排的，
 *       一行字占 {@code 10 * 0.025 = 0.25} 格，即 [身高, 身高 + 0.25]。</li>
 *   <li>原版铭牌：{@code translate(附着点 + 0.5)} → {@code scale(0.025, -0.025, 0.025)} → 在 y = 0 处绘制，
 *       文字<b>以上边缘为锚点向下</b>排，一行占 {@code 9 * 0.025 = 0.225} 格，
 *       即 [身高 + 0.275, 身高 + 0.5]。</li>
 * </ul>
 * 中间只剩 {@code 0.275 - 0.25 = 0.025} 格（正好一个字体像素）—— 背景框（左右各外扩 1 像素）
 * 还正好贴死，看着就是和名字糊在一起。所以默认在<b>屏幕方向</b>往下压 0.1 格把两者分开。
 *
 * <p>这个偏移是 billboard 局部坐标：{@code DisplayEntityRenderer#render} 里是
 * {@code push → multiply(billboardRotation) → multiplyPositionMatrix(transformation)}，平移在旋转之后，
 * 而 billboard 的旋转是「相机旋转的逆」（外加 180° 偏航，不影响 Y 轴），所以局部 +Y 恒等于<b>屏幕上方</b>。
 * 副作用：原版铭牌是世界坐标（身高 + 0.5），玩家越往下看，它在屏幕上离头顶越近（{@code ×cos(俯仰)}），
 * 而我们的偏移是屏幕固定的 —— 所以俯仰超过 ~40° 时两者还是会贴近。这是几何上无法规避的，
 * 想彻底避免只能调小 {@code healthTagScale} 让这行字更矮。
 */
public final class HealthTagManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 用来在服务器重启后清扫存档里的残留实体。 */
    private static final String ORPHAN_TAG = "pvp_health_tag";
    private static final String HIDDEN_NAME_TEAM = "pvp_health_names";

    private static HealthTagManager instance;

    private MinecraftServer server;
    /** 每个在线玩家一个展示实体。 */
    private final Map<UUID, DisplayEntity.TextDisplayEntity> tags = new HashMap<>();
    /** 自己生成的展示实体 UUID：把「自己刚生成的」和「存档里翻出来的孤儿」区分开。 */
    private final Set<UUID> owned = new HashSet<>();
    /** 上一次渲染出来的「血量/颜色档」，用来避免血量没变时反复发包。 */
    private final Map<UUID, Text> lastText = new HashMap<>();
    private int timer;
    private boolean namesHidden;

    private HealthTagManager(MinecraftServer server) {
        this.server = server;
    }

    public static HealthTagManager init(MinecraftServer server) {
        if (instance == null || instance.server != server) {
            instance = new HealthTagManager(server);
        }
        return instance;
    }

    public static HealthTagManager get() {
        return instance;
    }

    /**
     * 实体被加载进世界时回调（Fabric {@code ServerEntityEvents.ENTITY_LOAD}）。
     *
     * <p>为什么不是「启动时扫一遍所有世界」：实测（打印 4 个世界共扫描 0 个实体）
     * {@code ServerWorld.iterateEntities()} 在 SERVER_STARTED 时刻是空的 —— 此时区块刚准备好，
     * 实体还没交给实体管理器，所以启动清扫永远扫不到东西。硬杀服务器后残留在存档里的标签，
     * 是在对应区块**被加载**时才冒出来的，正好在这里拦掉。
     */
    public void handleEntityLoad(Entity entity) {
        if (!(entity instanceof DisplayEntity.TextDisplayEntity)) {
            return;
        }
        // 自己生成的实体也会走这个事件，靠 owned 区分
        if (this.owned.contains(entity.getUuid())) {
            return;
        }
        if (entity.getCommandTags().contains(ORPHAN_TAG)) {
            entity.discard();
            LOGGER.info("[PvP] 清理了 1 个残留在存档里的血量标签实体");
        }
    }

    /** 关服：全部拆掉，别写进存档（SERVER_STOPPING 在存档之前触发）。 */
    public void onServerStopping() {
        this.clearAll();
    }

    public void tick() {
        MinecraftServer server = this.server;
        if (server == null) {
            return;
        }
        if (!PvPConfig.INSTANCE.healthTagEnabled) {
            if (!this.tags.isEmpty() || this.namesHidden) {
                this.clearAll();
            }
            return;
        }
        boolean updateText = ++this.timer >= intervalTicks();
        if (updateText) {
            this.timer = 0;
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            try {
                this.refresh(player, updateText);
            } catch (Exception e) {
                LOGGER.error("[PvP] 刷新血量标签出错: {}", player.getGameProfile().getName(), e);
            }
        }
    }

    /** 玩家掉线：实体跟着走（否则会留在原地当孤儿）。 */
    public void remove(UUID uuid) {
        DisplayEntity.TextDisplayEntity display = this.tags.remove(uuid);
        this.lastText.remove(uuid);
        if (display != null) {
            this.owned.remove(display.getUuid());
            if (!display.isRemoved()) {
                display.discard();
            }
        }
    }

    public void clearAll() {
        for (DisplayEntity.TextDisplayEntity display : this.tags.values()) {
            if (display != null && !display.isRemoved()) {
                display.discard();
            }
        }
        this.tags.clear();
        this.owned.clear();
        this.lastText.clear();
        if (this.server != null && this.namesHidden) {
            var scoreboard = this.server.getScoreboard();
            var hiddenTeam = scoreboard.getTeam(HIDDEN_NAME_TEAM);
            if (hiddenTeam != null) {
                for (ServerPlayerEntity player : this.server.getPlayerManager().getPlayerList()) {
                    scoreboard.removeScoreHolderFromTeam(player.getGameProfile().getName(), hiddenTeam);
                }
                scoreboard.removeTeam(hiddenTeam);
            }
            for (ServerPlayerEntity player : this.server.getPlayerManager().getPlayerList()) {
                var team = scoreboard.getScoreHolderTeam(player.getGameProfile().getName());
                if (team != null && team.getName().startsWith("pvp_")
                        && !team.getName().equals(HIDDEN_NAME_TEAM)
                        && team.getNameTagVisibilityRule() != net.minecraft.scoreboard.AbstractTeam.VisibilityRule.ALWAYS) {
                    team.setNameTagVisibilityRule(net.minecraft.scoreboard.AbstractTeam.VisibilityRule.ALWAYS);
                }
            }
            this.namesHidden = false;
        }
    }

    private void refresh(ServerPlayerEntity player, boolean updateText) {
        UUID uuid = player.getUuid();

        this.hideVanillaName(player);

        // 旁观者（幽灵）/ 隐身玩家不显示：一个浮空血条会直接把幽灵的位置暴露给活着的玩家
        if (player.isSpectator() || player.isInvisible()) {
            if (this.tags.containsKey(uuid)) {
                this.remove(uuid);
            }
            return;
        }

        ServerWorld world = player.getServerWorld();
        DisplayEntity.TextDisplayEntity display = this.tags.get(uuid);
        // 两种情况下手上的实体已经没用了：被竞技场清场 discard 掉了，或者玩家换维度后它留在了旧世界
        if (display != null && (display.isRemoved() || display.getWorld() != world)) {
            if (!display.isRemoved()) {
                display.discard();
            }
            this.tags.remove(uuid);
            this.lastText.remove(uuid);
            display = null;
        }
        if (display == null) {
            display = this.spawn(player, world);
            if (display == null) {
                return; // 这 tick 生成失败（区块没加载等），下 tick 再试
            }
            this.tags.put(uuid, display);
        }

        // 不挂载在玩家上：服务端每 tick 同步独立实体的位置，包含本地玩家的预测移动。
        display.setPosition(player.getX(), player.getY() + player.getHeight(), player.getZ());

        if (!updateText) {
            return;
        }

        int health = Math.max(0, (int) Math.ceil(player.getHealth()));
        Formatting color = healthColor(player);
        Text text = player.getDisplayName().copy()
                .append(Text.literal("\n"))
                .append(Text.literal(Integer.toString(health)).formatted(color)
                        .append(Text.literal(" " + heart()).formatted(Formatting.RED)));
        if (!text.equals(this.lastText.get(uuid))) {
            this.lastText.put(uuid, text);
            ((TextDisplayEntityInvoker) display).pvp$setText(text);
        }
    }

    private void hideVanillaName(ServerPlayerEntity player) {
        this.namesHidden = true;
        var scoreboard = this.server.getScoreboard();
        String name = player.getGameProfile().getName();
        var team = scoreboard.getScoreHolderTeam(name);
        if (team == null) {
            team = scoreboard.getTeam(HIDDEN_NAME_TEAM);
            if (team == null) {
                team = scoreboard.addTeam(HIDDEN_NAME_TEAM);
                team.setNameTagVisibilityRule(net.minecraft.scoreboard.AbstractTeam.VisibilityRule.NEVER);
            }
            scoreboard.addScoreHolderToTeam(name, team);
        } else if (team.getName().startsWith("pvp_") && !team.getName().equals(HIDDEN_NAME_TEAM)
            && team.getNameTagVisibilityRule() != net.minecraft.scoreboard.AbstractTeam.VisibilityRule.NEVER) {
            team.setNameTagVisibilityRule(net.minecraft.scoreboard.AbstractTeam.VisibilityRule.NEVER);
        }
    }

    private DisplayEntity.TextDisplayEntity spawn(ServerPlayerEntity player, ServerWorld world) {
        DisplayEntity.TextDisplayEntity display = new DisplayEntity.TextDisplayEntity(EntityType.TEXT_DISPLAY, world);
        display.setNoGravity(true);   // 展示实体本来就不会自己走，纯保险
        display.setInvulnerable(true);
        display.addCommandTag(ORPHAN_TAG);

        DisplayEntityInvoker invoker = (DisplayEntityInvoker) display;
        invoker.pvp$setBillboardMode(DisplayEntity.BillboardMode.VERTICAL);
        invoker.pvp$setTeleportDuration(0);
        float scale = (float) scale();
        invoker.pvp$setTransformation(new AffineTransformation(
                new Vector3f(0.0f, (float) offset(), 0.0f), new Quaternionf(),
                new Vector3f(scale, scale, scale), new Quaternionf()));
        // 默认 flags 是 0（无阴影），而原版铭牌是带阴影的；背景色默认 0x40000000 已经和铭牌一致，不用改
        ((TextDisplayEntityInvoker) display).pvp$setDisplayFlags(DisplayEntity.TextDisplayEntity.SHADOW_FLAG);

        // 血量占原名字的位置，玩家名字作为第一行显示在它上方。
        display.refreshPositionAndAngles(player.getX(),
            player.getY() + player.getHeight(), player.getZ(), 0.0f, 0.0f);
        // 先登记再生成：ENTITY_LOAD 会在 spawnEntity 内部同步回调，不先登记会被自己清掉
        this.owned.add(display.getUuid());
        if (!world.spawnEntity(display)) {
            this.owned.remove(display.getUuid());
            LOGGER.warn("[PvP] 生成血量标签实体失败: {}", player.getGameProfile().getName());
            return null;
        }
        return display;
    }

    /** 血条染色：>60% 绿、30~60% 黄、<30% 红（红心本身恒为红色）。 */
    private static Formatting healthColor(ServerPlayerEntity player) {
        float max = Math.max(1.0f, player.getMaxHealth());
        float ratio = player.getHealth() / max;
        if (ratio > 0.6f) {
            return Formatting.GREEN;
        }
        return ratio > 0.3f ? Formatting.YELLOW : Formatting.RED;
    }

    private static int intervalTicks() {
        return Math.min(20, Math.max(1, PvPConfig.INSTANCE.healthTagUpdateIntervalTicks));
    }

    private static double scale() {
        double scale = PvPConfig.INSTANCE.healthTagScale;
        return scale > 0.0 ? scale : 1.0;
    }

    /** 世界竖直方向的偏移；默认 0.275 让血量行占据原姓名标签高度。 */
    private static double offset() {
        double offset = PvPConfig.INSTANCE.healthTagHeightOffset;
        return Math.max(-1.0, Math.min(1.0, offset));
    }

    private static String heart() {
        String heart = PvPConfig.INSTANCE.healthTagHeart;
        return heart == null || heart.isBlank() ? "❤" : heart;
    }
}
