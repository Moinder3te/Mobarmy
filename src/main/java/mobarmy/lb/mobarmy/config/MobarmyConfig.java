package mobarmy.lb.mobarmy.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import mobarmy.lb.mobarmy.MobarmyRef;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class MobarmyConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int farmDurationSeconds = 600;          // 10 min
    public int arrangeDurationSeconds = 600;       // 10 min
    public int respawnDelayTicks = 600;            // 30 sec
    public int waveDelayTicks = 100;               // 5 sec
    public int waveCount = 3;

    /** Maximale Anzahl gleichzeitig lebender Wave-Mobs PRO Arena. Schützt
     *  den Server vor Overload. Übrige Mobs werden gequeued und nachgespawnt
     *  sobald wieder Platz ist. */
    public int maxAliveMobs = 80;
    /** Wieviele Mobs pro Server-Tick aus der Queue gespawnt werden dürfen. */
    public int mobSpawnsPerTick = 4;
    /** Spawn-Cluster-Radius in Blöcken um {@code mobSpawnCenter} (eng zusammen). */
    public int mobSpawnRadius = 5;

    public BlockPosJson lobbyPos = new BlockPosJson(0, 100, 0);
    // Big ROUND arena up in the air. Stone roof seals out skylight, full
    // sea-lantern ceiling = light level 15 everywhere. In addition the
    // ENTITY_LOAD hook in MobarmyMod blocks ALL non-wave mob spawns in the
    // arena dimension, so the arena is guaranteed mob-free between waves.
    /** Arena radius in blocks. Diameter = 2*radius+1. */
    public int arenaRadius = 120;
    /** Y of the arena floor. */
    public int arenaFloorY = 130;
    /** Vertical interior height (blocks between floor and ceiling). */
    public int arenaHeight = 55;

    /** Spacing (blocks, along +X) between parallel team arenas. */
    public int arenaSpacing = 700;

    // Legacy fields kept so existing world configs still load. New geometry uses
    // arenaRadius/arenaFloorY/arenaHeight; these are only fallback markers.
    public BlockPosJson arenaPos1 = new BlockPosJson(-80, 130, -80);
    public BlockPosJson arenaPos2 = new BlockPosJson(80, 165, 80);
    public BlockPosJson spectatorPos = new BlockPosJson(0, 180, 0);
    public BlockPosJson arenaSpawnA = new BlockPosJson(-65, 131, 0);
    public BlockPosJson arenaSpawnB = new BlockPosJson(65, 131, 0);
    public BlockPosJson mobSpawnCenter = new BlockPosJson(0, 131, 0);

    public long randomizerSeed = 0L; // 0 = random
    public boolean buildArenaOnStart = true;

    public static class BlockPosJson {
        public int x, y, z;
        public BlockPosJson() {}
        public BlockPosJson(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    public static MobarmyConfig load(MinecraftServer server) {
        Path file = configFile(server);
        if (!Files.exists(file)) {
            MobarmyConfig cfg = new MobarmyConfig();
            cfg.save(server);
            return cfg;
        }
        try {
            String json = Files.readString(file);
            MobarmyConfig cfg = GSON.fromJson(json, MobarmyConfig.class);
            if (cfg == null) cfg = new MobarmyConfig();
            return cfg;
        } catch (IOException e) {
            MobarmyRef.LOG.error("Failed to load config", e);
            return new MobarmyConfig();
        }
    }

    public void save(MinecraftServer server) {
        Path file = configFile(server);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this));
        } catch (IOException e) {
            MobarmyRef.LOG.error("Failed to save config", e);
        }
    }

    private static Path configFile(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).resolve("mobarmy.json");
    }
}

