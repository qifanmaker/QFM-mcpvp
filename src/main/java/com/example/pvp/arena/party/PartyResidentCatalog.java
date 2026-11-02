package com.example.pvp.arena.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Audited reusable terrain for the registered Party games. Keeping the complete
 * static base loaded avoids repeatedly writing millions of unchanged cells.
 * Random routes, player platforms and runtime templates remain actual-round
 * work: invoking stage with fake players would omit geometry and mutate live
 * chunk tickets or player state. The profiles deliberately contain only fixed
 * source placements; every helper geometry is still listed for eager decoding.
 */
public final class PartyResidentCatalog {
    /** Current complete maps use this height; Dropper must retain source Y. */
    public static final int REFERENCE_CENTER_Y = 101;
    private static final String RESOURCE = "/pvp/party/resident_profiles.json";
    private static final String[] TILE_VARIANTS = {"small", "medium", "large", "crowd"};
    private static PartyResidentCatalog cached;

    public record Placement(String geometry, BlockPos translationRelativeToCenter) {
        public Placement {
            if (geometry == null || !geometry.matches("[a-z_][a-z0-9_]*"))
                throw new IllegalArgumentException("Invalid resident geometry name");
            translationRelativeToCenter = Objects.requireNonNull(translationRelativeToCenter, "translation").toImmutable();
        }

        public BlockPos translation(BlockPos center) {
            if (center.getY() != REFERENCE_CENTER_Y)
                throw new IllegalArgumentException("Party resident centers must use Y=" + REFERENCE_CENTER_Y);
            return center.add(this.translationRelativeToCenter);
        }
    }

    public record Profile(String key, PartyMinigame game, PartyArenaBounds bounds, List<Placement> placements) {
        public Profile {
            if (key == null || !key.matches("[a-z_][a-z0-9_]*"))
                throw new IllegalArgumentException("Invalid resident profile key");
            Objects.requireNonNull(game, "game");
            Objects.requireNonNull(bounds, "bounds");
            placements = List.copyOf(placements);
            if (placements.isEmpty()) throw new IllegalArgumentException("Resident profile has no terrain: " + key);
        }
    }

    private final List<String> geometryNames;
    private final Map<String, Profile> profiles;

    private PartyResidentCatalog(List<String> geometryNames, Map<String, Profile> profiles) {
        this.geometryNames = List.copyOf(geometryNames);
        this.profiles = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
    }

    public static synchronized PartyResidentCatalog load() throws IOException {
        if (cached != null) return cached;
        try (var input = PartyResidentCatalog.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("Missing Party resident profiles");
            try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (root.get("schemaVersion").getAsInt() != 1
                        || root.get("referenceCenterY").getAsInt() != REFERENCE_CENTER_Y)
                    throw new IOException("Unsupported Party resident profile format");
                var geometries = new ArrayList<String>();
                var geometrySet = new HashSet<String>();
                for (JsonElement encoded : root.getAsJsonArray("geometryNames")) {
                    String name = encoded.getAsString();
                    if (!name.matches("[a-z_][a-z0-9_]*") || !geometrySet.add(name)
                            || PartyResidentCatalog.class.getResource("/pvp/party/geometry/" + name + ".json.gz") == null)
                        throw new IOException("Invalid or missing resident geometry: " + name);
                    geometries.add(name);
                }
                var profiles = new LinkedHashMap<String, Profile>();
                var games = new HashSet<PartyMinigame>();
                for (JsonElement encoded : root.getAsJsonArray("profiles")) {
                    JsonObject row = encoded.getAsJsonObject();
                    PartyMinigame game = PartyMinigame.valueOf(row.get("game").getAsString());
                    if (game.sourceId() != row.get("sourceId").getAsInt())
                        throw new IOException("Resident profile source ID differs from its game");
                    JsonArray box = row.getAsJsonArray("bounds");
                    if (box.size() != 4) throw new IOException("Invalid resident profile bounds");
                    PartyArenaBounds bounds = new PartyArenaBounds(box.get(0).getAsInt(), box.get(1).getAsInt(),
                            box.get(2).getAsInt(), box.get(3).getAsInt());
                    var placements = new ArrayList<Placement>();
                    for (JsonElement value : row.getAsJsonArray("placements")) {
                        JsonObject placement = value.getAsJsonObject();
                        String geometry = placement.get("geometry").getAsString();
                        if (!geometrySet.contains(geometry)) throw new IOException("Unlisted resident placement: " + geometry);
                        JsonArray offset = placement.getAsJsonArray("translationRelativeToCenter");
                        if (offset.size() != 3) throw new IOException("Invalid resident placement translation");
                        placements.add(new Placement(geometry, new BlockPos(offset.get(0).getAsInt(), offset.get(1).getAsInt(), offset.get(2).getAsInt())));
                    }
                    Profile profile = new Profile(row.get("key").getAsString(), game, bounds, placements);
                    if (profiles.putIfAbsent(profile.key(), profile) != null)
                        throw new IOException("Duplicate resident profile: " + profile.key());
                    games.add(game);
                }
                if (!games.equals(java.util.Set.of(PartyMinigame.values())))
                    throw new IOException("Resident profiles do not cover every registered Party game");
                cached = new PartyResidentCatalog(geometries, profiles);
                return cached;
            }
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException | com.google.gson.JsonParseException exception) {
            throw new IOException("Invalid Party resident profiles", exception);
        }
    }

    public List<Profile> allProfiles() { return List.copyOf(this.profiles.values()); }
    public List<String> geometryNames() { return this.geometryNames; }

    public Profile profile(String key) {
        Profile result = this.profiles.get(key);
        if (result == null) throw new IllegalArgumentException("Unknown resident profile: " + key);
        return result;
    }

    /** Only the original four-player Tile selection consumes a random draw. */
    public Profile select(PartyMinigame game, int playerCount, Random random) {
        Objects.requireNonNull(random, "random");
        int tileVariant = game == PartyMinigame.TILE_CAPTURE && playerCount == 4 ? random.nextInt(3) : 0;
        return this.profileFor(game, playerCount, tileVariant);
    }

    public Profile profileFor(PartyMinigame game, int playerCount, int tileVariant) {
        Objects.requireNonNull(game, "game");
        if (!game.supportsPlayers(playerCount)) throw new IllegalArgumentException("Unsupported Party player count");
        String key = switch (game) {
            case SPLEEF, TILE_CAPTURE, RUNNER -> variantKey(game.sourceId(), playerCount, tileVariant);
            default -> game.name().toLowerCase(Locale.ROOT);
        };
        return this.profile(key);
    }

    /** Pure selection rule; no Minecraft registries or fake participants. */
    static String variantKey(int sourceId, int playerCount, int tileVariant) {
        if (playerCount < 2 || playerCount > 10) throw new IllegalArgumentException("Unsupported Party player count");
        return switch (sourceId) {
            case 408 -> playerCount >= 5 ? "spleef_large" : "spleef_small";
            case 412 -> {
                int variant = playerCount >= 5 ? 3 : playerCount <= 3 ? 0 : tileVariant;
                if (variant < 0 || variant > 2 && playerCount == 4)
                    throw new IllegalArgumentException("Four-player Tile variant must be 0..2");
                yield "tile_capture_" + TILE_VARIANTS[variant];
            }
            case 415 -> playerCount <= 4 ? "runner_small" : "runner_large";
            default -> throw new IllegalArgumentException("Game does not have a static terrain variant: " + sourceId);
        };
    }
}
