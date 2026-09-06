package com.example.pvp.arena.villagedefense;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;

/**
 * 1.8/1.12 经典"数字 block id + meta"→ 现代 {@link BlockState} 静态映射。
 * Village Defense 地图用方块都是经典村庄常用块（id ≤197 的编号在 1.8~1.12 稳定），
 * 未知 id 记日志置空气（调用方统计）。
 *
 * <p>注意：1.12 门占上下两格且同 id（64/71），顶层 half 靠 meta bit3 区分；
 * 门的方向/开合、楼梯朝向、台阶上下半在这里按 meta 还原，还原不了的非关键方向给默认朝向。
 */
public final class LegacyBlockMap {

    private LegacyBlockMap() {
    }

    /**
     * @param id   经典块 id（Blocks 数组字节，0-255）
     * @param meta data nibble（0-15）
     */
    public static BlockState stateFor(int id, int meta) {
        // 每个 id 一个 case；未覆盖的返回 null 表示"跳过/记日志"。
        return switch (id) {
            case 1 -> Blocks.STONE.getDefaultState(); // 村庄罕见用花岗岩等，保持纯石
            case 2 -> Blocks.GRASS_BLOCK.getDefaultState();
            case 3 -> meta == 1 ? Blocks.COARSE_DIRT.getDefaultState()
                    : meta == 2 ? Blocks.PODZOL.getDefaultState() : Blocks.DIRT.getDefaultState();
            case 4 -> Blocks.COBBLESTONE.getDefaultState();
            case 5 -> planks(meta); // 木板 0..5
            case 7 -> Blocks.BEDROCK.getDefaultState();
            case 8, 9 -> Blocks.WATER.getDefaultState();
            case 10, 11 -> Blocks.LAVA.getDefaultState();
            case 12 -> meta == 1 ? Blocks.RED_SAND.getDefaultState() : Blocks.SAND.getDefaultState();
            case 13 -> Blocks.GRAVEL.getDefaultState();
            case 14 -> Blocks.GOLD_ORE.getDefaultState();
            case 15 -> Blocks.IRON_ORE.getDefaultState();
            case 16 -> Blocks.COAL_ORE.getDefaultState();
            case 17 -> log(meta);
            case 18 -> leaves(meta);
            case 20 -> Blocks.GLASS.getDefaultState();
            case 22 -> Blocks.LAPIS_BLOCK.getDefaultState();
            case 24 -> meta == 1 ? Blocks.CHISELED_SANDSTONE.getDefaultState()
                    : meta == 2 ? Blocks.CUT_SANDSTONE.getDefaultState() : Blocks.SANDSTONE.getDefaultState();
            case 35 -> wool(meta);
            case 37 -> Blocks.DANDELION.getDefaultState();
            case 38 -> flower(meta);
            case 39 -> Blocks.BROWN_MUSHROOM.getDefaultState();
            case 40 -> Blocks.RED_MUSHROOM.getDefaultState();
            case 41 -> Blocks.GOLD_BLOCK.getDefaultState();
            case 42 -> Blocks.IRON_BLOCK.getDefaultState();
            case 43 -> doubleSlab(meta);
            case 44 -> slab(meta);
            case 45 -> Blocks.BRICKS.getDefaultState();
            case 47 -> Blocks.BOOKSHELF.getDefaultState();
            case 48 -> Blocks.MOSSY_COBBLESTONE.getDefaultState();
            case 49 -> Blocks.OBSIDIAN.getDefaultState();
            case 50 -> torch(meta);
            case 53 -> stairs(Blocks.OAK_STAIRS, meta);
            case 54 -> chest(meta);
            case 56 -> Blocks.DIAMOND_ORE.getDefaultState();
            case 57 -> Blocks.DIAMOND_BLOCK.getDefaultState();
            case 58 -> Blocks.CRAFTING_TABLE.getDefaultState();
            case 60 -> Blocks.FARMLAND.getDefaultState();
            case 61, 62 -> Blocks.FURNACE.getDefaultState();
            case 64 -> doorLower(Blocks.OAK_DOOR, meta);
            case 65 -> ladder(meta);
            case 66 -> Blocks.RAIL.getDefaultState();
            case 67 -> stairs(Blocks.COBBLESTONE_STAIRS, meta);
            case 70 -> Blocks.STONE_PRESSURE_PLATE.getDefaultState();
            case 71 -> doorLower(Blocks.IRON_DOOR, meta);
            case 72 -> Blocks.OAK_PRESSURE_PLATE.getDefaultState();
            case 73, 74 -> Blocks.REDSTONE_ORE.getDefaultState();
            case 76 -> Blocks.REDSTONE_TORCH.getDefaultState();
            case 77 -> Blocks.STONE_BUTTON.getDefaultState();
            case 78 -> Blocks.SNOW.getDefaultState();
            case 79 -> Blocks.ICE.getDefaultState();
            case 80 -> Blocks.SNOW_BLOCK.getDefaultState();
            case 81 -> Blocks.CACTUS.getDefaultState();
            case 82 -> Blocks.CLAY.getDefaultState();
            case 85 -> fence(meta);
            case 86 -> Blocks.PUMPKIN.getDefaultState();
            case 87 -> Blocks.NETHERRACK.getDefaultState();
            case 88 -> Blocks.SOUL_SAND.getDefaultState();
            case 89 -> Blocks.GLOWSTONE.getDefaultState();
            case 95 -> stainedGlass(meta);
            case 96 -> doorLower(Blocks.OAK_TRAPDOOR, meta); // 近似（1.8 陷阱门同态）
            case 98 -> stonebrick(meta);
            case 101 -> Blocks.IRON_BARS.getDefaultState();
            case 102 -> Blocks.GLASS_PANE.getDefaultState();
            case 103 -> Blocks.MELON.getDefaultState();
            case 106 -> Blocks.VINE.getDefaultState();
            case 107 -> Blocks.OAK_FENCE_GATE.getDefaultState();
            case 108 -> stairs(Blocks.BRICK_STAIRS, meta);
            case 109 -> stairs(Blocks.STONE_BRICK_STAIRS, meta);
            case 110 -> Blocks.MYCELIUM.getDefaultState();
            case 112 -> Blocks.NETHER_BRICKS.getDefaultState();
            case 113 -> Blocks.NETHER_BRICK_FENCE.getDefaultState();
            case 114 -> stairs(Blocks.NETHER_BRICK_STAIRS, meta);
            case 116 -> Blocks.ENCHANTING_TABLE.getDefaultState();
            case 121 -> Blocks.END_STONE.getDefaultState();
            case 123 -> Blocks.REDSTONE_LAMP.getDefaultState();
            case 125 -> doubleWoodSlab(meta);
            case 126 -> woodSlab(meta);
            case 128 -> stairs(Blocks.SANDSTONE_STAIRS, meta);
            case 129 -> Blocks.EMERALD_ORE.getDefaultState();
            case 130 -> Blocks.EMERALD_BLOCK.getDefaultState();
            case 133 -> Blocks.REDSTONE_BLOCK.getDefaultState();
            case 134 -> stairs(Blocks.SPRUCE_STAIRS, meta);
            case 135 -> stairs(Blocks.BIRCH_STAIRS, meta);
            case 136 -> stairs(Blocks.JUNGLE_STAIRS, meta);
            case 139 -> Blocks.COBBLESTONE_WALL.getDefaultState();
            case 155 -> Blocks.QUARTZ_BLOCK.getDefaultState();
            case 156 -> stairs(Blocks.QUARTZ_STAIRS, meta);
            case 159 -> stainedClay(meta);
            case 160 -> stainedGlassPane(meta);
            case 161 -> (meta & 1) == 1 ? Blocks.DARK_OAK_LEAVES.getDefaultState()
                    : Blocks.ACACIA_LEAVES.getDefaultState();
            case 162 -> log(meta);
            case 163 -> stairs(Blocks.ACACIA_STAIRS, meta);
            case 164 -> stairs(Blocks.DARK_OAK_STAIRS, meta);
            case 170 -> Blocks.HAY_BLOCK.getDefaultState();
            case 171 -> carpet(meta);
            case 172 -> Blocks.TERRACOTTA.getDefaultState();
            case 174 -> Blocks.PACKED_ICE.getDefaultState();
            case 179 -> Blocks.RED_SANDSTONE.getDefaultState();
            case 198 -> Blocks.END_ROD.getDefaultState();
            case 208 -> Blocks.DIRT_PATH.getDefaultState();
            case 263, 262 -> null; // 煤炭/箭? 非方块
            default -> null;
        };
    }

