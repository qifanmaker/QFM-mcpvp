package com.example.pvp.mygo.practice;

import com.example.pvp.mygo.MyGoGrenadePhysics;
import com.example.pvp.mygo.MyGoSession;
import com.example.pvp.mygo.MyGoUtilities;
import com.example.pvp.mygo.MyGoWeapon;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Practice must exercise the real bomb game, not ten omniscient aim turrets.
 * Decisions use delayed, field-of-view/LOS/utility-limited observations, coarse
 * audible contacts and expiring teammate reports. Input goes through the same
 * shop, gun cadence, recoil, spread, reload and C4 channel as human players.
 */
public final class MyGoBot {
    private record Contact(UUID enemy, Vec3d position, Vec3d velocity, double bodyHeight, int seenAt) { }
    private static final class TeamIntel {
        int round = -1;
        final Map<UUID, Contact> contacts = new HashMap<>();
        void reset(int round) { this.round = round; contacts.clear(); }
    }
    private static final Map<MyGoSession, TeamIntel[]> INTEL = new WeakHashMap<>();

    private final MyGoSession session;
    private final MyGoBotPlayer actor;
    private final MyGoBotDifficulty difficulty;
    private final int role;
    private final Random random;
    private final MyGoNavigator navigator;
    private final TeamIntel[] teamIntel;
    private final ArrayList<String> purchases = new ArrayList<>();
    private int round = -1, roundStartedAt, purchaseIndex, nextPurchase, observeAt, acquiredAt;
    private int lastVisibleAt = -1000, pauseUntil, nextTrigger, burstAmmo = -1, burstBudget, strafeUntil;
    private int strafeDirection = 1, nextUtility = 100, prepareUtilityAt, goalAt, nextScope, scanAt;
    private int lastDamagedAt = -1000;
    private MyGoWeapon burstWeapon;
    private Contact target, investigation;
    private BlockPos objective, cover;
    private MyGoUtilities.Type pendingUtility;
    private Vec3d utilityTarget;
    private double yawError, pitchError, previousHp = 100;
    private float scanYaw;
    private String state = "等待回合";
    private boolean attacking;
    private boolean primaryDry, pistolDry;

    public MyGoBot(MyGoSession session, MyGoBotPlayer actor, MyGoBotDifficulty difficulty, int seatIndex) {
        this.session = session; this.actor = actor; this.difficulty = difficulty; this.role = Math.floorMod(seatIndex, 5);
        this.random = new Random(actor.getUuid().getMostSignificantBits() ^ actor.getUuid().getLeastSignificantBits());
        this.navigator = new MyGoNavigator(actor.getServerWorld(), session.arena());
        this.teamIntel = INTEL.computeIfAbsent(session, ignored -> new TeamIntel[]{new TeamIntel(), new TeamIntel()});
    }

    public String getStateLabel() { return state; }
    public String debugState() { return "role=" + roleName() + " state=" + state + " " + navigator.debugState(); }
    public MyGoBotDifficulty getDifficulty() { return difficulty; }

