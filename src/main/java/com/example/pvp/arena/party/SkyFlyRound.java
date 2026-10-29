package com.example.pvp.arena.party;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.UnbreakableComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntUnaryOperator;

/**
 * 原版407 Hoopin' n' Swoopin'：通用鞘翅竞速会丢失原图的螺旋、随机窄环和一次尝试规则，
 * 因此逐项重现 sky_fly_generate/2、sky_fly_start/tick/score 与 end_sky_fly。
 * 自查：36环每环下降4格、五种含空气模板、七个转角及其随机范围保持原值；原生鞘翅飞行，
 * 无助推、传送过环或自定义飞行物理。积分取固定Y=-42的37种地面材料，不虚构过环检查点；
 * 无倒计时，所有人落到底层才结算，先冻结分数再传回大厅。仅修改和恢复本场玩家状态。
 */
public final class SkyFlyRound implements PartyRound {
    private static final String[] TEMPLATES = {"sky_fly_ring_large", "sky_fly_ring_medium",
            "sky_fly_ring_medium_chain", "sky_fly_ring_small", "sky_fly_ring_small_chain"};
    private static final int[][] TEMPLATE_MIN = {{-53,28,15},{-53,28,23},{-53,28,30},{-45,28,17},{-45,28,24}};
    private static final int[][] OBSERVATION = {{-84,42},{-84,112},{-154,112},{-154,42},
            {-84,42},{-84,112},{-154,112},{-154,42},{-120,76},{-120,76}};
    private static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime",
            "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    /** source block destination; choice is one of the original sf_rand1..11 markers. */
    record Ring(int number, int direction, int template, int x, int y, int z, int choice, int minimumChoice, int maximumChoice) { }

    private final PartyRoundPlayerState saved = new PartyRoundPlayerState();
    private final Map<UUID, Integer> previousY = new HashMap<>();
    private Vec3d launch;
    private int sourceOffsetY;
    private int landed;
    private boolean started;
    private boolean finished;

    @Override public void stage(PartyRoundContext context) throws IOException {
        // X/Z extent [-183,-22]×[-5,139] fits the allocation without shrinking
        // the original; source Y=-47..122 remains inside the 1.21.1 world.
        context.sourceAnchor(new BlockPos(-103, 36, 67));
        context.timeLimit(0);
        this.sourceOffsetY = context.sourceBlock(0, 0, 0).getY();
        this.launch = context.source(-23.5, 118, 4.5);
        context.geometry("sky_fly_base");
        PartyMapBlueprint[] templates = new PartyMapBlueprint[TEMPLATES.length];
        for (int i = 0; i < templates.length; i++) templates[i] = PartyMapBlueprint.load(TEMPLATES[i]);
        for (Ring ring : rings(bound -> context.random().nextInt(bound))) {
            int[] minimum = TEMPLATE_MIN[ring.template()];
            BlockPos destination = context.sourceBlock(ring.x(), ring.y(), ring.z());
            templates[ring.template()].forEachTranslated(destination.subtract(new BlockPos(minimum[0], minimum[1], minimum[2])),
                    (position, state) -> {
                        // clone selects X-min for west/east walls, Z-min for
                        // south/north walls; air must overwrite the quartz.
                        if ((ring.direction() == 1 || ring.direction() == 3)
                                ? position.getX() == destination.getX() : position.getZ() == destination.getZ()) {
                            context.stage(position, state);
                        }
                    });
        }
        int seat = 0;
        for (ServerPlayerEntity player : context.players()) {
            int[] observation = OBSERVATION[seat++];
            // Absolute integer tp coordinates in the original center X/Z by .5.
            context.spawn(player.getUuid(), context.source(observation[0] + .5, -46, observation[1] + .5));
        }
    }

