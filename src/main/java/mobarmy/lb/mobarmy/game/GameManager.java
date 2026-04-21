package mobarmy.lb.mobarmy.game;

import mobarmy.lb.mobarmy.MobarmyMod;
import mobarmy.lb.mobarmy.arena.Arena;
import mobarmy.lb.mobarmy.arena.ArenaBuildJob;
import mobarmy.lb.mobarmy.arena.ArenaDimension;
import mobarmy.lb.mobarmy.arena.ArenaProtection;
import mobarmy.lb.mobarmy.battle.BattleController;
import mobarmy.lb.mobarmy.team.Team;
import mobarmy.lb.mobarmy.ui.PhaseBossBar;
import mobarmy.lb.mobarmy.ui.WaveBuilderMenu;
import mobarmy.lb.mobarmy.ui.stats.GameStats;
import mobarmy.lb.mobarmy.ui.stats.StatsMenu;
import mobarmy.lb.mobarmy.util.PlayerUtils;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

public class GameManager {
    private final MobarmyMod mod;
    private GamePhase phase = GamePhase.LOBBY;
    private int phaseTicksRemaining = 0;
    private int phaseTicksTotal = 0;
    private BattleController battle;
    public final PhaseBossBar bossBar = new PhaseBossBar();

    /** One arena per team, built ONCE at game start, reused across all rounds. */
    private final List<Arena> teamArenas = new ArrayList<>();

    /** True if {@link #teamArenas} contains arenas that were already built
     *  (e.g. via the {@code /mobarmy prepare} command) and don't need to be
     *  rebuilt when the next game starts. Cleared on {@link #resetToLobby}. */
    private boolean arenasPrebuilt = false;

    /** Active chunked arena build job (null when idle). Tickled in {@link #tick}. */
    private ArenaBuildJob buildJob;
    private int lastReportedBuildPercent = -1;

    public GameManager(MobarmyMod mod) { this.mod = mod; }

    public GamePhase phase() { return phase; }
    public BattleController battle() { return battle; }
    public int phaseTicksRemaining() { return phaseTicksRemaining; }
    public boolean isPhase(GamePhase p) { return phase == p; }
    public List<Arena> teamArenas() { return teamArenas; }
    public boolean isBuilding() { return buildJob != null && !buildJob.isDone(); }
    public boolean arenasPrebuilt() { return arenasPrebuilt && !isBuilding(); }
    public int prebuiltArenaCount() { return arenasPrebuilt ? teamArenas.size() : 0; }

    /**
     * Pre-build {@code count} arenas in the arena dimension WITHOUT starting
     * the game phases. Players can keep playing in the lobby/overworld; once
     * the build job finishes they will be reused on the next {@link #startGame}.
     */
    public void prepareArenas(MinecraftServer server, int count) {
        if (phase != GamePhase.LOBBY && phase != GamePhase.END) {
            broadcast(server, Text.literal("Arenen können nur in der Lobby vorbereitet werden.").formatted(Formatting.RED));
            return;
        }
        if (isBuilding()) {
            broadcast(server, Text.literal("Bau läuft bereits — bitte warten oder /mobarmy stop.").formatted(Formatting.RED));
            return;
        }
        if (count < 1) {
            broadcast(server, Text.literal("Anzahl muss ≥ 1 sein.").formatted(Formatting.RED));
            return;
        }
        ArenaProtection.clear();
        teamArenas.clear();
        arenasPrebuilt = false;
        ServerWorld world = ArenaDimension.get(server);
        ArenaDimension.applyGameRules(world);
        for (int i = 0; i < count; i++) {
            net.minecraft.util.math.BlockPos offset =
                new net.minecraft.util.math.BlockPos(i * mod.config.arenaSpacing, 0, 0);
            teamArenas.add(new Arena(mod.config, offset));
        }
        broadcast(server, Text.literal("Bereite " + count + " Arenen vor…").formatted(Formatting.GRAY));
        long t0 = System.currentTimeMillis();
        buildJob = new ArenaBuildJob(world, new ArrayList<>(teamArenas), () -> {
            long ms = System.currentTimeMillis() - t0;
            arenasPrebuilt = true;
            broadcast(server, Text.literal("Arenen vorbereitet (" + count + ", Radius "
                + mod.config.arenaRadius + ", " + (ms / 1000) + " s). /mobarmy start kann jetzt sofort starten.")
                .formatted(Formatting.GREEN));
        });
        lastReportedBuildPercent = -1;
    }