    public void tick() {
        actor.input(0, 0, false); actor.setSprinting(false);
        MyGoSession.BotView view = session.botView(actor);
        if (view == null || !view.alive() || actor.isRemoved()) { session.botFire(actor, false); state = "阵亡等待"; return; }
        attacking = view.attackers();
        if (round != view.round()) resetRound(view);
        if (!"LIVE".equals(view.phase())) {
            session.botFire(actor, false); actor.setSneaking(false);
            if (view.canBuy()) buy(view);
            state = "BUY".equals(view.phase()) ? "购买与战术准备" : "回合结束";
            return;
        }
        if (view.ticks() >= observeAt) perceive(view);
        if (view.hp() < previousHp) {
            lastDamagedAt = view.ticks();
            if (target == null) { scanYaw = actor.getYaw() + 145; scanAt = view.ticks() + difficulty.reactionTicks(); }
        }
        previousHp = view.hp();
        // Flash and smoke blindness cannot be cancelled by the AI's aim update.
        if (actor.hasStatusEffect(StatusEffects.BLINDNESS) || actor.hasStatusEffect(StatusEffects.DARKNESS)) {
            session.botFire(actor, false); target = null; pendingUtility = null;
            state = "视线受阻，停止开火";
            if (cover != null) navigate(cover, view, .45, true);
            return;
        }
        if (target != null && view.ticks() - target.seenAt() > difficulty.memoryTicks()) target = null;
        if (view.channeling()) {
            boolean closeDanger = currentVisibleTarget() != null && actor.getPos().distanceTo(target.position()) < 9;
            if (!closeDanger || difficulty.getLevel() < 3) {
                session.botFire(actor, false); actor.setSneaking(true); state = view.attackers() ? "保持安装 C4" : "保持拆除 C4"; return;
            }
            actor.setSneaking(false); session.botSelect(actor, view.hasPrimary() ? 0 : 1);
            view = session.botView(actor);
        }
        if (pendingUtility != null && prepareUtility(view)) return;
        view = equipCombatWeapon(view);

        ServerPlayerEntity visibleTarget = currentVisibleTarget();
        if (visibleTarget != null && target != null) {
            lastVisibleAt = view.ticks();
            if (view.ticks() < acquiredAt) {
                session.botFire(actor, false); aim(target.position().add(0, target.bodyHeight(), 0), false);
                state = "发现目标，反应中"; return;
            }
            if (maybeUtility(view, true)) return;
            fight(view);
            return;
        }
        session.botFire(actor, false); burstAmmo = -1;
        if (view.gun() != null && view.ammo() < Math.max(2, view.gun().magazine / 4)
                && view.reserve() > 0 && !view.reloading() && view.ticks() - lastVisibleAt > difficulty.reactionTicks() * 2) {
            session.reload(actor); cover = chooseCover(target == null ? investigation : target); state = "掩体补弹";
        }
        view = session.botView(actor);
        if (view.reloading()) {
            if (cover != null) navigate(cover, view, .6, false);
            state = "换弹中"; return;
        }
        if (maybeUtility(view, false)) return;
        if (objectiveAction(view)) return;
        BlockPos goal = chooseObjective(view);
        if (goal != null) {
            double distance = actor.getPos().distanceTo(Vec3d.ofBottomCenter(goal));
            double arrival = view.bombGround() != null && goal.equals(view.bombGround()) ? .8 : 2;
            if (distance > arrival) {
                boolean quiet = difficulty.getLevel() >= 3 && (target != null || investigation != null) && distance < 14;
                navigate(goal, view, quiet ? .42 : 1, quiet);
                state = roleName() + " · " + (view.bombPlanted() ? view.attackers() ? "保护已安装 C4" : "回防拆包" : "执行路线");
            } else {
                actor.setSneaking(difficulty.getLevel() >= 3); lookAround(view); state = roleName() + " · 预瞄警戒";
            }
        } else { lookAround(view); state = "搜索可达路线"; }
    }

    private void resetRound(MyGoSession.BotView view) {
        round = view.round(); roundStartedAt = view.ticks(); purchaseIndex = 0; nextPurchase = view.ticks() + 5 + role * 3;
        target = null; investigation = null; objective = null; cover = null; pendingUtility = null;
        burstAmmo = -1; pauseUntil = 0; nextTrigger = 0; observeAt = view.ticks() + difficulty.reactionTicks();
        nextUtility = view.ticks() + 320 + role * 13; scanAt = 0; lastVisibleAt = -1000;
        navigator.reset(); previousHp = view.hp(); purchases.clear();
        lastDamagedAt = -1000;
        primaryDry = false; pistolDry = false;
        if (teamIntel[view.team()].round != view.round()) teamIntel[view.team()].reset(view.round());
        if (!view.hasPrimary()) {
            int rifle = view.attackers() ? MyGoWeapon.AK47.price : MyGoWeapon.M4A1S.price;
            if (role == 3 && difficulty.getLevel() >= 4 && view.cash() >= MyGoWeapon.AWP.price + 1000) purchases.add("awp");
            else if (view.cash() >= rifle + 650) purchases.add(view.attackers() ? "ak47" : "m4a1s");
            else if (view.cash() >= (view.attackers() ? 1050 : 1250) + 650) purchases.add(view.attackers() ? "mac10" : "mp9");
        }
        purchases.add("vest");
        if (view.cash() >= 3000) purchases.add("helmet");
        if (!view.attackers() && (role == 3 || role == 4)) purchases.add("kit");
        if (difficulty.getLevel() >= 3) {
            purchases.add(role == 1 || role == 4 ? "smoke" : "he");
            if (role == 1) purchases.add("flash");
            if (difficulty.getLevel() >= 4 && role == 2) purchases.add(view.attackers() ? "molotov" : "incendiary");
        }
    }

