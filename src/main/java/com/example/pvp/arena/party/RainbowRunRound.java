package com.example.pvp.arena.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.projectile.BreezeWindChargeEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 原版 413 Rainbow Run 的当前风弹生存玩法：rainbow_run/start/tick/end 与 sentry 函数。
 * 原 tick 中旧彩色方块碰撞积分已全部注释，不能按游戏名称另写一场竞速。
 * 保留 112 个原发射点、0.05 格/tick 的原生旋风弹、逐渐增加的生成率和存活排名。
 *
 * <p>修改前自查：仅本场禁跳、step_height=4、禁自然回血；死亡/坠落同 tick 批量计分；
 * 原命令操纵全服 marker/属性，因此改为本场实体集合和离场恢复，结束后不遗留风弹。
 */
public final class RainbowRunRound implements PartyRound {
    private static final int RATE_DIVISOR = 600;
    private static final int[] BOOT_COLORS = {
            255, 16_711_680, 65_280, 16_776_960, 43_690,
            16_733_695, 11_141_120, 43_520, 11_141_290, 170
    };
    private final PartyRoundPlayerState state = new PartyRoundPlayerState();
    private final List<Vec3d> shooters = new ArrayList<>();
    private final Map<UUID, BreezeWindChargeEntity> windCharges = new HashMap<>();
    private final Set<UUID> pendingEliminations = new HashSet<>();
    private double voidY;
    private double projectileEndX;
    private boolean finished;

    @Override
    public void stage(PartyRoundContext context) throws IOException {
        // 原 clone 从模板到运行区平移 (+115, 0, +841)，原出生点为 (24,-38,553)。
        context.sourceAnchor(new BlockPos(-91, -38, -288));
        context.timeLimit(0);
        context.geometry("rainbow_run");
        this.voidY = context.source(0, -64, 0).y;
        this.projectileEndX = context.source(-43, 0, 0).x;
        // 原 tp 24.0 -38 553：显式小数 X 保持 24.0，整数 Z 由命令解析器居中到 553.5。
        Vec3d spawn = context.source(-91, -38, -287.5);
        for (ServerPlayerEntity player : context.players()) context.spawn(player.getUuid(), spawn);
        this.loadShooters(context);
    }

