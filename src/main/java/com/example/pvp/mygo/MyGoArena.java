package com.example.pvp.mygo;

import com.example.pvp.arena.ArenaTemplate;
import com.example.pvp.arena.ArenaWorld;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CS 地图存档的静态方块蓝图；出生、朝向、购买区和包点使用同一份相对坐标。
 * 旧平面场地的固定 Y 会把掉落物埋进地形，窄高度检查也会误杀上台阶的玩家，
 * 因此边界和掉落支撑均随真实地图读取；只导入建筑，不运行原存档的命令或实体。
 */
public final class MyGoArena {
    private static final String DECORATION_TAG = "pvp_mygo_decoration:";
    private static final Map<String, MyGoArena> DECORATION_OWNERS = new HashMap<>();
    private final BlockPos center;
    private final MyGoMapBlueprint blueprint;
    private final String decorationOwner = DECORATION_TAG + UUID.randomUUID();
    private final Map<UUID, DisplayEntity.BlockDisplayEntity> decorations = new HashMap<>();

    public MyGoArena(ArenaTemplate template, int regionIndex) {
        this(template, regionIndex, null);
    }

    public MyGoArena(ArenaTemplate template, int regionIndex, String mapKey) {
        this.center = template.getCenter(regionIndex).down();
        try {
            this.blueprint = mapKey == null ? MyGoMapBlueprint.load() : MyGoMapBlueprint.load(mapKey);
        } catch (IOException error) {
            throw new IllegalStateException("MyGO 地图读取失败: " + (mapKey == null ? MyGoMapBlueprint.selectedKey() : mapKey), error);
        }
    }

    public String id() { return this.blueprint.id(); }
    public String name() { return this.blueprint.name(); }
    public BlockPos center() { return this.center; }
    public BlockPos spawn(boolean attackers, int index) { return world(this.blueprint.spawn(attackers, index).pos()); }
    public float spawnYaw(boolean attackers, int index) { return this.blueprint.spawn(attackers, index).yaw(); }
    public BlockPos spectator() { return world(this.blueprint.spectator()); }
    public List<BlockPos> sites() { return this.blueprint.sites().stream().map(site -> world(site.pos())).toList(); }
    public BlockPos testAnchor(String name) { return world(this.blueprint.testAnchors().get(name)); }
    public boolean inBounds(BlockPos pos) { return this.blueprint.isPlayable(local(pos)); }
    public boolean inBuyZone(BlockPos pos, boolean attackers) { return inBounds(pos) && this.blueprint.buyZone(attackers).contains(local(pos)); }
    public BlockPos siteAt(BlockPos pos) {
        return this.blueprint.sites().stream().filter(site -> site.bounds().contains(local(pos)))
                .map(site -> world(site.pos())).findFirst().orElse(null);
    }

    /** 搜索当前位置下面最近的可站立层，不会把高台上的 C4 强行投到统一地平面。 */
    public BlockPos safeDrop(ArenaWorld arena, BlockPos pos) {
        if (inBounds(pos)) {
            int bottom = this.center.getY() + this.blueprint.playBounds().min().getY();
            for (int y = pos.getY() + 1; y >= bottom; y--) {
                BlockPos candidate = new BlockPos(pos.getX(), y, pos.getZ());
                BlockState feet = arena.getBlockState(candidate);
                var support = arena.getBlockState(candidate.down()).getCollisionShape(arena, candidate.down());
                // 标记实体无碰撞，不需要玩家两格净空；保留低矮半砖通道里的原处拾取。
                // 半砖和楼梯使用其上方格，最多浮起半格，避免埋入模型。
                if (inBounds(candidate) && feet.getCollisionShape(arena, candidate).isEmpty()
                        && feet.getFluidState().isEmpty()
                        && !support.isEmpty()) return candidate;
            }
        }
        return spawn(true, 0);
    }