    private static BlockState planks(int meta) {
        return switch (meta) {
            case 1 -> Blocks.SPRUCE_PLANKS.getDefaultState();
            case 2 -> Blocks.BIRCH_PLANKS.getDefaultState();
            case 3 -> Blocks.JUNGLE_PLANKS.getDefaultState();
            case 4 -> Blocks.ACACIA_PLANKS.getDefaultState();
            case 5 -> Blocks.DARK_OAK_PLANKS.getDefaultState();
            default -> Blocks.OAK_PLANKS.getDefaultState();
        };
    }

    private static BlockState log(int meta) {
        int type = meta & 3;
        int axis = meta & 12;
        BlockState base = switch (type) {
            case 1 -> Blocks.SPRUCE_LOG.getDefaultState();
            case 2 -> Blocks.BIRCH_LOG.getDefaultState();
            case 3 -> Blocks.JUNGLE_LOG.getDefaultState();
            default -> Blocks.OAK_LOG.getDefaultState();
        };
        if (axis == 4) {
            return base.with(Properties.AXIS, net.minecraft.util.math.Direction.Axis.X);
        }
        if (axis == 8) {
            return base.with(Properties.AXIS, Direction.Axis.Z);
        }
        return base.with(Properties.AXIS, Direction.Axis.Y);
    }

