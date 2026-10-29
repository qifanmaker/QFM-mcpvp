package com.example.pvp.validation;

import com.example.pvp.arena.party.PartyRandomTicks;
import com.google.gson.Gson;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameRules;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Test-only native witness: opaque caps make sampled grass decay on its first
 * randomTick. A per-plant age shortcut would change thousands instead of at
 * most256 cells per16³ section, and would also touch cells outside the bound. */
public final class PartyRandomTicksProbe implements ModInitializer {
    private final ArrayList<Map<String, Object>> checks = new ArrayList<>();

    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("checks", this.checks);
            try { this.run(server.getOverworld()); report.put("ok", true); }
            catch (Throwable error) { report.put("ok", false); report.put("error", error.toString()); }
            try { Files.writeString(Path.of("party-random-ticks-report.json"), new Gson().toJson(report) + "\n"); }
            catch (Exception error) { throw new IllegalStateException(error); }
            server.stop(false);
        });
    }

    private void check(String name, boolean pass, Object actual) {
        this.checks.add(Map.of("name", name, "pass", pass, "actual", actual));
        if (!pass) throw new IllegalStateException(name + ": " + actual);
    }

    private void seed(ServerWorld world, BlockPos minimum, BlockPos maximum) {
        for (BlockPos pos : BlockPos.iterate(minimum, maximum)) {
            world.setBlockState(pos, Blocks.GRASS_BLOCK.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
        }
        for (BlockPos pos : BlockPos.iterate(minimum.getX(), maximum.getY() + 1, minimum.getZ(),
                maximum.getX(), maximum.getY() + 1, maximum.getZ())) {
            world.setBlockState(pos, Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
        }
    }

    private int dirt(ServerWorld world, BlockPos minimum, BlockPos maximum) {
        int count = 0;
        for (BlockPos pos : BlockPos.iterate(minimum, maximum)) if (world.getBlockState(pos).isOf(Blocks.DIRT)) count++;
        return count;
    }

    private void run(ServerWorld world) {
        int host = world.getGameRules().getInt(GameRules.RANDOM_TICK_SPEED);
        BlockPos minimum = new BlockPos(0, 64, 0), maximum = new BlockPos(15, 95, 15);
        this.seed(world, minimum, maximum);
        PartyRandomTicks.tick(world, minimum, maximum, 0);
        this.check("Zero rate leaves both native sections unchanged", this.dirt(world, minimum, maximum) == 0, 0);
        PartyRandomTicks.tick(world, minimum, maximum, 256);
        int lower = this.dirt(world, minimum, new BlockPos(15, 79, 15));
        int upper = this.dirt(world, new BlockPos(0, 80, 0), maximum);
        this.check("First section receives at most256 actual samples", lower > 0 && lower <= 256, lower);
        this.check("Second section separately receives at most256 actual samples", upper > 0 && upper <= 256, upper);
        this.check("Rate is per section rather than shared across the whole field", lower + upper > 256, lower + upper);

        this.seed(world, minimum, maximum);
        PartyRandomTicks.tick(world, minimum, new BlockPos(7, 95, 15), 512);
        int inside = this.dirt(world, minimum, new BlockPos(7, 95, 15));
        int outside = this.dirt(world, new BlockPos(8, 64, 0), maximum);
        this.check("Partial-section bounds still use uniform native samples", inside > 0 && inside <= 1024, inside);
        this.check("Adjacent plants outside the bound receive no helper ticks", outside == 0, outside);

        minimum = new BlockPos(-16, 64, -16); maximum = new BlockPos(-1, 79, -1);
        this.seed(world, minimum, maximum);
        PartyRandomTicks.tick(world, minimum, maximum, 200);
        int negative = this.dirt(world, minimum, maximum);
        this.check("Negative chunk coordinates preserve native local indexing", negative > 0 && negative <= 200, negative);
        this.seed(world, minimum, maximum);
        PartyRandomTicks.tick(world, minimum, maximum, 4500);
        int pregrowth = this.dirt(world, minimum, maximum);
        this.check("Population-scaled4500 rate remains native sampling", pregrowth > 200 && pregrowth <= 4096, pregrowth);
        this.check("Helper does not modify the shared randomTickSpeed gamerule", host == world.getGameRules().getInt(GameRules.RANDOM_TICK_SPEED), host);
    }
}