    public void generate(ArenaWorld arena) {
        DECORATION_OWNERS.put(this.decorationOwner, this);
        for (MyGoMapBlueprint.Cell cell : this.blueprint.cells()) {
            BlockPos pos = world(cell.pos());
            // 按钮会在客户端预测 powered 并挥手，服务端无法可靠区分其挥手与左键。
            // 原图按钮均为无碰撞装饰：保留相同 block_display 外观，移除可交互方块，
            // 不改变通路碰撞，也不需要客户端 Mod 或靠时间窗吞掉合法射击。
            if (cell.state().getBlock() instanceof ButtonBlock) {
                arena.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                DisplayEntity.BlockDisplayEntity display = new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY, arena);
                display.setPosition(pos.getX(), pos.getY(), pos.getZ());
                display.setBlockState(cell.state()); display.setInvulnerable(true); display.setNoGravity(true);
                display.addCommandTag(this.decorationOwner);
                arena.spawnEntity(display); this.decorations.put(display.getUuid(), display);
            } else arena.setBlockState(pos, cell.state(), 3);
        }
        // 阵亡等待室独立于原图，不开放自由飞行或敌方视野。
        BlockPos room = spectator();
        for (int x = -3; x <= 3; x++) for (int y = -1; y <= 4; y++) for (int z = -3; z <= 3; z++) {
            boolean shell = Math.abs(x) == 3 || Math.abs(z) == 3 || y == -1 || y == 4;
            arena.setBlockState(room.add(x, y, z), (shell ? Blocks.DEEPSLATE_BRICKS : Blocks.AIR).getDefaultState(), 3);
        }
        arena.setBlockState(room.up(3), Blocks.SEA_LANTERN.getDefaultState(), 3);
    }

    /** 分帧铺图完成后检查实际碰撞，配置错误时取消比赛，避免将玩家传进墙或虚空。 */
    public String validateSpawns(ArenaWorld arena) {
        for (boolean attackers : new boolean[]{true, false}) for (int i = 0; i < 5; i++) {
            BlockPos pos = spawn(attackers, i);
            if (!inBuyZone(pos, attackers) || !canStand(arena, pos))
                return (attackers ? "T" : "CT") + (i + 1) + " " + pos;
        }
        for (BlockPos site : sites()) if (!canStand(arena, site)) return "包点 " + site;
        return canStand(arena, spectator()) ? null : "阵亡等待室";
    }

    private static boolean canStand(ArenaWorld arena, BlockPos pos) {
        BlockState feet = arena.getBlockState(pos), head = arena.getBlockState(pos.up());
        return feet.getCollisionShape(arena, pos).isEmpty() && head.getCollisionShape(arena, pos.up()).isEmpty()
                && feet.getFluidState().isEmpty() && head.getFluidState().isEmpty()
                && Block.isFaceFullSquare(arena.getBlockState(pos.down()).getCollisionShape(arena, pos.down()),
                net.minecraft.util.math.Direction.UP);
    }

    public void clear(ArenaWorld arena) {
        closeDecorations();
        // 原图流体/重力方块在提取时固定；按蓝图清理，无需每场重复保存十几万个坐标。
        for (MyGoMapBlueprint.Cell cell : this.blueprint.cells())
            arena.setBlockState(world(cell.pos()), Blocks.AIR.getDefaultState(), 3);
        BlockPos room = spectator();
        for (BlockPos pos : BlockPos.iterate(room.add(-3, -1, -3), room.add(3, 4, 3)))
            arena.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
    }

    private BlockPos local(BlockPos pos) { return pos.subtract(this.center); }
    private BlockPos world(BlockPos pos) { return this.center.add(pos); }

    /** 区块重载后是新的实体对象；按 UUID 更新引用，旧场次的存档实体则在加载时丢弃。 */
    public static void handleEntityLoad(Entity entity) {
        if (!(entity instanceof DisplayEntity.BlockDisplayEntity display)) return;
        for (String tag : entity.getCommandTags()) if (tag.startsWith(DECORATION_TAG)) {
            MyGoArena owner = DECORATION_OWNERS.get(tag);
            if (owner == null) display.discard();
            else owner.decorations.put(display.getUuid(), display);
            return;
        }
    }

    /** 结算时先注销所有权；未加载区块里的旧对象会在之后重载时由上面的钩子清理。 */
    public void closeDecorations() {
        DECORATION_OWNERS.remove(this.decorationOwner);
        for (DisplayEntity.BlockDisplayEntity decoration : this.decorations.values()) decoration.discard();
        this.decorations.clear();
    }
}