    private static BlockState leaves(int meta) {
        int type = meta & 3;
        return switch (type) {
            case 1 -> Blocks.SPRUCE_LEAVES.getDefaultState();
            case 2 -> Blocks.BIRCH_LEAVES.getDefaultState();
            case 3 -> Blocks.JUNGLE_LEAVES.getDefaultState();
            default -> Blocks.OAK_LEAVES.getDefaultState();
        };
    }

    private static BlockState wool(int meta) {
        return switch (meta) {
            case 1 -> Blocks.ORANGE_WOOL.getDefaultState();
            case 2 -> Blocks.MAGENTA_WOOL.getDefaultState();
            case 3 -> Blocks.LIGHT_BLUE_WOOL.getDefaultState();
            case 4 -> Blocks.YELLOW_WOOL.getDefaultState();
            case 5 -> Blocks.LIME_WOOL.getDefaultState();
            case 6 -> Blocks.PINK_WOOL.getDefaultState();
            case 7 -> Blocks.GRAY_WOOL.getDefaultState();
            case 8 -> Blocks.LIGHT_GRAY_WOOL.getDefaultState();
            case 9 -> Blocks.CYAN_WOOL.getDefaultState();
            case 10 -> Blocks.PURPLE_WOOL.getDefaultState();
            case 11 -> Blocks.BLUE_WOOL.getDefaultState();
            case 12 -> Blocks.BROWN_WOOL.getDefaultState();
            case 13 -> Blocks.GREEN_WOOL.getDefaultState();
            case 14 -> Blocks.RED_WOOL.getDefaultState();
            case 15 -> Blocks.BLACK_WOOL.getDefaultState();
            default -> Blocks.WHITE_WOOL.getDefaultState();
        };
    }

    private static BlockState flower(int meta) {
        return switch (meta) {
            case 1 -> Blocks.BLUE_ORCHID.getDefaultState();
            case 2 -> Blocks.ALLIUM.getDefaultState();
            case 3 -> Blocks.AZURE_BLUET.getDefaultState();
            case 4 -> Blocks.RED_TULIP.getDefaultState();
            case 5 -> Blocks.ORANGE_TULIP.getDefaultState();
            case 6 -> Blocks.WHITE_TULIP.getDefaultState();
            case 7 -> Blocks.PINK_TULIP.getDefaultState();
            case 8 -> Blocks.OXEYE_DAISY.getDefaultState();
            default -> Blocks.POPPY.getDefaultState();
        };
    }

