package mobarmy.lb.mobarmy.battle;

import mobarmy.lb.mobarmy.MobarmyMod;
import mobarmy.lb.mobarmy.arena.Arena;
import mobarmy.lb.mobarmy.arena.ArenaProtection;
import mobarmy.lb.mobarmy.config.MobarmyConfig;
import mobarmy.lb.mobarmy.team.Team;
import mobarmy.lb.mobarmy.util.InventorySnapshot;
import mobarmy.lb.mobarmy.util.PlayerUtils;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrator for parallel-match battles.
 *
 *  - Arenas are built ONCE per game, at start(). One per team, at offset
 *    (teamIndex × arenaSpacing, 0, 0). They remain standing between rounds.
 *  - Round transition only wipes the interior (dropped items, straggler mobs,
 *    player-placed blocks). The structure itself stays — much cheaper than a
 *    full rebuild.
 *  - Each round: team i attacks team (i+r+1) % N in ITS OWN arena. All matches
 *    run in parallel. Round advances only when every match has finished.
 *  - After N−1 rounds every team has attacked every other team once.
 */
public class BattleController {
    private final MobarmyMod mod;
    private final List<Team> teams;
    private final ServerWorld world;
    private final MobarmyConfig cfg;
    private final InventorySnapshot inventorySnapshot = new InventorySnapshot();

    /** teamArenas.get(i) = fixed arena for teams.get(i). Built externally and
     *  injected here by GameManager — we never construct or rebuild them. */
    private final List<Arena> teamArenas;

    private int round = 0;
    private final List<MatchInstance> currentMatches = new ArrayList<>();
    private boolean waitingForNextRound = false;

    /** Teams whose match is finished this round — they spectate other teams. */
    private final List<Team> spectatingTeams = new ArrayList<>();
    /** The match index (into currentMatches) that spectators are watching. -1 = none. */
    private int spectatingMatchIdx = -1;

    /** All match results across all rounds — used by GameStats at game end. */
    private final List<MatchResult> allResults = new ArrayList<>();

    public List<MatchResult> allResults() { return List.copyOf(allResults); }

    public BattleController(MobarmyMod mod, List<Team> teams, List<Arena> teamArenas,
                            ServerWorld world, MobarmyConfig cfg) {
        this.mod = mod;
        this.teams = teams;
        this.teamArenas = teamArenas;
        this.world = world;
        this.cfg = cfg;
    }

    public int round() { return round; }
    public List<MatchInstance> matches() { return List.copyOf(currentMatches); }

    public boolean finished() {
        return round >= teams.size() - 1 && currentMatches.isEmpty() && !waitingForNextRound;
    }

    public void start() {
        round = 0;
        currentMatches.clear();

        // Snapshot every player's farm-phase inventory once.
        for (Team t : teams) {
            for (UUID u : t.members) {
                ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(u);
                if (p != null) inventorySnapshot.snapshot(p);
            }
        }
        // Defensive: if for some reason arenas weren't built, abort cleanly.
        if (teamArenas.size() < teams.size()) {
            broadcast(Text.literal("[Mobarmy] FEHLER: Arenen nicht bereit, Battle abgebrochen.")
                .formatted(Formatting.RED));
            mod.scheduler.schedule(20, mod.gameManager::finishGame);
            return;
        }

        // Chunks are already force-loaded from arena build/prepare phase.
        // No need to force-load here — they've been streaming to clients
        // throughout FARM and ARRANGE phases.

        startRound(0);
    }

    private void startRound(int r) {
        round = r;
        currentMatches.clear();
        waitingForNextRound = false;
        spectatingTeams.clear();
        spectatingMatchIdx = -1;
        int N = teams.size();
        if (N < 2) { finish(); return; }

        broadcast(Text.literal("=== RUNDE " + (r + 1) + " / " + (N - 1) + " ===")
            .formatted(Formatting.GOLD, Formatting.BOLD));

        for (int i = 0; i < N; i++) {
            Team att = teams.get(i);
            Team def = teams.get((i + r + 1) % N);
            if (att == def) continue;

            Arena arena = teamArenas.get(i);
            // Only clear interior for rounds > 0 (first round uses a freshly-built arena).
            if (r > 0) cleanArenaInterior(arena);

            MatchInstance m = new MatchInstance(att, def, arena);
            currentMatches.add(m);

            for (UUID u : att.members) {
                ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(u);
                if (p == null) continue;
                PlayerUtils.setMode(p, GameMode.SURVIVAL);
                PlayerUtils.heal(p);
                inventorySnapshot.restore(p);
                // Give the Mob Tracker compass.
                p.getInventory().setStack(8, MobTracker.create());
                PlayerUtils.teleport(p, world, arena.spawnA);
                // Pro-Team Respawn-Punkt: stirbt der Spieler in der Arena,
                // bringt ihn der Vanilla-Respawn (sofort, mit keepInventory +
                // Totem-Support) auf den CACHE-Würfel über seiner Team-Arena.
                // Dort wartet er 30 s in Survival mit Inventar; danach
                // teleportiert ihn der scheduler in MobarmyMod zurück auf
                // das Spawn-Pad in der Arena (siehe AFTER_DEATH-Handler).
                BlockPos cachePad = new BlockPos(
                    (int) Math.floor(arena.spectator.x),
                    (int) Math.floor(arena.spectator.y),
                    (int) Math.floor(arena.spectator.z));
                net.minecraft.world.WorldProperties.SpawnPoint sp =
                    net.minecraft.world.WorldProperties.SpawnPoint.create(
                        world.getRegistryKey(), cachePad, 0f, 0f);
                p.setSpawnPoint(new ServerPlayerEntity.Respawn(sp, true), false);
            }
            broadcastToTeam(att, Text.literal("Deine Arena: du gegen " + def.name + "'s Wellen")
                .formatted(Formatting.GOLD));
        }
    }