    private void buy(MyGoSession.BotView view) {
        if (view.ticks() < nextPurchase || purchaseIndex >= purchases.size()) return;
        session.buy(actor, purchases.get(purchaseIndex++)); nextPurchase = view.ticks() + 5 + random.nextInt(5);
        MyGoSession.BotView current = session.botView(actor);
        session.botSelect(actor, current.hasPrimary() ? 0 : 1);
    }

    private void perceive(MyGoSession.BotView view) {
        observeAt = view.ticks() + difficulty.reactionTicks();
        List<ServerPlayerEntity> visible = session.botVisibleEnemies(actor).stream().filter(this::withinView).toList();
        ServerPlayerEntity chosen = visible.stream().min(Comparator.comparingDouble(actor::squaredDistanceTo)).orElse(null);
        TeamIntel intel = teamIntel[view.team()];
        intel.contacts.values().removeIf(contact -> view.ticks() - contact.seenAt() > difficulty.memoryTicks());
        if (chosen != null) {
            Vec3d velocity = Vec3d.ZERO;
            if (target != null && target.enemy().equals(chosen.getUuid())) {
                int age = Math.max(1, view.ticks() - target.seenAt());
                velocity = chosen.getPos().subtract(target.position()).multiply(1.0 / age);
                if (velocity.lengthSquared() > .25) velocity = velocity.normalize().multiply(.5);
            } else acquiredAt = view.ticks() + difficulty.reactionTicks() + random.nextInt(4);
            boolean head = random.nextDouble() < difficulty.headAimChance();
            double height = head ? chosen.getStandingEyeHeight() : chosen.isSneaking() ? .8 : 1.05;
            target = new Contact(chosen.getUuid(), chosen.getPos(), velocity, height, view.ticks());
            intel.contacts.put(chosen.getUuid(), target); lastVisibleAt = view.ticks();
            yawError = random.nextGaussian() * difficulty.aimErrorDegrees() * .65;
            pitchError = random.nextGaussian() * difficulty.aimErrorDegrees() * .4;
            investigation = target;
        } else {
            if (difficulty.getLevel() >= 2) {
                Contact report = intel.contacts.values().stream().max(Comparator.comparingInt(Contact::seenAt)).orElse(null);
                if (report != null && (investigation == null || report.seenAt() > investigation.seenAt())) investigation = report;
                List<Vec3d> heard = session.botHeardEnemies(actor);
                if (!heard.isEmpty() && (investigation == null || view.ticks() - investigation.seenAt() > 20)) {
                    Vec3d source = heard.stream().min(Comparator.comparingDouble(actor.getPos()::squaredDistanceTo)).orElseThrow();
                    investigation = new Contact(new UUID(0, 0), source, Vec3d.ZERO, 1, view.ticks());
                }
            }
            if (investigation != null && view.ticks() - investigation.seenAt() > difficulty.memoryTicks()) investigation = null;
        }
    }

    private boolean withinView(ServerPlayerEntity enemy) {
        Vec3d direction = enemy.getEyePos().subtract(actor.getEyePos()).multiply(1, 0, 1);
        if (direction.lengthSquared() < 1) return true;
        Vec3d facing = actor.getRotationVec(1).multiply(1, 0, 1).normalize();
        return facing.dotProduct(direction.normalize()) >= Math.cos(Math.toRadians(75));
    }

