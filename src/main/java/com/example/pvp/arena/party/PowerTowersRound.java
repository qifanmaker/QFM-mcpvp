package com.example.pvp.arena.party;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 原版419 Power Towers：十秒原生调音统计长塔，拆底后按真实混凝土粉末落块逐个计分。
 * 修改原因：直接按点击次数计分会丢失源1..7/tick增量限制、零点击基线、拆底补偿及落块阶段；
 * 普通搭高/跑酷也不符合四个源函数。完整End场景和左右4×3×3克隆保留原坐标、空气和朝向。
 * 自查：读取永久统计的本轮增量，不重置统计；不改原生落块重力/碰撞，不伪造落块计分；
 * 只将本场落块的dropItem设false复现源doEntityDrops=false，实体、状态和方块均限定本场。
 * 源先杀marker再选marker放挡板，后两句本来无效；不补造挡板或限制合法输入速率。
 */
public final class PowerTowersRound implements PartyRound {
    private static final BlockPos ANCHOR = new BlockPos(-498, 52, -5);
    private static final int MASH_TICKS = 200;
    private static final int[][] TOWERS = {{-500,-1},{-496,-9},{-496,-1},{-500,-9},{-497,-5},
            {-499,-5},{-494,-13},{-502,3},{-494,3},{-502,-13}};
    private static final int[][] SPAWNS = {{-501,-1},{-495,-9},{-495,-1},{-501,-9},{-496,-5},
            {-500,-5},{-493,-13},{-503,3},{-493,3},{-503,-13}};
    private static final int[][] DESTINATIONS = {{-502,-2},{-497,-10},{-497,-2},{-502,-10},{-498,-6},
            {-501,-6},{-495,-14},{-504,2},{-495,2},{-504,-14}};
    private static final Set<Integer> WEST = Set.of(1,4,6,8,10);
    /** Pure source-rule tests must not initialize Minecraft registries before bootstrap. */
    private static final class NativeBlocks {
        static final Set<Block> RESET = Set.of(Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB,
                Blocks.REINFORCED_DEEPSLATE, Blocks.NOTE_BLOCK);
        static final Block[] COLORS = {Blocks.BLUE_CONCRETE_POWDER, Blocks.RED_CONCRETE_POWDER,
                Blocks.LIME_CONCRETE_POWDER, Blocks.YELLOW_CONCRETE_POWDER, Blocks.CYAN_CONCRETE_POWDER,
                Blocks.MAGENTA_CONCRETE_POWDER, Blocks.BROWN_CONCRETE_POWDER, Blocks.GREEN_CONCRETE_POWDER,
                Blocks.PURPLE_CONCRETE_POWDER, Blocks.GRAY_CONCRETE_POWDER};
    }

    private static final class Tower {
        final UUID owner;
        final int seat;
        final int platform;
        final BlockPos base;
        final BlockPos note;
        final Vec3d spawn;
        final float yaw;
        final Block powder;
        int initialStat;
        int priorMash;
        int totalMash;
        int markerY = 52;
        int height;

        Tower(UUID owner, int seat, int platform, BlockPos base, BlockPos note, Vec3d spawn) {
            this.owner = owner; this.seat = seat; this.platform = platform; this.base = base; this.note = note; this.spawn = spawn;
            this.yaw = WEST.contains(platform) ? 90 : -90;
            this.powder = NativeBlocks.COLORS[seat];
        }
    }

    private final PartyRoundPlayerState saved = new PartyRoundPlayerState();
    private final Map<UUID, Tower> towers = new LinkedHashMap<>();
    private final Map<UUID, FallingBlockEntity> falling = new HashMap<>();
    private final Map<UUID, DisplayEntity.BlockDisplayEntity> displays = new HashMap<>();
    private final Set<UUID> counted = new HashSet<>();
    private final Set<BlockPos> notes = new HashSet<>();
    private ServerWorld world;
    private int offsetY;
    private int emptyFallingTicks;
    private boolean started;
    private boolean released;
    private boolean finished;

    static int platformFor(int seat, int players) {
        return players == 8 && (seat == 4 || seat == 5) ? seat + 5 : seat + 1;
    }

    static int movedMarker(int marker, int delta) { return delta >= 1 && delta <= 7 ? marker + delta : marker; }
    static int compensation(int total) { return total >= 2 ? 2 : total == 1 ? 1 : 0; }
    static boolean validOriginalFill(int marker) { return marker - 1 >= 0 && marker - 1 <= 255; }

