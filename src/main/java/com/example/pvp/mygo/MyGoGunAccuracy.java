package com.example.pvp.mygo;

import java.util.Random;

/**
 * Reason: fixed Gaussian class multipliers removed weapon-specific recovery,
 * crouching, velocity accuracy and the Negev's sustained-fire convergence.
 * This adapter uses original numerical cones/recovery/seed parameters. It does
 * not claim Source animation, command RNG or recoil tables are byte-identical.
 * Review: firing penalty decays exponentially instead of vanishing each legal
 * pistol cycle; samples use two bounded radial cones, never unbounded Gaussian
 * tails; camera recoil is a separate seeded pattern and never changes position.
 */
public final class MyGoGunAccuracy {
    public record Offset(double yawDegrees, double pitchDegrees) { }
    public record Kick(double yawDegrees, double pitchDegrees) { }

    private final MyGoWeapon weapon;
    private final MyGoWeaponProfile profile;
    private int lastTick = -1, lastShot = -10000, shots;
    private double firePenalty, landPenalty, inaccuracy, spread, sequenceRecovery, previousVerticalVelocity;
    private int lastCameraTick = -1;
    private double cameraYaw, cameraPitch;
    private boolean wasGrounded = true;

    public MyGoGunAccuracy(MyGoWeapon weapon) {
        this.weapon = weapon;
        this.profile = weapon.profile();
    }

    /** speedFraction is measured speed/current weapon maximum; vertical velocity is blocks/tick. */
    public void update(int tick, boolean alternate, boolean crouched, boolean onGround, boolean climbing,
                       double speedFraction, double verticalVelocity) {
        double elapsed = this.lastTick < 0 ? 0 : Math.max(0, tick - this.lastTick) / 20.0;
        this.lastTick = tick;
        double recovery = this.profile.recoverySeconds(crouched, this.shots);
        this.firePenalty *= Math.pow(.1, elapsed / recovery);
        this.landPenalty *= Math.pow(.1, elapsed / Math.max(.05, recovery));
        // Long gaps reset the deterministic spray without destroying residual
        // accuracy immediately after a short burst.
        if (tick - this.lastShot > Math.max(4, Math.ceil(recovery * 40))) this.shots = 0;
        if (!this.wasGrounded && onGround) {
            // Tiny support/step changes must not impose the full high-fall
            // penalty. Derive severity from the last sampled downward speed.
            double impact = Math.clamp((-this.previousVerticalVelocity - .1) / .5, 0, 1);
            this.landPenalty = Math.max(this.landPenalty, this.profile.value("inaccuracy land", alternate) * impact);
        }
        this.wasGrounded = onGround;
        this.previousVerticalVelocity = verticalVelocity;

        double base = this.profile.value(crouched ? "inaccuracy crouch" : "inaccuracy stand", alternate) / 1000;
        double movement = Math.clamp((Math.max(0, speedFraction) - .34) / .61, 0, 1);
        double moving = this.profile.value("inaccuracy move", alternate) / 1000 * movement;
        double air = 0;
        if (!onGround) {
            // Valve's Air Time model depends on velocity, including walking off
            // ledges. Minecraft gravity requires its own 0.42 jump normalization.
            double fraction = Math.clamp(Math.abs(verticalVelocity) / .42, 0, 1);
            double apex = this.profile.value("inaccuracy jump", alternate) / 1000;
            double launch = this.profile.value("inaccuracy jump initial") / 1000;
            air = apex + Math.max(0, launch - apex) * fraction;
        }
        double ladder = climbing ? this.profile.value("inaccuracy ladder", alternate) / 1000 : 0;
        this.spread = this.profile.value("spread", alternate) / 1000;
        this.inaccuracy = Math.max(base, ladder) + moving + air + this.firePenalty + this.landPenalty;
        if (this.weapon == MyGoWeapon.NEGEV) {
            // Its original 9..12 bullet recovery transition identifies the
            // convergence phase; use the accurate alternate fire penalty there.
            double start = this.profile.value("recovery transition start bullet");
            double end = this.profile.value("recovery transition end bullet");
            double convergence = Math.clamp((this.shots - start) / Math.max(1, end - start), 0, 1);
            this.inaccuracy += (1 - convergence) * .12;
        }
        this.sequenceRecovery = recovery;
    }

    /** Independent inaccuracy and spread radial samples; offsets are angular degrees. */
    public Offset sample(Random random) {
        double a = random.nextDouble() * Math.PI * 2, b = random.nextDouble() * Math.PI * 2;
        double r1 = random.nextDouble() * this.inaccuracy, r2 = random.nextDouble() * this.spread;
        return new Offset(Math.toDegrees(Math.atan(Math.cos(a) * r1 + Math.cos(b) * r2)),
                Math.toDegrees(Math.atan(Math.sin(a) * r1 + Math.sin(b) * r2)));
    }