    private ServerPlayerEntity currentVisibleTarget() {
        if (target == null) return null;
        return session.botVisibleEnemies(actor).stream().filter(this::withinView)
                .filter(p -> p.getUuid().equals(target.enemy())).findFirst().orElse(null);
    }

    private void fight(MyGoSession.BotView view) {
        view = equipCombatWeapon(view);
        if (view.gun() == null) { fightWithKnife(view); return; }
        Vec3d point = target.position().add(0, target.bodyHeight(), 0);
        if (difficulty.getLevel() >= 3) point = point.add(target.velocity().multiply(difficulty.reactionTicks() * .4));
        // A visible enemy is not always a sensible duel: Mirage has long open
        // sightlines from spawn. Pistol bots must still execute their role and
        // close/reposition, rather than turn the round into immobile 100 m aim
        // turrets. This changes decisions only, never the weapon's real range.
        double preferredRange = preferredEngagementRange(view.gun());
        if (actor.getPos().distanceTo(target.position()) > preferredRange && !view.reloading() && view.ammo() > 0) {
            session.botFire(actor, false);
            // A distant visible opponent must not keep C4 carriers/defusers
            // stuck on their objective forever merely by remaining in sight.
            // Close threats still fight first; taking damage interrupts safety.
            if (objectiveAction(view, true)) return;
            BlockPos destination = chooseObjective(view);
            if (destination != null && actor.squaredDistanceTo(Vec3d.ofBottomCenter(destination)) > 4) {
                navigate(destination, view, 1, false); state = "远距离转移，寻找有效交战位置";
            } else {
                aim(point, true); state = "守住目标，等待进入有效交战距离";
            }
            return;
        }
        aim(point, true); actor.setSneaking(difficulty.getLevel() >= 3 && actor.getPos().distanceTo(target.position()) > 12);
        if (view.reloading()) {
            session.botFire(actor, false);
            if (cover == null) cover = chooseCover(target);
            if (cover != null) navigate(cover, view, .65, false);
            state = "撤入掩体换弹"; return;
        }
        if (view.ammo() <= 0) {
            session.botFire(actor, false); session.reload(actor); cover = chooseCover(target); state = "弹匣空，换弹"; return;
        }
        if (actor.getPos().distanceTo(point) > 14 && view.gun().scopeLevels() > 0 && !view.scoped()
                && difficulty.getLevel() >= 3 && view.ticks() >= nextScope) {
            session.useItem(actor, Hand.MAIN_HAND); nextScope = view.ticks() + 20;
        }
        if (teammateInShot(point)) {
            session.botFire(actor, false); moveRelative(0, .45 * strafeDirection, false); state = "避让队友射线"; return;
        }
        if (view.ticks() < pauseUntil) {
            session.botFire(actor, false);
            if (view.ticks() >= strafeUntil) { strafeDirection = random.nextBoolean() ? 1 : -1; strafeUntil = view.ticks() + 15; }
            moveRelative(0, .4 * strafeDirection, false); state = "点射间隔横移"; return;
        }
        double movement = actor.getVelocity().horizontalLength();
        if (difficulty.getLevel() >= 3 && actor.isOnGround() && movement > .035) {
            session.botFire(actor, false); moveDirection(actor.getVelocity().multiply(-1), .45, false); state = "急停稳定准星"; return;
        }
        if (difficulty.getLevel() < 3) moveRelative(0, .15 * strafeDirection, false);
        double tolerance = Math.max(2, difficulty.aimErrorDegrees() * 1.5);
        boolean aligned = actor.getRotationVec(1).dotProduct(point.subtract(actor.getEyePos()).normalize()) >= Math.cos(Math.toRadians(tolerance));
        if (!aligned) { session.botFire(actor, false); state = "调整瞄准"; return; }
        if (burstWeapon != view.gun() || burstAmmo < 0 || view.ammo() > burstAmmo) {
            burstWeapon = view.gun(); burstAmmo = view.ammo(); burstBudget = difficulty.burstShots();
        }
        if (burstAmmo - view.ammo() >= burstBudget) {
            session.botFire(actor, false); pauseUntil = view.ticks() + difficulty.burstPauseTicks() + random.nextInt(5);
            burstAmmo = -1; state = "结束短点射"; return;
        }
        if (view.gun().automatic() || view.ticks() >= nextTrigger) {
            session.botFire(actor, true);
            if (!view.gun().automatic()) nextTrigger = view.ticks() + Math.max(view.gun().cycleTicks, difficulty.reactionTicks() / 2);
        }
        state = actor.isSneaking() ? "蹲姿控制点射" : "短点射压制";
    }

