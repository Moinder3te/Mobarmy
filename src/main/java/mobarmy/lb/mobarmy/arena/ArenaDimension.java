package mobarmy.lb.mobarmy.arena;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import net.minecraft.world.rule.GameRules;

/**
 * Identifier + lookup helper for the dedicated "arena" dimension that mirrors
 * the overworld but is built and used only for arenas — keeps the player-facing
 * overworld free of giant cylinders.
 *
 * The dimension itself is defined in the data pack under
 * {@code data/mobarmy/dimension/arena.json}.
 */
public final class ArenaDimension {
    private ArenaDimension() {}

    public static final Identifier ID = Identifier.of("mobarmy", "arena");
    public static final RegistryKey<World> WORLD_KEY = RegistryKey.of(RegistryKeys.WORLD, ID);

    /**
     * Returns the arena world, or — if the data-pack dimension wasn't loaded
     * (e.g. on a pre-existing world that didn't have it at creation time) —
     * falls back to the overworld so the game still works.
     */
    public static ServerWorld get(MinecraftServer server) {
        ServerWorld w = server.getWorld(WORLD_KEY);
        return w != null ? w : server.getOverworld();
    }

    /**
     * Lock down the arena dimension for combat: disable natural spawns, mob
     * griefing, tile drops, raids, etc. Safe to call multiple times.
     *
     * Time and skylight are handled by the custom dimension type
     * ({@code data/mobarmy/dimension_type/arena.json}) which has
     * {@code has_skylight: false} and {@code has_fixed_time: true}, so undead
     * mobs can NEVER burn regardless of gamerule state.
     *
     * SAFETY: Falls die Arena-Dimension nicht geladen ist (Datapack fehlt o.ä.),
     * wird {@link #get(MinecraftServer)} auf die Overworld zurückfallen. Damit
     * wir nicht versehentlich Spielregeln auf die Lobby/Overworld anwenden,
     * machen wir eine harte Identitäts-Prüfung.
     */
    public static void applyGameRules(ServerWorld w) {
        if (w == null) return;
        if (w.getRegistryKey() != WORLD_KEY) {
            // Niemals Gamerules in der Overworld/Lobby ändern.
            return;
        }
        try {
            GameRules gr = w.getGameRules();
            MinecraftServer s = w.getServer();
            // --- Spawn control ---
            gr.setValue(GameRules.DO_MOB_SPAWNING, false, s);
            gr.setValue(GameRules.SPAWN_MONSTERS, false, s);
            gr.setValue(GameRules.SPAWN_PATROLS, false, s);
            gr.setValue(GameRules.SPAWN_PHANTOMS, false, s);
            gr.setValue(GameRules.SPAWN_WANDERING_TRADERS, false, s);
            gr.setValue(GameRules.SPAWNER_BLOCKS_WORK, false, s);
            gr.setValue(GameRules.DISABLE_RAIDS, true, s);
            // --- Combat / arena rules ---
            gr.setValue(GameRules.DO_MOB_GRIEFING, false, s);
            gr.setValue(GameRules.DO_TILE_DROPS, false, s);
            // NOTE: KEEP_INVENTORY and DO_IMMEDIATE_RESPAWN are global (not
            // per-dimension) and are toggled by GameManager at BATTLE start/end
            // so they don't leak into the overworld during FARM phase.
            // --- Mob anger persistence ---
            // Mobs stay angry even after a player dies and respawns.
            gr.setValue(GameRules.FORGIVE_DEAD_PLAYERS, false, s);
            // All neutral mobs attack all players, not just the provoker.
            gr.setValue(GameRules.UNIVERSAL_ANGER, true, s);
        } catch (Throwable ignored) {
            // Older mappings: silently skip if a rule is missing.
        }
    }
}