    @Override public void stage(PartyRoundContext context) throws IOException {
        context.sourceAnchor(ANCHOR);
        context.timeLimit(MASH_TICKS);
        this.world = context.world();
        this.offsetY = context.sourceBlock(0,0,0).getY();
        PartyMapBlueprint.load("power_towers").forEachTranslated(BlockPos.ORIGIN, (source, state) -> {
            // Rule2 resets only these four materials, preserving surrounding
            // original tripwire, barriers, rock towers and decorative signs.
            boolean reset = source.getX() >= -504 && source.getX() <= -492 && source.getY() >= 51
                    && source.getY() <= 52 && source.getZ() >= -14 && source.getZ() <= 4 && NativeBlocks.RESET.contains(state.getBlock());
            context.stage(context.sourceBlock(source.getX(), source.getY(), source.getZ()), reset ? Blocks.AIR.getDefaultState() : state);
        });
        int seat = 0;
        for (ServerPlayerEntity player : context.players()) {
            int platform = platformFor(seat, context.initialPlayerCount());
            int[] tower = TOWERS[platform-1], spawn = SPAWNS[platform-1], destination = DESTINATIONS[platform-1];
            boolean west = WEST.contains(platform);
            BlockPos target = context.sourceBlock(destination[0],51,destination[1]);
            // Original /clone copies all36 cells including air, not merely
            // the non-air cells encoded by the safe geometry resource.
            for (BlockPos point : BlockPos.iterate(target, target.add(3,2,2))) context.stage(point, Blocks.AIR.getDefaultState());
            BlockPos sourceMin = new BlockPos(-473,12,west ? -19 : -15);
            PartyMapBlueprint.load(west ? "power_towers_west" : "power_towers_east")
                    .forEachTranslated(target.subtract(sourceMin), context::stage);
            Tower racer = new Tower(player.getUuid(), seat++, platform, context.sourceBlock(tower[0],52,tower[1]),
                    context.sourceBlock(destination[0] + (west ? 0 : 3),52,tower[1]),
                    context.source(spawn[0]+.5,52,spawn[1]+.5));
            this.towers.put(player.getUuid(), racer);
            this.notes.add(racer.note);
            context.spawn(player.getUuid(), racer.spawn);
        }
    }