    private MyGoSession.BotView equipCombatWeapon(MyGoSession.BotView view) {
        int selected = actor.getInventory().selectedSlot;
        if (view.gun() != null) {
            if (selected == 0) primaryDry = view.ammo() == 0 && view.reserve() == 0;
            if (selected == 1) pistolDry = view.ammo() == 0 && view.reserve() == 0;
        }
        // Draw immediately after planting/throwing; exhausted reserve ammo is
        // not an endless reload. Keep a dry primary holstered until next round.
        int slot = view.hasPrimary() && !primaryDry ? 0 : !pistolDry ? 1 : 2;
        session.botSelect(actor, slot);
        return session.botView(actor);
    }

    private void fightWithKnife(MyGoSession.BotView view) {
        session.botFire(actor, false);
        ServerPlayerEntity enemy = currentVisibleTarget();
        if (enemy == null || target == null) return;
        aim(target.position().add(0, 1.1, 0), true); actor.setSneaking(false);
        double distance = actor.squaredDistanceTo(enemy);
        if (distance > 7) navigate(BlockPos.ofFloored(target.position()), view, .8, false);
        else if (view.ticks() >= nextTrigger && actor.getRotationVec(1).dotProduct(
                enemy.getBoundingBox().getCenter().subtract(actor.getEyePos()).normalize()) > Math.cos(Math.toRadians(15))) {
            session.attack(actor, enemy); actor.swingHand(Hand.MAIN_HAND); nextTrigger = view.ticks() + 12;
        }
        state = "弹药耗尽，战术刀接近";
    }

    private boolean objectiveAction(MyGoSession.BotView view) {
        return objectiveAction(view, false);
    }

    private boolean objectiveAction(MyGoSession.BotView view, boolean distantThreat) {
        boolean safeToCommit = view.ticks() - lastVisibleAt > difficulty.reactionTicks() * 2
                || distantThreat && view.ticks() - lastDamagedAt > difficulty.reactionTicks() * 2;
        if (!view.bombPlanted() && view.attackers() && view.carryingBomb() && session.arena().siteAt(actor.getBlockPos()) != null
                && safeToCommit) {
            session.botObjective(actor); state = "安装 C4"; return true;
        }
        if (view.bombPlanted() && !view.attackers() && view.bombPosition() != null
                && actor.squaredDistanceTo(Vec3d.ofCenter(view.bombPosition())) < 7 && designatedDefuser(view)
                && (!distantThreat || safeToCommit)) {
            session.botObjective(actor); state = "拆除 C4"; return true;
        }
        actor.setSneaking(false);
        return false;
    }

    private boolean designatedDefuser(MyGoSession.BotView view) {
        double own = actor.squaredDistanceTo(Vec3d.ofCenter(view.bombPosition())) - (view.kit() ? 9 : 0);
        for (ServerPlayerEntity teammate : session.botTeammates(actor)) {
            MyGoSession.BotView other = session.botView(teammate);
            if (other.channeling()) return false;
            // A human standing near the bomb is not evidence that they will
            // defuse. Leave the channel free if they use it, but do not let an
            // idle human prevent every bot from completing the objective.
            if (!(teammate instanceof MyGoBotPlayer)) continue;
            double cost = teammate.squaredDistanceTo(Vec3d.ofCenter(view.bombPosition())) - (other.kit() ? 9 : 0);
            if (cost < own - .01 || Math.abs(cost - own) < .01 && teammate.getUuid().compareTo(actor.getUuid()) < 0) return false;
        }
        return true;
    }