    private void loadShooters(PartyRoundContext context) throws IOException {
        String path = "/pvp/party/rainbow_run_shooters.json";
        try (InputStream input = RainbowRunRound.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing Rainbow Run shooter coordinates");
            try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (root.get("formatVersion").getAsInt() != 1) throw new IOException("Unsupported shooter format");
                for (JsonElement element : root.getAsJsonArray("shooters")) {
                    JsonArray point = element.getAsJsonArray();
                    // summon 与 tp 均将绝对整数 X/Z 居中 +0.5；yaw=-90 的 ^ ^ ^1 再向东一格。
                    this.shooters.add(context.source(point.get(0).getAsInt() - 113.5,
                            point.get(1).getAsInt(), point.get(2).getAsInt() - 840.5));
                }
                if (this.shooters.size() != 112) throw new IOException("Rainbow Run requires 112 original shooters");
            }
        } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException exception) {
            throw new IOException("Invalid Rainbow Run shooter coordinates", exception);
        }
    }

    @Override
    public void start(PartyRoundContext context) {
        for (ServerPlayerEntity player : context.players()) {
            this.state.attribute(player, EntityAttributes.GENERIC_JUMP_STRENGTH, 0);
            this.state.attribute(player, EntityAttributes.GENERIC_STEP_HEIGHT, 4);
            this.state.attribute(player, EntityAttributes.GENERIC_GRAVITY, 0.08);
            this.state.invisible(player);
            this.state.resetHealth(player);
            ItemStack boots = new ItemStack(Items.LEATHER_BOOTS);
            int slot = Math.floorMod(context.slot(player.getUuid()), BOOT_COLORS.length);
            boots.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(BOOT_COLORS[slot], false));
            player.getRegistryManager().get(RegistryKeys.ENCHANTMENT).getEntry(Enchantments.BINDING_CURSE)
                    .ifPresent(enchantment -> boots.addEnchantment(enchantment, 1));
            player.equipStack(EquipmentSlot.FEET, boots);
            player.teleport(context.world(), player.getX(), player.getY(), player.getZ(), 90, 0);
            player.currentScreenHandler.sendContentUpdates();
        }
        context.title("§a躲避风弹！", "§f不能跳跃；摔伤和风弹会扣血，存活到最后获胜");
    }

    @Override
    public void tick(PartyRoundContext context, int elapsed) {
        if (this.finished) return;
        for (ServerPlayerEntity player : context.alive()) {
            // 原 data get 的整数 Y<=-65 等价于连续坐标 Y<-64，平移后仍使用同一阈值。
            if (player.getY() < this.voidY) this.fall(context, player);
        }
        this.settleEliminations(context);
        if (context.alive().size() <= 1) {
            context.finish();
            return;
        }
        this.cleanPassedProjectiles(context);
        int rate = 300 + elapsed;
        int count = rate / RATE_DIVISOR;
        int remainder = rate % RATE_DIVISOR;
        // sentry 使用 random 1..600 且 >= remainder 时不发射，概率严格是 (remainder-1)/600。
        if (context.random().nextInt(RATE_DIVISOR) + 1 < remainder) count++;
        List<Vec3d> shuffled = new ArrayList<>(this.shooters);
        Collections.shuffle(shuffled, context.random());
        for (int i = 0; i < Math.min(count, shuffled.size()); i++) this.shoot(context, shuffled.get(i));
    }

    private void shoot(PartyRoundContext context, Vec3d position) {
        BreezeWindChargeEntity projectile = new RoundWindCharge(context);
        projectile.refreshPositionAndAngles(position.x, position.y, position.z, -90, 0);
        projectile.setNoGravity(true);
        // sentry 的 sin/cos 均放大 1000，结果乘 0.00000005 后为 0.05；原生风弹无加速度。
        projectile.setVelocity(0.05, 0, 0);
        this.windCharges.put(projectile.getUuid(), projectile);
        if (!context.world().spawnEntity(projectile)) this.windCharges.remove(projectile.getUuid());
    }

    private void cleanPassedProjectiles(PartyRoundContext context) {
        this.windCharges.values().removeIf(projectile -> {
            // 对应原 if block ~2 ~ ~ barrier kill；出模板后的风弹亦不能进入相邻比赛区域。
            if (!projectile.isRemoved() && (projectile.getX() > this.projectileEndX
                    || context.world().getBlockState(BlockPos.ofFloored(projectile.getPos().add(2, 0, 0))).isOf(Blocks.BARRIER))) {
                projectile.discard();
            }
            return projectile.isRemoved();
        });
    }

    private void fall(PartyRoundContext context, ServerPlayerEntity player) {
        if (this.finished || context.isOut(player.getUuid()) || !this.pendingEliminations.add(player.getUuid())) return;
        context.eliminate(player, "§c你已坠落，正在观看 Rainbow Run。");
        context.announce("§c" + player.getName().getString() + " 坠落了！");
    }

    private void settleEliminations(PartyRoundContext context) {
        int count = this.pendingEliminations.size();
        if (count == 0) return;
        // 原 tag=kill 先标记同帧全体，再给 tag=!kill 存活者逐人加分，避免 UUID 顺序决定胜负。
        for (ServerPlayerEntity survivor : context.alive()) context.addScore(survivor.getUuid(), count);
        this.pendingEliminations.clear();
    }

    @Override
    public void death(PartyRoundContext context, ServerPlayerEntity player) {
        this.fall(context, player);
    }

    @Override
    public boolean allowsDamage(PartyRoundContext context, LivingEntity victim, DamageSource source) {
        return victim instanceof ServerPlayerEntity player && !context.isOut(player.getUuid())
                && (source.isOf(DamageTypes.FALL) || source.isOf(DamageTypes.OUT_OF_WORLD)
                || source.getSource() != null && this.ownsEntity(source.getSource()));
    }

    @Override
    public boolean allowsNaturalRegeneration() {
        return false;
    }

    @Override
    public boolean ownsEntity(Entity entity) {
        return this.windCharges.containsKey(entity.getUuid());
    }

    @Override
    public void leave(PartyRoundContext context, ServerPlayerEntity player) {
        if (!this.finished && !context.isOut(player.getUuid())) this.pendingEliminations.add(player.getUuid());
        this.state.restore(player);
    }

    @Override
    public void finish(PartyRoundContext context) {
        this.settleEliminations(context);
        this.finished = true;
        this.clearProjectiles();
    }

    @Override
    public void close(PartyRoundContext context) {
        this.finished = true;
        this.clearProjectiles();
        context.players().forEach(this.state::restore);
    }

    private void clearProjectiles() {
        this.windCharges.values().forEach(Entity::discard);
        this.windCharges.clear();
    }

    @Override
    public String scoreDescription(PartyRoundContext context, UUID player) {
        return "存活积分 " + context.score(player);
    }

    @Override
    public String status(PartyRoundContext context) {
        return "Rainbow Run · 剩余 " + context.alive().size() + " 人";
    }

    /** 无须生成原版发射 marker；原生弹体保留碰撞和爆炸，仅限制可以命中的本场玩家。 */
    private static final class RoundWindCharge extends BreezeWindChargeEntity {
        private final PartyRoundContext context;

        private RoundWindCharge(PartyRoundContext context) {
            super(EntityType.BREEZE_WIND_CHARGE, context.world());
            this.context = context;
        }

        @Override
        protected boolean canHit(Entity entity) {
            return entity instanceof ServerPlayerEntity player && this.context.slot(player.getUuid()) >= 0
                    && !this.context.isOut(player.getUuid()) && super.canHit(entity);
        }
    }
}