    /** Original generator, expressed as data; RNG receives only a positive bound. */
    static List<Ring> rings(IntUnaryOperator random) {
        List<Ring> result = new ArrayList<>(36);
        int x = -39, y = 115, z = -4;
        for (int number = 1; number <= 36; number++) {
            int direction = number <= 8 ? 1 : number <= 15 ? 2 : number <= 21 ? 3
                    : number <= 26 ? 4 : number <= 30 ? 1 : number <= 33 ? 2 : number <= 35 ? 3 : 4;
            y -= 4;
            switch (number) {
                case 9 -> { x = -182; z = 13; }
                case 16 -> { x = -165; z = 138; }
                case 22 -> { x = -58; z = 121; }
                case 27 -> { x = -75; z = 32; }
                case 31 -> { x = -146; z = 49; }
                case 34 -> { x = -129; z = 102; }
                case 36 -> { x = -94; z = 85; }
                default -> { }
            }
            boolean beforeCorner = number == 8 || number == 15 || number == 21 || number == 26
                    || number == 30 || number == 33 || number == 35;
            boolean afterCorner = number == 9 || number == 16 || number == 22 || number == 27
                    || number == 31 || number == 34 || number == 36;
            int minimum = beforeCorner || afterCorner || number <= 8 ? 1 : number <= 30 ? 2 : 3;
            int maximum = beforeCorner ? 8 : afterCorner ? 5 : 11;
            int choice = minimum + random.applyAsInt(maximum - minimum + 1);
            if (choice < minimum || choice > maximum) throw new IllegalArgumentException("Invalid Sky Fly random roll");
            int template = number < 9 ? 0 : number < 20 ? 1 : number < 31 ? 2 : number < 36 ? 3 : 4;
            int destinationX = x, destinationZ = z;
            switch (direction) {
                case 1 -> destinationZ += choice - 1;
                case 2 -> destinationX += choice - 1;
                case 3 -> destinationZ -= choice + 5;
                case 4 -> destinationX -= choice + 5;
                default -> throw new IllegalStateException();
            }
            result.add(new Ring(number, direction, template, destinationX, y, destinationZ, choice, minimum, maximum));
            switch (direction) {
                case 1 -> x -= 18;
                case 2 -> z += 18;
                case 3 -> x += 18;
                case 4 -> z -= 18;
                default -> throw new IllegalStateException();
            }
        }
        return List.copyOf(result);
    }