    private boolean designatedRecovery(MyGoSession.BotView view) {
        Vec3d bomb = Vec3d.ofBottomCenter(view.bombGround());
        double own = actor.squaredDistanceTo(bomb);
        for (ServerPlayerEntity teammate : session.botTeammates(actor)) {
            if (!(teammate instanceof MyGoBotPlayer)) continue;
            double other = teammate.squaredDistanceTo(bomb);
            if (other < own - .01 || Math.abs(other - own) < .01 && teammate.getUuid().compareTo(actor.getUuid()) < 0) return false;
        }
        return true;
    }

    private BlockPos chooseObjective(MyGoSession.BotView view) {
        if (objective != null && view.ticks() < goalAt) return objective;
        goalAt = view.ticks() + 35 + role;
        List<BlockPos> sites = session.arena().sites();
        int mainSite = Math.floorMod(view.round(), sites.size());
        BlockPos selected;
        if (view.bombPlanted() && view.bombPosition() != null) {
            if (!view.attackers()) selected = view.bombPosition();
            else {
                double angle = role * Math.PI * 2 / 5;
                selected = view.bombPosition().add((int) Math.round(Math.cos(angle) * 7), 0, (int) Math.round(Math.sin(angle) * 7));
            }
        } else if (view.attackers()) {
            if (view.bombGround() != null && designatedRecovery(view)) selected = view.bombGround();
            else if (view.carryingBomb()) selected = sites.get(mainSite);
            else if (role == 4 && view.ticks() - roundStartedAt < 1200) selected = sites.get(1 - mainSite);
            else selected = sites.get(mainSite);
        } else if (investigation != null && (role == 4 || actor.getPos().distanceTo(investigation.position()) < 20)) {
            selected = BlockPos.ofFloored(investigation.position());
        } else selected = sites.get(role < 2 ? 0 : role < 4 ? 1 : mainSite);
        // Small per-role staging offsets reduce five actors standing on the same
        // corner. The actual carrier and designated defuser keep the exact goal.
        if (!(view.carryingBomb() || view.bombGround() != null || view.bombPlanted() && !view.attackers()))
            selected = selected.add(role % 2 == 0 ? 2 : -2, 0, role < 2 ? 2 : -2);
        objective = selected;
        return objective;
    }

    private boolean maybeUtility(MyGoSession.BotView view, boolean visible) {
        if (difficulty.getLevel() < 3 || view.ticks() < nextUtility || view.channeling()) return false;
        Contact known = target != null && view.ticks() - target.seenAt() < 45 ? target : investigation;
        if (known == null || view.ticks() - known.seenAt() > 45) return false;
        double distance = actor.getPos().distanceTo(known.position());
        if (distance < 8 || distance > 23 || random.nextDouble() > difficulty.utilityChance() * .12) return false;
        Map<MyGoUtilities.Type, Integer> held = session.botGrenades(actor);
        MyGoUtilities.Type type = null;
        Vec3d destination = known.position();
        if ((view.hp() < 35 || view.reloading()) && held.getOrDefault(MyGoUtilities.Type.SMOKE, 0) > 0) {
            type = MyGoUtilities.Type.SMOKE;
            destination = actor.getPos().lerp(known.position(), .45);
        } else if (held.getOrDefault(MyGoUtilities.Type.HE, 0) > 0) type = MyGoUtilities.Type.HE;
        else if (!visible && held.getOrDefault(MyGoUtilities.Type.FLASH, 0) > 0) type = MyGoUtilities.Type.FLASH;
        else if (held.getOrDefault(MyGoUtilities.Type.MOLOTOV, 0) > 0) type = MyGoUtilities.Type.MOLOTOV;
        else if (held.getOrDefault(MyGoUtilities.Type.INCENDIARY, 0) > 0) type = MyGoUtilities.Type.INCENDIARY;
        if (type == null) return false;
        final Vec3d landing = destination;
        double friendlyRadius = type == MyGoUtilities.Type.FLASH ? 12 : 5;
        if (session.botTeammates(actor).stream().anyMatch(mate -> mate.getPos().distanceTo(landing) < friendlyRadius)) return false;
        pendingUtility = type; utilityTarget = destination; prepareUtilityAt = view.ticks();
        session.botFire(actor, false);
        return prepareUtility(view);
    }

