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
     * Lock the arena dimension to permanent noon and disable weather/mob
     * spawning so the arenas stay visually bright and free of intruders.
     * Safe to call multiple times — gamerules are idempotent.
     *
     * SAFETY: Falls die Arena-Dimension nicht geladen ist (Datapack fehlt o.ä.),
     * wird {@link #get(MinecraftServer)} auf die Overworld zurückfallen. Damit
     * wir nicht versehentlich Spielregeln wie KEEP_INVENTORY oder
     * DO_IMMEDIATE_RESPAWN auf die Lobby/Overworld anwenden, machen wir hier
     * eine harte Identitäts-Prüfung und brechen ab wenn das übergebene World
     * NICHT die echte Arena-Dimension ist.
     */
    public static void applyGameRules(ServerWorld w) {
        if (w == null) return;
        if (w.getRegistryKey() != WORLD_KEY) {
            // Niemals Gamerules in der Overworld/Lobby ändern.
            return;
        }
        try {
            // Permanent night — undead mobs don't burn.
            w.setTimeOfDay(18000L); // midnight
            GameRules gr = w.getGameRules();
            MinecraftServer s = w.getServer();
            gr.setValue(GameRules.ADVANCE_TIME, false, s);
            gr.setValue(GameRules.ADVANCE_WEATHER, false, s);
            gr.setValue(GameRules.DO_MOB_SPAWNING, false, s);
            gr.setValue(GameRules.SPAWN_MONSTERS, false, s);
            gr.setValue(GameRules.SPAWN_PATROLS, false, s);
            gr.setValue(GameRules.SPAWN_PHANTOMS, false, s);
            gr.setValue(GameRules.SPAWN_WANDERING_TRADERS, false, s);
            gr.setValue(GameRules.DO_MOB_GRIEFING, false, s);
            gr.setValue(GameRules.DO_TILE_DROPS, false, s);
            gr.setValue(GameRules.SPAWNER_BLOCKS_WORK, false, s);
            gr.setValue(GameRules.DISABLE_RAIDS, true, s);
            // Spieler dürfen sterben ohne Inventar zu verlieren — der
            // Respawnpunkt wird per BattleController auf den Team-Spawn
            // gesetzt, sodass Vanilla-Death + Totem reibungslos funktionieren.
            gr.setValue(GameRules.KEEP_INVENTORY, true, s);
            // Sofort-Respawn: kein Death-Screen, Spieler poppt direkt am
            // gesetzten Team-Spawnpunkt wieder auf.
            gr.setValue(GameRules.DO_IMMEDIATE_RESPAWN, true, s);
            // Clear any leftover weather state.
            w.setWeather(0, 0, false, false);
        } catch (Throwable ignored) {
            // Older mappings: silently skip if a rule is missing.
        }
    }
}