    private static BlockState slab(int meta) {
        boolean upper = (meta & 8) != 0;
        int type = meta & 7;
        BlockState base = switch (type) {
            case 1 -> Blocks.SANDSTONE_SLAB.getDefaultState();
            case 2 -> Blocks.OAK_SLAB.getDefaultState();
            case 3 -> Blocks.COBBLESTONE_SLAB.getDefaultState();
            case 4 -> Blocks.BRICK_SLAB.getDefaultState();
            case 5 -> Blocks.STONE_BRICK_SLAB.getDefaultState();
            case 6 -> Blocks.NETHER_BRICK_SLAB.getDefaultState();
            case 7 -> Blocks.QUARTZ_SLAB.getDefaultState();
            default -> Blocks.STONE_SLAB.getDefaultState();
        };
        return base.with(SlabBlock.TYPE, upper ? SlabType.TOP : SlabType.BOTTOM);
    }

    private static BlockState doubleSlab(int meta) {
        return switch (meta & 7) {
            case 1 -> Blocks.SANDSTONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.DOUBLE);
            case 3 -> Blocks.COBBLESTONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.DOUBLE);
            case 5 -> Blocks.STONE_BRICK_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.DOUBLE);
            default -> Blocks.STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.DOUBLE);
        };
    }

    private static BlockState woodSlab(int meta) {
        boolean upper = (meta & 8) != 0;
        BlockState base = switch (meta & 7) {
            case 1 -> Blocks.SPRUCE_SLAB.getDefaultState();
            case 2 -> Blocks.BIRCH_SLAB.getDefaultState();
            case 3 -> Blocks.JUNGLE_SLAB.getDefaultState();
            case 4 -> Blocks.ACACIA_SLAB.getDefaultState();
            case 5 -> Blocks.DARK_OAK_SLAB.getDefaultState();
            default -> Blocks.OAK_SLAB.getDefaultState();
        };
        return base.with(SlabBlock.TYPE, upper ? SlabType.TOP : SlabType.BOTTOM);
    }

    private static BlockState doubleWoodSlab(int meta) {
        return woodSlab(meta).with(SlabBlock.TYPE, SlabType.DOUBLE);
    }

    private static BlockState stairs(net.minecraft.block.Block block, int meta) {
        BlockState base = block.getDefaultState();
        boolean top = (meta & 4) != 0;
        // 1.8 楼梯朝向：0=东 1=西 2=南 3=北（bit2=上下颠倒）
        Direction facing = switch (meta & 3) {
            case 0 -> Direction.EAST;
            case 1 -> Direction.WEST;
            case 2 -> Direction.SOUTH;
            case 3 -> Direction.NORTH;
            default -> Direction.NORTH;
        };
        return base.with(StairsBlock.FACING, facing).with(StairsBlock.HALF, top ? BlockHalf.TOP : BlockHalf.BOTTOM);
    }

    private static BlockState torch(int meta) {
        // 1.21 火把分两个方块：TORCH(竖立，无朝向) 与 WALL_TORCH(挂墙，有 FACING)
        Direction facing = switch (meta) {
            case 1 -> Direction.EAST;
            case 2 -> Direction.WEST;
            case 3 -> Direction.SOUTH;
            case 4 -> Direction.NORTH;
            default -> null;
        };
        if (facing == null) {
            return Blocks.TORCH.getDefaultState(); // 0 / 5 = 竖立
        }
        return Blocks.WALL_TORCH.getDefaultState().with(Properties.HORIZONTAL_FACING, facing);
    }

    private static BlockState chest(int meta) {
        Direction facing = switch (meta & 3) {
            case 2 -> Direction.NORTH;
            case 3 -> Direction.SOUTH;
            case 4 -> Direction.WEST;
            default -> Direction.EAST;
        };
        return Blocks.CHEST.getDefaultState().with(HorizontalFacingBlock.FACING, facing);
    }

    private static BlockState ladder(int meta) {
        Direction facing = switch (meta) {
            case 2 -> Direction.NORTH;
            case 3 -> Direction.SOUTH;
            case 4 -> Direction.WEST;
            default -> Direction.EAST;
        };
        return Blocks.LADDER.getDefaultState().with(HorizontalFacingBlock.FACING, facing);
    }

    /** 门的下半格：meta bit0-1 方向、bit2 开合、bit3=下半(0)/上半(1)。 */
    private static BlockState doorLower(net.minecraft.block.Block door, int meta) {
        BlockState def = door.getDefaultState();
        if (!def.contains(DoorBlock.HALF)) {
            return def; // 陷阱门等
        }
        boolean upper = (meta & 8) != 0;
        if (upper) {
            return def.with(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        }
        Direction facing = switch (meta & 3) {
            case 0 -> Direction.WEST;
            case 1 -> Direction.NORTH;
            case 2 -> Direction.EAST;
            default -> Direction.SOUTH;
        };
        return def.with(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .with(DoorBlock.FACING, facing)
                .with(DoorBlock.OPEN, (meta & 4) != 0);
    }

    private static BlockState stainedGlass(int meta) {
        return switch (meta) {
            case 1 -> Blocks.ORANGE_STAINED_GLASS.getDefaultState();
            case 2 -> Blocks.MAGENTA_STAINED_GLASS.getDefaultState();
            case 3 -> Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState();
            case 4 -> Blocks.YELLOW_STAINED_GLASS.getDefaultState();
            case 5 -> Blocks.LIME_STAINED_GLASS.getDefaultState();
            case 6 -> Blocks.PINK_STAINED_GLASS.getDefaultState();
            case 7 -> Blocks.GRAY_STAINED_GLASS.getDefaultState();
            case 8 -> Blocks.LIGHT_GRAY_STAINED_GLASS.getDefaultState();
            case 9 -> Blocks.CYAN_STAINED_GLASS.getDefaultState();
            case 10 -> Blocks.PURPLE_STAINED_GLASS.getDefaultState();
            case 11 -> Blocks.BLUE_STAINED_GLASS.getDefaultState();
            case 12 -> Blocks.BROWN_STAINED_GLASS.getDefaultState();
            case 13 -> Blocks.GREEN_STAINED_GLASS.getDefaultState();
            case 14 -> Blocks.RED_STAINED_GLASS.getDefaultState();
            case 15 -> Blocks.BLACK_STAINED_GLASS.getDefaultState();
            default -> Blocks.WHITE_STAINED_GLASS.getDefaultState();
        };
    }

    private static BlockState stainedGlassPane(int meta) {
        return switch (meta) {
            case 1 -> Blocks.ORANGE_STAINED_GLASS_PANE.getDefaultState();
            case 14 -> Blocks.RED_STAINED_GLASS_PANE.getDefaultState();
            case 15 -> Blocks.BLACK_STAINED_GLASS_PANE.getDefaultState();
            default -> Blocks.WHITE_STAINED_GLASS_PANE.getDefaultState();
        };
    }

    private static BlockState stainedClay(int meta) {
        return switch (meta) {
            case 1 -> Blocks.ORANGE_TERRACOTTA.getDefaultState();
            case 4 -> Blocks.YELLOW_TERRACOTTA.getDefaultState();
            case 14 -> Blocks.RED_TERRACOTTA.getDefaultState();
            case 15 -> Blocks.BLACK_TERRACOTTA.getDefaultState();
            default -> Blocks.WHITE_TERRACOTTA.getDefaultState();
        };
    }

    private static BlockState carpet(int meta) {
        return switch (meta) {
            case 1 -> Blocks.ORANGE_CARPET.getDefaultState();
            case 14 -> Blocks.RED_CARPET.getDefaultState();
            case 15 -> Blocks.BLACK_CARPET.getDefaultState();
            default -> Blocks.WHITE_CARPET.getDefaultState();
        };
    }

    private static BlockState fence(int meta) {
        return switch (meta) {
            case 1 -> Blocks.SPRUCE_FENCE.getDefaultState();
            case 2 -> Blocks.BIRCH_FENCE.getDefaultState();
            case 3 -> Blocks.JUNGLE_FENCE.getDefaultState();
            case 4 -> Blocks.ACACIA_FENCE.getDefaultState();
            case 5 -> Blocks.DARK_OAK_FENCE.getDefaultState();
            default -> Blocks.OAK_FENCE.getDefaultState();
        };
    }

    private static BlockState stonebrick(int meta) {
        return switch (meta) {
            case 1 -> Blocks.MOSSY_STONE_BRICKS.getDefaultState();
            case 2 -> Blocks.CRACKED_STONE_BRICKS.getDefaultState();
            case 3 -> Blocks.CHISELED_STONE_BRICKS.getDefaultState();
            default -> Blocks.STONE_BRICKS.getDefaultState();
        };
    }
}