    private boolean prepareUtility(MyGoSession.BotView view) {
        session.botFire(actor, false); actor.setSneaking(false);
        if (view.ticks() - prepareUtilityAt > 40 || !"LIVE".equals(view.phase())) { pendingUtility = null; return false; }
        Vec3d offset = utilityTarget.add(0, .4, 0).subtract(actor.getEyePos());
        double flat = offset.horizontalLength(), speed = 750 * .9 / MyGoWeapon.SOURCE_UNITS_PER_BLOCK / 20;
        double v2 = speed * speed, g = MyGoGrenadePhysics.GRAVITY;
        double discriminant = v2 * v2 - g * (g * flat * flat + 2 * offset.y * v2);
        double launchPitch = discriminant > 0 && flat > .1 ? -Math.toDegrees(Math.atan((v2 - Math.sqrt(discriminant)) / (g * flat))) : -20;
        // Invert the strong throw's automatic ten-degree lift before applying
        // the same bounded visible aim as gun handling.
        float inputPitch = (float) ((launchPitch + 10) * 9 / 8);
        float yaw = (float) Math.toDegrees(Math.atan2(-offset.x, offset.z));
        turn(yaw, inputPitch, 1);
        state = "准备投掷 " + pendingUtility.label;
        if (Math.abs(MathHelper.wrapDegrees(actor.getYaw() - yaw)) < 7 && Math.abs(actor.getPitch() - inputPitch) < 6) {
            session.botGrenade(actor, pendingUtility, MyGoUtilities.ThrowMode.STRONG);
            pendingUtility = null; nextUtility = view.ticks() + 200 + random.nextInt(100);
        }
        return true;
    }

    private void navigate(BlockPos goal, MyGoSession.BotView view, double speed, boolean quiet) {
        Vec3d next = navigator.next(actor, goal, view.ticks());
        if (next == null) { lookAround(view); return; }
        Vec3d direction = next.subtract(actor.getPos()).multiply(1, 0, 1);
        if (direction.lengthSquared() < .02) return;
        if (target != null && view.ticks() - target.seenAt() < difficulty.memoryTicks()) aim(target.position().add(0, 1.1, 0), false);
        else aim(next.add(0, 1.35, 0), false);
        Vec3d avoidance = Vec3d.ZERO;
        for (ServerPlayerEntity mate : session.botTeammates(actor)) {
            Vec3d away = actor.getPos().subtract(mate.getPos()).multiply(1, 0, 1);
            double distance = away.length();
            if (distance > .05 && distance < 1.2) avoidance = avoidance.add(away.normalize().multiply((1.2 - distance) * .65));
        }
        direction = direction.normalize().add(avoidance);
        actor.setSneaking(quiet);
        moveDirection(direction, speed, next.y > actor.getY() + .35 || actor.horizontalCollision);
    }