    /**
     * Lightweight arena reset between rounds: removes all non-player entities
     * inside the arena (items, stray mobs, arrows, XP orbs). Block cleanup is
     * skipped — with doTileDrops=false and keepInventory=true, player-placed
     * blocks are harmless and iterating ~3.5M positions would crash the server.
     */
    private void cleanArenaInterior(Arena arena) {
        Box box = arena.box();
        List<Entity> toDiscard = new ArrayList<>();
        for (Entity e : world.getEntitiesByClass(Entity.class, box, e -> !(e instanceof PlayerEntity))) {
            toDiscard.add(e);
        }
        for (Entity e : toDiscard) e.discard();
    }

    public void tick() {
        if (waitingForNextRound) return;
        boolean allFinished = !currentMatches.isEmpty();
        List<MatchInstance> justFinished = new ArrayList<>();
        for (MatchInstance m : currentMatches) {
            boolean wasDone = m.finished;
            m.tick(world, mod);
            if (!m.finished) allFinished = false;
            if (m.finished && !wasDone) justFinished.add(m);
        }
        if (world.getServer().getTicks() % 20 == 0) {
            for (MatchInstance m : currentMatches) m.purgeIntruders(world);
            // === CRITICAL: Purge stale mob UUIDs every second ===
            // If a mob dies without triggering AFTER_DEATH (void, discard,
            // chunk unload, despawn), its UUID stays in aliveMobs forever
            // and the wave is NEVER considered cleared. Fix: remove any UUID
            // where the entity no longer exists or is dead.
            for (MatchInstance m : currentMatches) {
                m.spawner.aliveMobs.removeIf(uuid -> {
                    net.minecraft.entity.Entity e = world.getEntity(uuid);
                    return e == null || !e.isAlive() || e.isRemoved();
                });
            }
            // === Retarget mobs every second (not every 5s) ===
            // Mobs lose their target when a player dies and respawns, or when
            // they wander too far. Frequent retargeting keeps them aggressive.
            for (MatchInstance m : currentMatches) {
                if (m.finished) continue;
                // Only target alive players inside the arena — not dead/respawning ones.
                List<ServerPlayerEntity> validTargets = new ArrayList<>();
                for (ServerPlayerEntity p : m.onlineAttackers(world.getServer())) {
                    if (p.isAlive() && !p.isSpectator() && m.arena.contains(p.getX(), p.getY(), p.getZ())) {
                        validTargets.add(p);
                    }
                }
                if (validTargets.isEmpty()) continue;
                for (UUID mobId : m.spawner.aliveMobs) {
                    net.minecraft.entity.Entity e = world.getEntity(mobId);
                    if (e == null) continue;
                    ServerPlayerEntity target = validTargets.get(
                        world.getRandom().nextInt(validTargets.size()));
                    // Re-target mobs that lost their target.
                    if (e instanceof net.minecraft.entity.mob.MobEntity mob) {
                        if (mob.getTarget() == null || !mob.getTarget().isAlive()) {
                            mob.setTarget(target);
                        }
                    }
                    // Refresh anger on neutral mobs.
                    if (e instanceof net.minecraft.entity.mob.Angerable angerable) {
                        if (!angerable.hasAngerTime()) {
                            angerable.universallyAnger();
                            angerable.setTarget(target);
                        }
                    }
                }
            }
        }
        // Re-apply Night Vision every 5s and keep Wardens from burrowing.
        if (world.getServer().getTicks() % 100 == 0) {
            for (MatchInstance m : currentMatches) {
                for (UUID u : m.attacker.members) {
                    ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(u);
                    if (p == null) continue;
                    p.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                        net.minecraft.entity.effect.StatusEffects.NIGHT_VISION,
                        260, 0, true, false, true));
                }
                // Keep Wardens angry so they never burrow.
                for (UUID mobId : m.spawner.aliveMobs) {
                    net.minecraft.entity.Entity e = world.getEntity(mobId);
                    if (e instanceof net.minecraft.entity.mob.WardenEntity warden) {
                        List<ServerPlayerEntity> attackers = m.onlineAttackers(world.getServer());
                        if (!attackers.isEmpty()) {
                            warden.increaseAngerAt(attackers.get(0), 150, false);
                        }
                    }
                }
            }
        }

        // Handle newly finished matches: put their team into spectator mode.
        for (MatchInstance m : justFinished) {
            moveTeamToSpectator(m.attacker);
        }

        // If the match spectators are watching just finished, cascade to next.
        if (spectatingMatchIdx >= 0 && spectatingMatchIdx < currentMatches.size()
                && currentMatches.get(spectatingMatchIdx).finished) {
            redirectSpectatorsToNextActive();
        }

        if (allFinished) {
            // Capture results before clearing matches.
            for (MatchInstance m : currentMatches) {
                allResults.add(MatchResult.from(m, round));
            }
            for (MatchInstance m : currentMatches) m.spawner.clear(world);
            waitingForNextRound = true;
            spectatingTeams.clear();
            spectatingMatchIdx = -1;
            int next = round + 1;
            if (next >= teams.size() - 1) {
                currentMatches.clear();
                finish();
                return;
            }
            broadcast(Text.literal("Runde " + (round + 1) + " beendet. Nächste Runde in 5s…")
                .formatted(Formatting.AQUA));
            mod.scheduler.schedule(cfg.waveDelayTicks * 2, () -> {
                currentMatches.clear();
                startRound(next);
            });
        }
    }

    /** Move a finished team into spectator mode and teleport them to an active match. */
    private void moveTeamToSpectator(Team team) {
        spectatingTeams.add(team);
        MatchInstance active = findFirstActiveMatch();
        if (active == null) return; // all done, finishGame will handle it
        spectatingMatchIdx = currentMatches.indexOf(active);
        teleportSpectatorsToMatch(active);
    }

    /** When the currently watched match finishes, find the next active match
     *  and redirect ALL spectating teams there. */
    private void redirectSpectatorsToNextActive() {
        MatchInstance next = findFirstActiveMatch();
        if (next == null) {
            spectatingMatchIdx = -1;
            return; // all done
        }
        spectatingMatchIdx = currentMatches.indexOf(next);
        teleportSpectatorsToMatch(next);
    }

    /** Teleport all spectating teams to the given match's arena. */
    private void teleportSpectatorsToMatch(MatchInstance match) {
        for (Team t : spectatingTeams) {
            broadcastToTeam(t, Text.literal("⚔ Zuschauer: " + match.attacker.name + " vs "
                + match.defender.name + "'s Wellen").formatted(Formatting.LIGHT_PURPLE));
            for (UUID u : t.members) {
                ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(u);
                if (p == null) continue;
                PlayerUtils.setMode(p, GameMode.SPECTATOR);
                PlayerUtils.teleport(p, world, match.arena.spectator);
            }
        }
    }

    /** Find the first match in the list that's still active. */
    private MatchInstance findFirstActiveMatch() {
        for (MatchInstance m : currentMatches) {
            if (!m.finished) return m;
        }
        return null;
    }

    private void finish() {
        // Release force-loaded chunks.
        for (Arena a : teamArenas) a.unforceLoadChunks(world);
        mod.scheduler.schedule(40, mod.gameManager::finishGame);
    }

    // ---------- lookup helpers ----------

    public MatchInstance findMatchForAttacker(PlayerEntity p) {
        for (MatchInstance m : currentMatches) if (m.isAttacker(p)) return m;
        return null;
    }

    public MatchInstance findMatchForMob(LivingEntity mob) {
        for (MatchInstance m : currentMatches) if (m.spawner.aliveMobs.contains(mob.getUuid())) return m;
        for (MatchInstance m : currentMatches) if (m.arena.contains(mob.getX(), mob.getY(), mob.getZ())) return m;
        return null;
    }

    public boolean isAttackerInArena(PlayerEntity p) { return findMatchForAttacker(p) != null; }

    public void onMobDeath(LivingEntity mob) {
        MatchInstance m = findMatchForMob(mob);
        if (m != null) m.spawner.onMobDeath(mob);
    }

    // ---------- util ----------
    private void broadcast(Text t) {
        world.getServer().getPlayerManager().broadcast(t, false);
    }
    private void broadcastToTeam(Team t, Text msg) {
        for (UUID u : t.members) {
            ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(u);
            if (p != null) p.sendMessage(msg, false);
        }
    }
}