    /** Call once after every shot, not once per shotgun pellet. Positive pitch kicks upward. */
    public Kick fired(int tick, boolean alternate) {
        this.lastShot = tick;
        this.shots++;
        boolean accurateNegev = this.weapon == MyGoWeapon.NEGEV
                && this.shots >= this.profile.value("recovery transition end bullet");
        double added = this.profile.value("inaccuracy fire", alternate || accurateNegev) / 1000;
        this.firePenalty = Math.min(1, this.firePenalty + added);
        return recoil(this.weapon, this.shots - 1, alternate);
    }

    /**
     * Seeded smoothed per-gun recoil. Original seed/angle/magnitude properties
     * are used; .02 is a declared Minecraft camera scale rather than a Valve
     * animation constant. This avoids the previous shared sinusoidal pattern.
     */
    public static Kick recoil(MyGoWeapon weapon, int shot, boolean alternate) {
        MyGoWeaponProfile profile = weapon.profile();
        Random random = new Random(profile.recoilSeed());
        double previousAngle = profile.value("recoil angle", alternate), previousMagnitude = 0;
        double angle = previousAngle, magnitude = 0;
        for (int i = 0; i <= Math.clamp(shot, 0, 255); i++) {
            double suppress = Math.min(1, (i + 1) / 4.0);
            double targetAngle = profile.value("recoil angle", alternate)
                    + (random.nextDouble() * 2 - 1) * profile.value("recoil angle variance", alternate) * suppress;
            double targetMagnitude = Math.max(0, profile.value("recoil magnitude", alternate)
                    + (random.nextDouble() * 2 - 1) * profile.value("recoil magnitude variance", alternate));
            angle = previousAngle * .55 + targetAngle * .45;
            magnitude = i == 0 ? targetMagnitude : previousMagnitude * .55 + targetMagnitude * .45;
            previousAngle = angle;
            previousMagnitude = magnitude;
        }
        double radians = Math.toRadians(angle);
        return new Kick(Math.sin(radians) * magnitude * .02,
                Math.cos(radians) * magnitude * .02);
    }

    public int shots() { return this.shots; }
    public double inaccuracy() { return this.inaccuracy; }
    public double spread() { return this.spread; }
    public double firingPenalty() { return this.firePenalty; }
    public double recoverySeconds() { return this.sequenceRecovery; }
    public boolean recentlyFired(int tick) { return tick - this.lastShot <= Math.max(2, this.weapon.cycleTicks); }

    /** Record only the deltas actually applied after clamping the player's pitch. */
    public void recordCameraKick(int tick, double yawDelta, double upwardPitchDelta) {
        if (!Double.isFinite(yawDelta) || !Double.isFinite(upwardPitchDelta)) return;
        this.cameraYaw += yawDelta;
        this.cameraPitch += upwardPitchDelta;
        this.lastCameraTick = tick;
    }

    /**
     * Apply using the same convention as fired(): yaw += kick.yaw, pitch -=
     * kick.pitch. No baseline view angle is saved: the player can keep aiming
     * and counter-steering while only our previously added offset decays.
     */
    public Kick recoverCamera(int tick) {
        if (this.lastCameraTick < 0) { this.lastCameraTick = tick; return new Kick(0, 0); }
        double elapsed = Math.max(0, tick - this.lastCameraTick) / 20.0;
        this.lastCameraTick = tick;
        if (tick - this.lastShot <= 2 || elapsed == 0) return new Kick(0, 0);
        double recovery = this.profile.recoverySeconds(false, this.shots);
        double fraction = 1 - Math.pow(.1, elapsed / recovery);
        double yaw = this.cameraYaw * fraction, pitch = this.cameraPitch * fraction;
        this.cameraYaw -= yaw;
        this.cameraPitch -= pitch;
        if (Math.abs(yaw) < 1e-15) yaw = 0;
        if (Math.abs(pitch) < 1e-15) pitch = 0;
        return new Kick(yaw == 0 ? 0 : -yaw, pitch == 0 ? 0 : -pitch);
    }

    public void reset() {
        this.lastTick = -1; this.lastShot = -10000; this.shots = 0;
        this.firePenalty = 0; this.landPenalty = 0; this.inaccuracy = 0; this.spread = 0;
        this.wasGrounded = true; this.sequenceRecovery = 0; this.previousVerticalVelocity = 0;
        this.lastCameraTick = -1; this.cameraYaw = 0; this.cameraPitch = 0;
    }
}