    public void startGame(MinecraftServer server) {
        if (phase != GamePhase.LOBBY && phase != GamePhase.END) {
            broadcast(server, Text.literal("Spiel läuft bereits!").formatted(Formatting.RED));
            return;
        }
        if (mod.teams.size() < 2) {
            broadcast(server, Text.literal("Mindestens 2 Teams nötig!").formatted(Formatting.RED));
            return;
        }
        // Prevent racing with an in-flight /mobarmy prepare build (would
        // produce half-built arenas and a stale ArenaProtection map).
        if (isBuilding()) {
            broadcast(server, Text.literal("Arenen-Bau läuft noch — bitte warten oder /mobarmy stop.").formatted(Formatting.RED));
            return;
        }
        for (Team t : mod.teams.all()) t.resetForNewGame();
        WaveBuilderMenu.closeAll(mod);

        long seed = mod.config.randomizerSeed != 0 ? mod.config.randomizerSeed : new Random().nextLong();
        mod.randomizer.init(seed);
        mod.config.randomizerSeed = seed;
        mod.config.save(server);
        broadcast(server, Text.literal("Block-Randomizer aktiv (Seed " + seed + ")").formatted(Formatting.AQUA));

        // NOTE: do NOT clear ArenaProtection here — buildArenas() decides
        // whether to reuse pre-built arenas, and reusing requires the
        // existing protection map (otherwise cleanArenaInterior in round 2+
        // would treat structure blocks as unprotected and air them out,
        // destroying the arena mid-game). Protection is cleared inside
        // buildArenas() ONLY when we actually rebuild from scratch.
        buildArenas(server);

        startFarm(server);
    }

    /** Build one cylindrical arena per team at offset (i × arenaSpacing, 0, 0).
     *  Spread across many ticks via {@link ArenaBuildJob} so chunk generation
     *  doesn't blow the watchdog. The job is ticked from {@link #tick}.
     *  If arenas were already pre-built via {@link #prepareArenas} and there
     *  are enough of them, they are reused and no new build job is started. */
    private void buildArenas(MinecraftServer server) {
        var ordered = mod.teams.ordered();
        int needed = ordered.size();

        // Reuse pre-built arenas if we have enough.
        if (arenasPrebuilt && teamArenas.size() >= needed && !isBuilding()) {
            if (teamArenas.size() > needed) {
                teamArenas.subList(needed, teamArenas.size()).clear();
            }
            // Force-load arena chunks early so they're streamed to clients
            // well before the battle teleport happens.
            ServerWorld world = ArenaDimension.get(server);
            for (Arena a : teamArenas) a.forceLoadChunks(world);
            broadcast(server, Text.literal("Verwende vorbereitete Arenen (" + needed + ").").formatted(Formatting.GREEN));
            return;
        }

        teamArenas.clear();
        arenasPrebuilt = false;
        // Now wipe protection — we are committing to a full rebuild and the
        // new ArenaBuildJob will repopulate the map as it places structure
        // blocks via ArenaBuilder.set(). Doing this here (rather than in
        // startGame) keeps the map intact when reusing pre-built arenas.
        ArenaProtection.clear();
        ServerWorld world = ArenaDimension.get(server);
        ArenaDimension.applyGameRules(world);
        for (int i = 0; i < needed; i++) {
            net.minecraft.util.math.BlockPos offset =
                new net.minecraft.util.math.BlockPos(i * mod.config.arenaSpacing, 0, 0);
            teamArenas.add(new Arena(mod.config, offset));
        }
        if (!mod.config.buildArenaOnStart) {
            broadcast(server, Text.literal("Arenen-Bau deaktiviert (config).").formatted(Formatting.GRAY));
            return;
        }
        broadcast(server, Text.literal("Baue " + needed + " Arenen…").formatted(Formatting.GRAY));
        long t0 = System.currentTimeMillis();
        final List<Arena> arenasCopy = new ArrayList<>(teamArenas);
        buildJob = new ArenaBuildJob(world, arenasCopy, () -> {
            long ms = System.currentTimeMillis() - t0;
            // Force-load arena chunks immediately after build so they're ready
            // for client streaming well before the battle teleport.
            for (Arena a : arenasCopy) a.forceLoadChunks(world);
            broadcast(server, Text.literal("Arenen fertig (Radius " + mod.config.arenaRadius + ", " + (ms / 1000) + " s)")
                .formatted(Formatting.GREEN));
        });
        lastReportedBuildPercent = -1;
    }