    @Override public void start(PartyRoundContext context) {
        this.started = true;
        for (ServerPlayerEntity player : context.players()) {
            this.saved.attribute(player, EntityAttributes.GENERIC_JUMP_STRENGTH, .42);
            this.saved.resetHealth(player);
            context.score(player.getUuid(), 0);
            context.teleport(player, this.launch);
            ItemStack elytra = new ItemStack(Items.ELYTRA);
            elytra.set(DataComponentTypes.UNBREAKABLE, new UnbreakableComponent(false));
            elytra.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false);
            player.getRegistryManager().get(RegistryKeys.ENCHANTMENT).getEntry(Enchantments.BINDING_CURSE)
                    .ifPresent(entry -> elytra.addEnchantment(entry, 1));
            player.equipStack(EquipmentSlot.CHEST, elytra);
            player.currentScreenHandler.sendContentUpdates();
        }
        this.refreshEffects(context);
        context.title("§a飞吧！", "§f向西起飞，逆时针向内穿过36个圆环");
        context.announce("§e只有一次尝试；落在铁栏杆上会失去鞘翅，请跳下到底层。"
                + "\n§f按你所在区域计分，所有玩家落到底层后结束，没有时间限制。");
    }

    @Override public void tick(PartyRoundContext context, int elapsed) {
        if (!this.started || this.finished) return;
        this.refreshEffects(context);
        this.landed = 0;
        List<ServerPlayerEntity> players = context.players();
        for (ServerPlayerEntity player : players) {
            Integer priorY = this.previousY.get(player.getUuid());
            // The source checks yesterday's cached ypos BEFORE refreshing it.
            if (priorY != null && priorY >= -40 && priorY <= -32) {
                this.saved.attribute(player, EntityAttributes.GENERIC_JUMP_STRENGTH, 0);
            }
            this.updateScore(context, player);
            int y = (int) Math.floor(player.getY()) - this.sourceOffsetY;
            this.previousY.put(player.getUuid(), y);
            if (y <= -32) clearElytra(player);
            if (player.isOnGround() && context.world().getBlockState(player.getBlockPos()).isOf(Blocks.IRON_BARS)) {
                if (player.getInventory().count(Items.ELYTRA) > 0) {
                    player.sendMessage(Text.literal("§e你错过了圆环。请跳下到底层，让本轮可以结束。"), false);
                }
                clearElytra(player);
            }
            if (context.world().getBlockState(player.getBlockPos().down(2)).isOf(Blocks.DEAD_BUBBLE_CORAL_BLOCK)) this.landed++;
        }
        if (!players.isEmpty() && this.landed == players.size()) context.finish();
    }

    private void refreshEffects(PartyRoundContext context) {
        for (ServerPlayerEntity player : context.players()) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20, 99, false, false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 20, 99, false, false));
        }
    }

    private void updateScore(PartyRoundContext context, ServerPlayerEntity player) {
        BlockPos feet = player.getBlockPos();
        BlockState underCourse = context.world().getBlockState(new BlockPos(feet.getX(), this.sourceOffsetY - 42, feet.getZ()));
        int points = pointsForMaterial(Registries.BLOCK.getId(underCourse.getBlock()).getPath());
        if (points >= 0) context.score(player.getUuid(), points);
    }

    static int pointsForMaterial(String material) {
        for (int index = 0; index < COLORS.length; index++) {
            if (material.equals(COLORS[index] + "_concrete")) return index;
            if (material.equals(COLORS[index] + "_wool")) return 16 + index;
            if (index <= 4 && material.equals(COLORS[index] + "_terracotta")) return 32 + index;
        }
        return -1;
    }

    private static void clearElytra(ServerPlayerEntity player) {
        boolean changed = false;
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            if (player.getInventory().getStack(slot).isOf(Items.ELYTRA)) {
                player.getInventory().setStack(slot, ItemStack.EMPTY);
                changed = true;
            }
        }
        if (changed) {
            // An airborne Elytra removal must clear the native flag as well;
            // otherwise 1.21.1 can retain the gliding pose until its next update.
            player.stopFallFlying();
            player.currentScreenHandler.sendContentUpdates();
        }
    }

    @Override public boolean allowsDamage(PartyRoundContext context, LivingEntity victim, DamageSource source) {
        // Resistance99 makes all ordinary collision/fall damage zero, as in
        // the source. Keep native damage handling (including its flight motion).
        return victim instanceof ServerPlayerEntity && source.getAttacker() == null;
    }

    @Override public void death(PartyRoundContext context, ServerPlayerEntity player) {
        this.saved.resetHealth(player);
        // Mirrors the source spawnpoint without giving replacement Elytra.
        // Ordinary collision/fall damage is zero under source Resistance99;
        // an administrative /kill still must not eliminate the participant.
        context.teleport(player, this.launch);
    }

    @Override public void finish(PartyRoundContext context) {
        if (this.finished) return;
        // end_sky_fly explicitly scores before any observer/lobby teleport.
        context.players().forEach(player -> this.updateScore(context, player));
        this.finished = true;
    }

    private void restore(ServerPlayerEntity player) {
        this.saved.restore(player);
        player.removeStatusEffect(StatusEffects.RESISTANCE);
        player.removeStatusEffect(StatusEffects.WEAKNESS);
        clearElytra(player);
        this.previousY.remove(player.getUuid());
    }

    @Override public void leave(PartyRoundContext context, ServerPlayerEntity player) { this.restore(player); }
    @Override public void close(PartyRoundContext context) {
        this.finished = true;
        context.players().forEach(this::restore);
    }
    @Override public String scoreDescription(PartyRoundContext context, UUID player) { return context.score(player) + " 环区域"; }
    @Override public String status(PartyRoundContext context) { return "螺旋穿环 · 已落地 " + this.landed + "/" + context.players().size(); }
}