    private BlockPos chooseCover(Contact threat) {
        if (threat == null) return null;
        BlockPos best = null; double bestScore = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 12; i++) {
            double angle = i * Math.PI / 6;
            Vec3d candidate = actor.getPos().add(Math.cos(angle) * 4, 0, Math.sin(angle) * 4);
            Vec3d body = candidate.add(0, 1.1, 0);
            var wall = actor.getServerWorld().raycast(new RaycastContext(threat.position().add(0, 1.5, 0), body,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, actor));
            if (wall.getType() == HitResult.Type.MISS) continue;
            Box box = actor.getBoundingBox().offset(candidate.subtract(actor.getPos()));
            if (actor.getServerWorld().getBlockCollisions(actor, box).iterator().hasNext()) continue;
            double cost = candidate.squaredDistanceTo(actor.getPos()) - candidate.distanceTo(threat.position()) * .15;
            if (cost < bestScore) { best = BlockPos.ofFloored(candidate); bestScore = cost; }
        }
        return best;
    }

    private boolean teammateInShot(Vec3d point) {
        Vec3d eye = actor.getEyePos();
        for (ServerPlayerEntity mate : session.botTeammates(actor)) {
            if (mate.getBoundingBox().expand(.08).raycast(eye, point).isPresent()) return true;
        }
        return false;
    }

    private void lookAround(MyGoSession.BotView view) {
        if (investigation != null && view.ticks() - investigation.seenAt() < difficulty.memoryTicks()) {
            aim(investigation.position().add(0, 1.1, 0), false); return;
        }
        if (view.ticks() >= scanAt) {
            Vec3d opponentSpawn = Vec3d.ofBottomCenter(session.arena().spawn(!view.attackers(), role));
            Vec3d toward = opponentSpawn.subtract(actor.getPos());
            scanYaw = (float) Math.toDegrees(Math.atan2(-toward.x, toward.z)) + (float) (random.nextDouble() * 140 - 70);
            scanAt = view.ticks() + 35 + random.nextInt(40);
        }
        turn(scanYaw, 0, .5);
    }

    private void aim(Vec3d point, boolean combat) {
        Vec3d offset = point.subtract(actor.getEyePos());
        float yaw = (float) (Math.toDegrees(Math.atan2(-offset.x, offset.z)) + (combat ? yawError : 0));
        float pitch = (float) (-Math.toDegrees(Math.atan2(offset.y, offset.horizontalLength())) + (combat ? pitchError : 0));
        turn(yaw, pitch, combat ? .20 + difficulty.recoilControl() * .70 : .65);
    }

    private void turn(float yaw, float pitch, double gain) {
        float cap = (float) difficulty.maxTurnDegrees();
        float yawDelta = MathHelper.wrapDegrees(yaw - actor.getYaw());
        float pitchDelta = pitch - actor.getPitch();
        actor.setYaw(actor.getYaw() + MathHelper.clamp(yawDelta * (float) gain, -cap, cap));
        actor.setPitch(MathHelper.clamp(actor.getPitch() + MathHelper.clamp(pitchDelta * (float) gain, -cap * .8f, cap * .8f), -87, 87));
        actor.setHeadYaw(actor.getYaw()); actor.setBodyYaw(actor.getYaw());
    }

    private void moveDirection(Vec3d direction, double speed, boolean jump) {
        if (direction.horizontalLengthSquared() < .0001) return;
        Vec3d normalized = direction.multiply(1, 0, 1).normalize();
        double yaw = Math.toRadians(actor.getYaw());
        double forward = normalized.x * -Math.sin(yaw) + normalized.z * Math.cos(yaw);
        double sideways = normalized.x * Math.cos(yaw) + normalized.z * Math.sin(yaw);
        moveRelative(forward * speed, sideways * speed, jump);
    }

    private void moveRelative(double forward, double sideways, boolean jump) {
        double scale = actor.isSneaking() ? .3 : 1;
        actor.input((float) (forward * scale), (float) (sideways * scale), jump && actor.isOnGround());
    }

    private String roleName() {
        if (!attacking) return switch (role) { case 0 -> "A 点锚点"; case 1 -> "A 点支援"; case 2 -> "B 点锚点"; case 3 -> "B 点支援/拆包"; default -> "机动回防"; };
        return switch (role) { case 0 -> "突破手"; case 1 -> "支援手"; case 2 -> "交叉火力"; case 3 -> "狙击/拆包手"; default -> "侧翼/回防手"; };
    }

    static double preferredEngagementRange(MyGoWeapon gun) {
        if (gun.sniper()) return 90;
        if (gun.shotgun()) return 12;
        if (gun.smg()) return 28;
        if (gun.pistol()) return gun == MyGoWeapon.DEAGLE || gun == MyGoWeapon.REVOLVER ? 36 : 24;
        return 60;
    }
}
