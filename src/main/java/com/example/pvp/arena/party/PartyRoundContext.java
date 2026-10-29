package com.example.pvp.arena.party;

import com.example.pvp.arena.ArenaWorld;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** 本场能力边界：坐标转换、地形跟踪与结果均交还会话，不修改全服规则。 */
public interface PartyRoundContext {
    ArenaWorld world();
    BlockPos center();
    Random random();
    List<ServerPlayerEntity> players();
    List<ServerPlayerEntity> alive();
    Set<UUID> playerIds();
    int initialPlayerCount();
    int slot(UUID player);
    boolean isOut(UUID player);
    void sourceAnchor(BlockPos anchor);
    Vec3d source(double x, double y, double z);
    default BlockPos sourceBlock(int x, int y, int z) { return BlockPos.ofFloored(source(x, y, z)); }
    /** 载入原图几何；translation 为源坐标到本场世界的平移量。 */
    void geometry(String name, BlockPos translation) throws IOException;
    default void geometry(String name) throws IOException {
        geometry(name, sourceBlock(0, 0, 0));
    }
    void stage(BlockPos position, BlockState state);
    void set(BlockPos position, BlockState state);
    /** 按原生16³区段随机选格，仅作用于本场指定范围，不改共享 randomTickSpeed。 */
    void randomTicks(BlockPos minimum, BlockPos maximum, int speed);
    void spawn(UUID player, Vec3d position);
    void teleport(ServerPlayerEntity player, Vec3d position);
    void timeLimit(int ticks);
    int elapsed();
    int timeLimit();
    int score(UUID player);
    void score(UUID player, int score);
    default void addScore(UUID player, int amount) { score(player, score(player) + amount); }
    void eliminate(ServerPlayerEntity player, String message);
    void completed(UUID player);
    void finish();
    void announce(String message);
    void title(String title, String subtitle);
}