    public void startFarm(MinecraftServer server) {
        phase = GamePhase.FARM;
        phaseTicksRemaining = mod.config.farmDurationSeconds * 20;
        phaseTicksTotal = phaseTicksRemaining;
        broadcast(server, Text.literal("=== FARM-PHASE === " + mod.config.farmDurationSeconds + "s").formatted(Formatting.GREEN));
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            Team t = mod.teams.get(p);
            if (t == null) continue;
            PlayerUtils.setMode(p, GameMode.SURVIVAL);
            PlayerUtils.heal(p);
            // Kein Teleport beim FARM-Start — Spieler bleiben wo sie sind.
            // Kein Spawnpunkt-/Gamerule-Zauber: in FARM gilt Vanilla, also
            // ganz normaler Death-Screen + Inventar-Verlust beim Sterben.
        }
    }

    public void startArrange(MinecraftServer server) {
        phase = GamePhase.ARRANGE;
        phaseTicksRemaining = mod.config.arrangeDurationSeconds * 20;
        phaseTicksTotal = phaseTicksRemaining;
        broadcast(server, Text.literal("=== ANORDNUNGS-PHASE === " + mod.config.arrangeDurationSeconds + "s").formatted(Formatting.YELLOW));
        for (Team t : mod.teams.all()) {
            while (t.waves.size() < mod.config.waveCount) t.waves.add(new ArrayList<>());
            for (UUID u : t.members) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(u);
                if (p != null) WaveBuilderMenu.open(p, mod, t);
            }
        }
    }

    public void startBattle(MinecraftServer server) {
        phase = GamePhase.BATTLE;
        for (Team t : mod.teams.all()) {
            while (t.waves.size() < mod.config.waveCount) t.waves.add(new ArrayList<>());
            for (UUID u : t.members) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(u);
                if (p != null) p.closeHandledScreen();
            }
        }
        WaveBuilderMenu.closeAll(mod);

        ServerWorld world = ArenaDimension.get(server);
        battle = new BattleController(mod, mod.teams.ordered(), teamArenas, world, mod.config);
        broadcast(server, Text.literal("=== BATTLE-PHASE ===").formatted(Formatting.RED, Formatting.BOLD));
        battle.start();
    }

    public void finishGame() {
        MinecraftServer server = mod.server;
        if (server == null) return;
        if (phase != GamePhase.BATTLE) return;
        phase = GamePhase.END;

        // Capture stats snapshot BEFORE resetting anything.
        GameStats gameStats = new GameStats(new ArrayList<>(mod.teams.all()), mod.randomizer.seed());
        this.lastGameStats = gameStats;

        // ===================== SAFE TELEPORT =====================
        Team winner = gameStats.winner == null ? null
            : mod.teams.all().stream().filter(t -> t.name.equals(gameStats.winner.name)).findFirst().orElse(null);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            // Prevent fall damage: set adventure mode + heal BEFORE teleport.
            PlayerUtils.setMode(p, GameMode.ADVENTURE);
            PlayerUtils.heal(p);
            p.setVelocity(0, 0, 0);
            p.velocityDirty = true;
            PlayerUtils.teleport(p, server.getOverworld(), mod.config.lobbyPos);
            if (winner != null) {
                PlayerUtils.title(p,
                    Text.literal("🏆 " + winner.name + " gewinnt! 🏆").formatted(winner.color, Formatting.BOLD),
                    Text.literal(winner.score + " Punkte"));
            }
        }

        // ===================== OPEN STATS UI =====================
        // Delay 3 seconds so the title animation plays, then open the UI.
        mod.scheduler.schedule(60, () -> {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                StatsMenu.open(p, gameStats);
            }
        });

        // Short chat summary (compact, UI has the details).
        broadcast(server, Text.empty());
        broadcast(server, Text.literal("══════════════════════════════════════").formatted(Formatting.GOLD));
        if (winner != null) {
            broadcast(server, Text.literal("  🏆 Gewinner: " + winner.name + " 🏆")
                .formatted(winner.color, Formatting.BOLD));
        }
        String[] medals = {"🥇", "🥈", "🥉"};
        List<GameStats.TeamStats> ranking = gameStats.ranking;
        for (int i = 0; i < ranking.size(); i++) {
            GameStats.TeamStats t = ranking.get(i);
            String medal = i < medals.length ? medals[i] : "  " + (i + 1) + ".";
            broadcast(server, Text.literal("  " + medal + " " + t.name + " — " + t.score + " Punkte")
                .formatted(t.color));
        }
        broadcast(server, Text.literal("══════════════════════════════════════").formatted(Formatting.GOLD));
        broadcast(server, Text.empty());

        // Seed zurücksetzen damit das nächste Spiel einen neuen bekommt.
        mod.config.randomizerSeed = 0;
        mod.config.save(server);
        battle = null;
    }

    /** Last game stats snapshot — players can re-open the stats UI via command. */
    private GameStats lastGameStats;
    public GameStats lastGameStats() { return lastGameStats; }

    public void resetToLobby(MinecraftServer server) {
        // Cancel any in-flight arena build and release its chunk tickets.
        if (buildJob != null) {
            buildJob.cancel();
            buildJob = null;
        }
        lastReportedBuildPercent = -1;

        // Release force-loaded arena chunks.
        ServerWorld arenaWorld = ArenaDimension.get(server);
        for (Arena a : teamArenas) {
            try { a.unforceLoadChunks(arenaWorld); } catch (Throwable ignored) {}
        }

        // Clean up any active battle: clear leftover wave mobs in every arena.
        if (battle != null) {
            for (var m : battle.matches()) {
                try { m.spawner.clear(arenaWorld); } catch (Throwable ignored) {}
            }
        }
        // Discard any non-player entities still floating in the arenas, and
        // (best-effort) sweep the protected blocks for stragglers.
        ServerWorld world = ArenaDimension.get(server);
        for (Arena a : teamArenas) {
            try {
                for (var e : world.getEntitiesByClass(net.minecraft.entity.Entity.class, a.box(),
                        e -> !(e instanceof net.minecraft.entity.player.PlayerEntity))) {
                    e.discard();
                }
            } catch (Throwable ignored) {}
        }

        phase = GamePhase.LOBBY;
        battle = null;
        mod.config.randomizerSeed = 0;
        mod.config.save(server);
        ArenaProtection.clear();
        teamArenas.clear();
        arenasPrebuilt = false;
        for (Team t : mod.teams.all()) t.resetForNewGame();
        WaveBuilderMenu.closeAll(mod);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            PlayerUtils.setMode(p, GameMode.ADVENTURE);
            PlayerUtils.heal(p);
            PlayerUtils.teleport(p, server.getOverworld(), mod.config.lobbyPos);
            p.getInventory().clear();
        }
        broadcast(server, Text.literal("Spiel zurückgesetzt.").formatted(Formatting.GRAY));
    }

    public void tick(MinecraftServer server) {
        // === Drive the chunked arena builder ===
        if (buildJob != null) {
            if (!buildJob.isDone()) {
                buildJob.tick();
                int pct = buildJob.progressPercent();
                if (pct / 10 != lastReportedBuildPercent / 10 && pct < 100) {
                    lastReportedBuildPercent = pct;
                    broadcast(server, Text.literal("Arenen-Bau: " + pct + "%").formatted(Formatting.DARK_AQUA));
                }
            } else {
                buildJob = null;
                lastReportedBuildPercent = 100;
            }
        }

        String extra = "";
        if (isBuilding()) {
            extra = "§eArenen werden gebaut: §f" + buildJob.progressPercent() + "%";
        } else if (phase == GamePhase.BATTLE && battle != null) {
            int activeMatches = battle.matches().size();
            int totalMobs = 0;
            for (var m : battle.matches()) totalMobs += m.spawner.aliveMobs.size();
            extra = "§7Runde §f" + (battle.round() + 1) + " §7| §f" + activeMatches
                + " §7Arenen aktiv §7| §cMobs insgesamt: §f" + totalMobs;
        }
        bossBar.update(server, phase, phaseTicksRemaining, phaseTicksTotal, extra);

        if (phase == GamePhase.LOBBY || phase == GamePhase.END) return;

        // Fast-forward: if all teams submitted in ARRANGE, jump to battle now
        // — but only if arenas are ready.
        if (phase == GamePhase.ARRANGE && WaveBuilderMenu.allTeamsSubmitted(mod) && !isBuilding()) {
            startBattle(server);
            return;
        }

        if (phaseTicksRemaining > 0) {
            phaseTicksRemaining--;
            if (phaseTicksRemaining % 20 == 0 && phaseTicksRemaining <= 200) {
                int s = phaseTicksRemaining / 20;
                if (s > 0) broadcast(server, Text.literal(phase.name() + " endet in " + s + "s").formatted(Formatting.GRAY));
            }
            if (phaseTicksRemaining == 0) {
                // Don't transition into BATTLE while arenas are still building.
                if (phase == GamePhase.ARRANGE && isBuilding()) {
                    // Hold here for a second and re-check.
                    phaseTicksRemaining = 20;
                    return;
                }
                switch (phase) {
                    case FARM -> startArrange(server);
                    case ARRANGE -> startBattle(server);
                    default -> {}
                }
                return;
            }
        }

        if (phase == GamePhase.BATTLE && battle != null) {
            battle.tick();
        }
    }

    public void onMobKill(LivingEntity mob, ServerPlayerEntity killer) {
        if (phase != GamePhase.FARM) {
            if (phase == GamePhase.BATTLE && battle != null) battle.onMobDeath(mob);
            return;
        }
        if (mob == null || killer == null) return;
        Team t = mod.teams.get(killer);
        if (t == null) return;
        EntityType<?> type = mob.getType();
        t.killedMobs.addTo(type, 1);
        if (mobarmy.lb.mobarmy.util.MobUtils.isBaby(mob)) {
            t.killedBabies.addTo(type, 1);
        }
        // Snapshot the killed mob's variant NBT so the wave can spawn an exact
        // copy later (preserves wolf coats, frog colours, cat skins, horse
        // markings, villager profession/biome, baby flag, etc.).
        net.minecraft.nbt.NbtCompound nbt = mobarmy.lb.mobarmy.util.MobUtils.snapshotVariantNbt(mob);
        if (nbt != null) {
            t.killedNbts.computeIfAbsent(type, k -> new ArrayList<>()).add(nbt);
        }
        t.score += 1;
    }

    public void onPlayerDeath(ServerPlayerEntity player) {
        // Veraltet: Spielertod wird nicht mehr abgefangen. Vanilla regelt
        // alles (Totem, keepInventory in Arena-Dim, Respawn am Team-Spawn,
        // der in BattleController.startRound gesetzt wird). Diese Methode
        // bleibt nur als API-Stub für externe Aufrufer (no-op).
    }

    private void broadcast(MinecraftServer server, Text t) {
        if (server != null) server.getPlayerManager().broadcast(t, false);
    }
}

