package com.example.pvp.validation;

import com.example.pvp.arena.party.PartySourceMobs;
import com.google.gson.Gson;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.SilverfishEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test-only native policy fixtures. The original map's global gamerules must
 * apply to Party targets without changing ordinary mobs. Invoke actual native
 * despawn, AI goals, death and explosions in an isolated overworld, where the
 * existing arena explosion protection cannot mask a failure of the new policy.
 */
public final class PartySourceMobsProbe implements ModInitializer {
    private final ArrayList<Map<String, Object>> checks = new ArrayList<>();

    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("checks", checks);
            try { run(server); report.put("ok", true); }
            catch (Throwable error) { report.put("ok", false); report.put("error", error.toString()); }
            try { Files.writeString(Path.of("party-source-mobs-report.json"), new Gson().toJson(report) + "\n"); }
            catch (Exception error) { throw new IllegalStateException(error); }
            server.stop(false);
        });
    }

    private void check(String name, boolean pass) {
        checks.add(Map.of("name", name, "pass", pass));
        if (!pass) throw new IllegalStateException(name);
    }

    private void run(MinecraftServer server) throws Exception {
        ServerWorld world = server.getOverworld();
        server.setDifficulty(Difficulty.PEACEFUL, true);
        EntityType<?>[] types = {EntityType.ZOMBIE, EntityType.ZOMBIE_VILLAGER, EntityType.WITHER_SKELETON,
                EntityType.ENDERMAN, EntityType.BLAZE, EntityType.ZOMBIFIED_PIGLIN, EntityType.CREEPER,
                EntityType.SILVERFISH, EntityType.SKELETON, EntityType.CHICKEN};
        for (EntityType<?> type : types) {
            MobEntity target = PartySourceMobs.create(type, world);
            target.checkDespawn();
            check(type + " source target survives native Peaceful despawn check", !target.isRemoved());
            target.discard();
        }
        var ordinary = EntityType.ZOMBIE.create(world);
        ordinary.checkDespawn();
        check("Ordinary hostile still follows Peaceful despawn", ordinary.isRemoved());

        server.setDifficulty(Difficulty.NORMAL, true);
        world.getGameRules().get(GameRules.DO_MOB_GRIEFING).set(true, server);
        world.getGameRules().get(GameRules.DO_MOB_LOOT).set(true, server);
        BlockPos sourceBlock = new BlockPos(8, 80, 8), ordinaryBlock = new BlockPos(32, 80, 8);
        world.setBlockState(sourceBlock, Blocks.STONE.getDefaultState());
        world.setBlockState(ordinaryBlock, Blocks.STONE.getDefaultState());
        var sourceCreeper = PartySourceMobs.create(EntityType.CREEPER, world);
        var ordinaryCreeper = EntityType.CREEPER.create(world);
        check("Explosion fixtures place actual stone before native explosions", world.getBlockState(sourceBlock).isOf(Blocks.STONE)
                && world.getBlockState(ordinaryBlock).isOf(Blocks.STONE));
        check("Source creeper retains exact native-policy identity", PartySourceMobs.isSourceMob(sourceCreeper));
        var sourceExplosion = world.createExplosion(sourceCreeper, 8.5, 80.5, 8.5, 4, World.ExplosionSourceType.MOB);
        var ordinaryExplosion = world.createExplosion(ordinaryCreeper, 32.5, 80.5, 8.5, 4, World.ExplosionSourceType.MOB);
        check("Source explosion uses native KEEP destruction policy", sourceExplosion.getDestructionType() == Explosion.DestructionType.KEEP);
        check("Ordinary explosion keeps native block destruction", ordinaryExplosion.getDestructionType() != Explosion.DestructionType.KEEP);
        check("Source creeper explosion preserves blocks with global mobGriefing=true", world.getBlockState(sourceBlock).isOf(Blocks.STONE));
        check("Ordinary creeper explosion still destroys blocks", world.getBlockState(ordinaryBlock).isAir());
        sourceCreeper.discard(); ordinaryCreeper.discard();

        SilverfishEntity sourceFish = (SilverfishEntity) PartySourceMobs.create(EntityType.SILVERFISH, world);
        SilverfishEntity ordinaryFish = EntityType.SILVERFISH.create(world);
        cavity(world, sourceFish, new BlockPos(8, 80, 32));
        cavity(world, ordinaryFish, new BlockPos(32, 80, 32));
        runInfestGoal(sourceFish); runInfestGoal(ordinaryFish);
        check("Source silverfish retains native wandering without infesting stone", !sourceFish.isRemoved());
        check("Ordinary silverfish can still infest stone", ordinaryFish.isRemoved());

        SilverfishEntity sourceHelp = (SilverfishEntity) PartySourceMobs.create(EntityType.SILVERFISH, world);
        SilverfishEntity ordinaryHelp = EntityType.SILVERFISH.create(world);
        BlockPos sourceInfested = new BlockPos(57, 81, 8), ordinaryInfested = new BlockPos(81, 81, 8);
        cavity(world, sourceHelp, new BlockPos(56, 80, 8));
        cavity(world, ordinaryHelp, new BlockPos(80, 80, 8));
        world.setBlockState(sourceInfested, Blocks.INFESTED_STONE.getDefaultState());
        world.setBlockState(ordinaryInfested, Blocks.INFESTED_STONE.getDefaultState());
        runHelpGoal(sourceHelp); runHelpGoal(ordinaryHelp);
        check("Source call-for-help restores infested stone under original griefing=false behavior", world.getBlockState(sourceInfested).isOf(Blocks.STONE));
        check("Ordinary call-for-help still breaks the infested block", world.getBlockState(ordinaryInfested).isAir());
        sourceFish.discard(); sourceHelp.discard(); ordinaryHelp.discard();

        // Use the already tracked spawn chunk; fresh distant chunks may still
        // have pending entity tracking during SERVER_STARTED.
        Box sourceItems = new Box(6, 95, 6, 12, 110, 12);
        int index = 0;
        for (EntityType<?> type : types) {
            MobEntity target = PartySourceMobs.create(type, world);
            target.refreshPositionAndAngles(8.5 + index++ * .01, 100, 8.5, 0, 0);
            target.setNoGravity(true); target.setAiDisabled(true);
            target.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND));
            target.setEquipmentDropChance(EquipmentSlot.HEAD, 2);
            world.spawnEntity(target); target.kill();
        }
        check("Native source-target deaths create no loot or equipment with global doMobLoot=true",
                world.getEntitiesByClass(ItemEntity.class, sourceItems, item -> true).isEmpty());
        var control = EntityType.ZOMBIE.create(world);
        control.refreshPositionAndAngles(8.5, 150, 8.5, 0, 0);
        control.setNoGravity(true); control.setAiDisabled(true);
        control.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND));
        control.setEquipmentDropChance(EquipmentSlot.HEAD, 2);
        world.spawnEntity(control); control.kill();
        check("Ordinary native death retains guaranteed equipment drops (health=" + control.getHealth()
                        + ", head=" + control.getEquippedStack(EquipmentSlot.HEAD) + ")",
                !world.getEntitiesByClass(ItemEntity.class, new Box(6, 145, 6, 12, 160, 12), item -> item.getStack().isOf(Items.DIAMOND)).isEmpty());
        var collision = PartySourceMobs.create(EntityType.ZOMBIE, world);
        check("Source target collision is initially native", collision.isPushable());
        PartySourceMobs.noCollision(collision);
        check("WAM alone can opt into original no-collision team behavior", !collision.isPushable());
        collision.discard();
        check("Native policy fixtures do not change the world's enabled griefing/loot rules",
                world.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING) && world.getGameRules().getBoolean(GameRules.DO_MOB_LOOT));
    }

    private void cavity(ServerWorld world, SilverfishEntity fish, BlockPos base) {
        for (BlockPos pos : BlockPos.iterate(base.add(-2, 0, -2), base.add(2, 3, 2))) world.setBlockState(pos, Blocks.STONE.getDefaultState());
        world.setBlockState(base.up(), Blocks.AIR.getDefaultState());
        world.setBlockState(base.up(2), Blocks.AIR.getDefaultState());
        fish.refreshPositionAndAngles(base.getX() + .5, base.getY() + 1, base.getZ() + .5, 0, 0);
    }

    private Goal goal(SilverfishEntity fish, String name) throws Exception {
        var field = MobEntity.class.getDeclaredField("goalSelector"); field.setAccessible(true);
        GoalSelector selector = (GoalSelector) field.get(fish);
        return selector.getGoals().stream().map(entry -> entry.getGoal())
                .filter(entry -> entry.getClass().getSimpleName().equals(name)).findFirst().orElseThrow();
    }

    private void runInfestGoal(SilverfishEntity fish) throws Exception {
        Goal goal = goal(fish, "WanderAndInfestGoal");
        for (int attempt = 0; attempt < 300 && !fish.isRemoved(); attempt++) {
            fish.getNavigation().stop();
            if (goal.canStart()) goal.start();
        }
    }

    private void runHelpGoal(SilverfishEntity fish) throws Exception {
        Goal goal = goal(fish, "CallForHelpGoal");
        var method = goal.getClass().getDeclaredMethod("onHurt"); method.setAccessible(true); method.invoke(goal);
        for (int tick = 0; tick < 25; tick++) if (goal.canStart()) goal.tick();
    }
}
