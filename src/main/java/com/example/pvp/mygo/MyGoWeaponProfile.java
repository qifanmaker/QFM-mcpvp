package com.example.pvp.mygo;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Reason: broad weapon classes erased original handling differences. These are
 * the resolved legacy CS:GO numerical attributes, not model animation timings.
 * Review: load once, reject missing/nonfinite attributes, and keep wall penetration
 * separate from the historical MyGoWeapon.penetration armour-ratio alias.
 */
public final class MyGoWeaponProfile {
    private final Map<String, Double> attributes;

    private MyGoWeaponProfile(Map<String, Double> attributes) {
        this.attributes = Map.copyOf(attributes);
    }

    public static MyGoWeaponProfile of(MyGoWeapon weapon) {
        MyGoWeaponProfile result = Holder.PROFILES.get(weapon.name());
        if (result == null) throw new IllegalStateException("Missing CS:GO profile: " + weapon.name());
        return result;
    }

    public double value(String key) {
        Double result = this.attributes.get(key);
        if (result == null) throw new IllegalArgumentException("Missing CS:GO attribute: " + key);
        return result;
    }

    /** Alternate mode means scoped, burst, or silenced, depending on the gun. */
    public double value(String key, boolean alternate) {
        return alternate && this.attributes.containsKey(key + " alt") ? value(key + " alt") : value(key);
    }

    public double valueOr(String key, double fallback) { return this.attributes.getOrDefault(key, fallback); }
    public boolean automatic() { return value("is full auto") != 0; }
    public boolean hasBurstMode() { return value("has burst mode") != 0; }
    public boolean hasSilencer() { return value("has silencer") != 0; }
    public double maxSpeed(boolean alternate) { return value("max player speed", alternate); }
    public double attackMoveFactor() { return value("attack movespeed factor"); }
    public double wallPenetration() { return value("penetration"); }
    public int recoilSeed() { return (int) value("recoil seed"); }
    public double burstIntervalSeconds() { return valueOr("time between burst shots", 0); }
    public double burstCycleSeconds() { return valueOr("cycletime when in burst mode", value("cycletime")); }
    public int zoomTicks(int level) {
        return Math.max(1, (int) Math.ceil(value("zoom time " + Math.clamp(level, 0, 2)) * 20 - 1e-9));
    }

    public double recoverySeconds(boolean crouched, double bullets) {
        String posture = crouched ? "crouch" : "stand";
        double initial = value("recovery time " + posture);
        double terminal = value("recovery time " + posture + " final");
        double start = value("recovery transition start bullet"), end = value("recovery transition end bullet");
        double fraction = end <= start ? 0 : Math.clamp((bullets - start) / (end - start), 0, 1);
        return Math.max(.01, initial + (terminal - initial) * fraction);
    }

    public Map<String, Double> attributes() { return this.attributes; }

    private static final class Holder {
        private static final Map<String, MyGoWeaponProfile> PROFILES = load();

        private static Map<String, MyGoWeaponProfile> load() {
            try (var stream = MyGoWeaponProfile.class.getResourceAsStream("/pvp/mygo/csgo-weapon-profiles.json")) {
                if (stream == null) throw new IOException("Missing CS:GO weapon profiles");
                JsonObject weapons = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("weapons");
                Map<String, MyGoWeaponProfile> result = new HashMap<>();
                for (var entry : weapons.entrySet()) {
                    Map<String, Double> values = new HashMap<>();
                    for (Map.Entry<String, JsonElement> attribute : entry.getValue().getAsJsonObject().entrySet()) {
                        double value = attribute.getValue().getAsDouble();
                        if (!Double.isFinite(value)) throw new IOException("Nonfinite CS:GO attribute");
                        values.put(attribute.getKey(), value);
                    }
                    result.put(entry.getKey(), new MyGoWeaponProfile(values));
                }
                return Map.copyOf(result);
            } catch (IOException | RuntimeException error) {
                throw new IllegalStateException("Cannot load CS:GO weapon handling data", error);
            }
        }
    }
}