    @Override public void start(PartyRoundContext context) {
        this.started = true;
        for (BlockPos point : BlockPos.iterate(context.sourceBlock(-504,53,-13),context.sourceBlock(-492,53,3))) {
            if (this.world.getBlockState(point).isOf(Blocks.BARRIER)) context.set(point, Blocks.AIR.getDefaultState());
        }
        for (ServerPlayerEntity player : context.players()) {
            Tower tower = this.towers.get(player.getUuid());
            tower.initialStat = tuneStat(player);
            this.saved.attribute(player, EntityAttributes.GENERIC_JUMP_STRENGTH, 0);
            this.saved.resetHealth(player);
            player.setYaw(tower.yaw); player.setPitch(0);
            context.teleport(player, tower.spawn);
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, -1,255,false,false));
            player.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.MASTER,10,0);
            context.score(tower.owner, 0);
        }
        context.announce("§e在10秒内尽可能快地右键调节面前的音符盒！\n§7你的彩色粉末塔会在身后升起，结束后转身观看真实落块计数。最高塔获胜。");
    }

    @Override public boolean handlesTimeLimit() { return true; }

    @Override public void tick(PartyRoundContext context, int elapsed) {
        if (!this.started || this.finished) return;
        if (!this.released) {
            for (ServerPlayerEntity player : context.players()) {
                Tower tower = this.towers.get(player.getUuid());
                if (tower == null) continue;
                tower.totalMash = Math.max(0, tuneStat(player) - tower.initialStat);
                tower.markerY = movedMarker(tower.markerY, tower.totalMash - tower.priorMash);
                this.fill(context, tower);
                tower.priorMash = tower.totalMash;
            }
            if (elapsed >= MASH_TICKS) this.release(context);
        }
        if (this.released) this.countFalling(context);
    }

    private void fill(PartyRoundContext context, Tower tower) {
        // Source /fill fails wholly when either endpoint exceeds the End's
        // original0..255 build height, rather than silently clipping the tower.
        if (!validOriginalFill(tower.markerY)) return;
        for (int y = Math.min(53,tower.markerY-1); y <= Math.max(53,tower.markerY-1); y++) {
            BlockPos point = new BlockPos(tower.base.getX(), y + this.offsetY, tower.base.getZ());
            if (this.world.getBlockState(point).isAir()) context.set(point, tower.powder.getDefaultState());
        }
    }

    private void release(PartyRoundContext context) {
        this.released = true;
        for (BlockPos point : BlockPos.iterate(context.sourceBlock(-502,52,-13),context.sourceBlock(-494,52,3))) {
            if (this.world.getBlockState(point).isOf(Blocks.REINFORCED_DEEPSLATE)) context.set(point,Blocks.TRIPWIRE.getDefaultState());
        }
        for (Tower tower : this.towers.values()) {
            var display = new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY,this.world);
            display.setBlockState(Blocks.REINFORCED_DEEPSLATE.getDefaultState());
            display.setTransformation(new AffineTransformation(new Vector3f(-.5f,0,-.5f),new Quaternionf(),new Vector3f(1),new Quaternionf()));
            display.setPosition(tower.base.toCenterPos().add(0,-.5,0));
            display.addCommandTag("party_power_towers_base");
            this.displays.put(display.getUuid(), display);
            this.world.spawnEntity(display);
            context.set(tower.base.up(),Blocks.AIR.getDefaultState());
            tower.height += compensation(tower.totalMash);
            context.score(tower.owner, tower.height);
        }
        for (ServerPlayerEntity player : context.players()) {
            player.setYaw(player.getYaw()+180);
            context.teleport(player,player.getPos());
        }
        context.announce("§6停止调音！转身观看粉末塔倒下，正在逐块计分……");
    }

    private void countFalling(PartyRoundContext context) {
        boolean any = false;
        for (Iterator<FallingBlockEntity> iterator = this.falling.values().iterator(); iterator.hasNext();) {
            FallingBlockEntity block = iterator.next();
            if (block.isRemoved()) { iterator.remove(); continue; }
            any = true;
            Tower tower = this.towerFor(block);
            if (tower != null && MathHelper.floor(block.getY()-this.offsetY) <= 56 && this.counted.add(block.getUuid())) {
                context.score(tower.owner, ++tower.height);
            }
            // Only the original End's world bounds differ from the arena's
            // dimension. Preserve its native>100tick below-bottom expiration;
            // no position, velocity, landing state or gravity is rewritten.
            if (block.timeFalling > 100 && block.getY() <= this.offsetY) block.discard();
        }
        // The source end_pt counter accumulates empty ticks; it does not reset
        // when more native entities start falling two ticks after a neighbor.
        if (!any && ++this.emptyFallingTicks >= 15) context.finish();
    }

    private static int tuneStat(ServerPlayerEntity player) { return player.getStatHandler().getStat(Stats.CUSTOM,Stats.TUNE_NOTEBLOCK); }

    private Tower towerFor(FallingBlockEntity block) {
        BlockPos origin = block.getFallingBlockPos();
        for (Tower tower : this.towers.values()) {
            if (origin.getX() == tower.base.getX() && origin.getZ() == tower.base.getZ()
                    && origin.getY() >= this.offsetY && origin.getY() <= this.offsetY+255
                    && block.getBlockState().isOf(tower.powder)) return tower;
        }
        return null;
    }

    @Override public boolean ownsEntity(Entity entity) {
        if (this.finished || entity.getWorld() != this.world) return false;
        return this.displays.containsKey(entity.getUuid()) || this.falling.containsKey(entity.getUuid())
                || entity instanceof FallingBlockEntity block && this.towerFor(block) != null;
    }

    @Override public void entityLoaded(PartyRoundContext context, Entity entity) {
        if (entity instanceof FallingBlockEntity block && this.ownsEntity(block)) {
            block.dropItem = false;
            block.addCommandTag("party_power_towers_falling");
            this.falling.put(block.getUuid(),block);
        }
    }

    @Override public ActionResult useBlock(PartyRoundContext context, ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        // Attribution is the tuning player's statistic, even when they can
        // reach a neighbor's active note block. Vanilla still validates reach.
        return !this.released && hand == Hand.MAIN_HAND && this.notes.contains(hit.getBlockPos())
                && this.world.getBlockState(hit.getBlockPos()).isOf(Blocks.NOTE_BLOCK) ? ActionResult.PASS : ActionResult.FAIL;
    }

    @Override public ActionResult attackBlock(PartyRoundContext context, ServerPlayerEntity player, BlockPos position) {
        return !this.released && this.notes.contains(position) ? ActionResult.PASS : ActionResult.FAIL;
    }

    @Override public boolean allowsDamage(PartyRoundContext context, LivingEntity victim, DamageSource source) { return false; }

    @Override public void death(PartyRoundContext context, ServerPlayerEntity player) {
        // Ordinary gameplay cannot kill a caged player. An administrative kill
        // must not invent a source elimination or erase the already-built tower.
        Tower tower = this.towers.get(player.getUuid());
        this.saved.resetHealth(player);
        if (tower != null) context.teleport(player,tower.spawn);
    }

    private void restore(ServerPlayerEntity player) {
        this.saved.restore(player);
        player.removeStatusEffect(StatusEffects.WEAKNESS);
    }

    @Override public void leave(PartyRoundContext context, ServerPlayerEntity player) { this.restore(player); }
    @Override public void finish(PartyRoundContext context) { this.finished = true; }
    @Override public void close(PartyRoundContext context) {
        this.finished = true;
        this.falling.values().forEach(Entity::discard);
        this.displays.values().forEach(Entity::discard);
        this.falling.clear(); this.displays.clear(); this.counted.clear();
        context.players().forEach(this::restore);
    }
    @Override public String scoreDescription(PartyRoundContext context, UUID player) { return context.score(player) + " 格塔高"; }
    @Override public String status(PartyRoundContext context) { return this.released ? "速度之巅 · 真实落块计数中"
            : "速度之巅 · 调音剩余 " + Math.max(0,(MASH_TICKS-context.elapsed()+19)/20) + " 秒"; }
}
