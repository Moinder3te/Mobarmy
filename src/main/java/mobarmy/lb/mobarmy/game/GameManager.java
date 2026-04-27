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
import net.minecraft.world.rule.GameRules;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
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
        for (Team t : mod.teams.all()) {
            if (t.members.isEmpty()) {
                broadcast(server, Text.literal("Team " + t.name + " hat keine Spieler! Entferne es oder füge Spieler hinzu.").formatted(Formatting.RED));
                return;
            }
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
        var randMode = mod.config.randomizerMode != null ? mod.config.randomizerMode
            : mobarmy.lb.mobarmy.config.RandomizerMode.GLOBAL;
        mod.randomizerManager.init(seed, randMode, mod.teams.all());
        mod.config.randomizerSeed = seed;
        mod.config.save(server);
        String modeLabel = randMode.displayName;
        broadcast(server, Text.literal("Block-Randomizer aktiv (Seed " + seed + ", Modus: " + modeLabel + ")").formatted(Formatting.AQUA));

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
            ServerWorld world = ArenaDimension.get(server);
            if (world.getRegistryKey() != ArenaDimension.WORLD_KEY) {
                broadcast(server, Text.literal("FEHLER: Arena-Dimension nicht geladen! Ist das Datapack installiert?").formatted(Formatting.RED));
                phase = GamePhase.LOBBY;
                return;
            }
            // Force-load arena chunks early so they're streamed to clients
            // well before the battle teleport happens.
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
        if (world.getRegistryKey() != ArenaDimension.WORLD_KEY) {
            broadcast(server, Text.literal("FEHLER: Arena-Dimension nicht geladen! Ist das Datapack installiert?").formatted(Formatting.RED));
            phase = GamePhase.LOBBY;
            return;
        }
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
        saveBackpacks(server);
        phase = GamePhase.ARRANGE;
        phaseTicksRemaining = mod.config.arrangeDurationSeconds * 20;
        phaseTicksTotal = phaseTicksRemaining;
        broadcast(server, Text.literal("=== ANORDNUNGS-PHASE === " + mod.config.arrangeDurationSeconds + "s").formatted(Formatting.YELLOW));
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            PlayerUtils.title(p,
                Text.literal("FARM VORBEI").formatted(Formatting.YELLOW, Formatting.BOLD),
                Text.literal("Ordne deine Wellen an!").formatted(Formatting.GRAY));
            server.getOverworld().playSound(null, p.getBlockPos(),
                net.minecraft.sound.SoundEvents.ENTITY_ENDER_DRAGON_GROWL,
                net.minecraft.sound.SoundCategory.MASTER, 0.6f, 1.2f);
        }
        for (Team t : mod.teams.all()) {
            while (t.waves.size() < mod.config.waveCount) t.waves.add(new ArrayList<>());
            for (UUID u : t.members) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(u);
                if (p != null) WaveBuilderMenu.open(p, mod, t);
            }
        }
    }

    public void startBattle(MinecraftServer server) {
        saveBackpacks(server);
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

        // Enable keepInventory + instant respawn for BATTLE (global gamerules).
        setBattleGamerules(server, true);

        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            PlayerUtils.title(p,
                Text.literal("⚔ BATTLE ⚔").formatted(Formatting.RED, Formatting.BOLD),
                Text.literal("Kämpfe in der Arena!").formatted(Formatting.YELLOW));
            server.getOverworld().playSound(null, p.getBlockPos(),
                net.minecraft.sound.SoundEvents.ENTITY_WITHER_SPAWN,
                net.minecraft.sound.SoundCategory.MASTER, 0.5f, 1f);
        }
        battle.start();
    }

    public void finishGame() {
        MinecraftServer server = mod.server;
        if (server == null) return;
        if (phase != GamePhase.BATTLE) return;
        phase = GamePhase.END;
        deleteBackpackFiles(server);

        // Disable keepInventory + instant respawn now that BATTLE is over.
        setBattleGamerules(server, false);

        // Capture stats snapshot BEFORE resetting anything.
        List<mobarmy.lb.mobarmy.battle.MatchResult> allResults =
            battle != null ? battle.allResults() : List.of();
        GameStats gameStats = new GameStats(new ArrayList<>(mod.teams.all()), allResults, mod.randomizerManager.baseSeed());
        this.lastGameStats = gameStats;

        // ===================== SAFE TELEPORT =====================
        GameStats.TeamStats winnerStats = gameStats.winner;
        Team winner = winnerStats == null ? null
            : mod.teams.all().stream().filter(t -> t.name.equals(winnerStats.name)).findFirst().orElse(null);
        String winnerTime = winnerStats != null && winnerStats.totalTimeTicks < Long.MAX_VALUE
            ? mobarmy.lb.mobarmy.battle.MatchInstance.formatTicks(winnerStats.totalTimeTicks)
            : "DNF";
        PlayerUtils.ensurePlatform(server.getOverworld(), mod.config.lobbyPos);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            PlayerUtils.setMode(p, GameMode.ADVENTURE);
            PlayerUtils.heal(p);
            p.setVelocity(0, 0, 0);
            p.velocityDirty = true;
            PlayerUtils.teleport(p, server.getOverworld(), mod.config.lobbyPos);
            // Victory sound for everyone.
            server.getOverworld().playSound(null, p.getBlockPos(),
                net.minecraft.sound.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
                net.minecraft.sound.SoundCategory.MASTER, 1f, 1f);
            if (winner != null) {
                PlayerUtils.title(p,
                    Text.literal("🏆 " + winner.name + " gewinnt! 🏆").formatted(winner.color, Formatting.BOLD),
                    Text.literal("Schnellste Zeit: " + winnerTime));
            }
        }

        // ===================== OPEN STATS UI =====================
        mod.scheduler.schedule(60, () -> {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                StatsMenu.open(p, gameStats);
            }
        });

        // Short chat summary.
        broadcast(server, Text.empty());
        broadcast(server, Text.literal("══════════════════════════════════════").formatted(Formatting.GOLD));
        if (winner != null) {
            broadcast(server, Text.literal("  🏆 Gewinner: " + winner.name + " (" + winnerTime + ") 🏆")
                .formatted(winner.color, Formatting.BOLD));
        }
        String[] medals = {"🥇", "🥈", "🥉"};
        List<GameStats.TeamStats> ranking = gameStats.ranking;
        for (int i = 0; i < ranking.size(); i++) {
            GameStats.TeamStats t = ranking.get(i);
            String medal = i < medals.length ? medals[i] : "  " + (i + 1) + ".";
            String time = t.totalTimeTicks < Long.MAX_VALUE
                ? mobarmy.lb.mobarmy.battle.MatchInstance.formatTicks(t.totalTimeTicks)
                : "DNF";
            broadcast(server, Text.literal("  " + medal + " " + t.name + " — " + time)
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
                try { m.forceCleanup(); } catch (Throwable ignored) {}
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
        bossBar.clear();

        // Ensure battle gamerules are off (in case resetToLobby is called mid-battle).
        setBattleGamerules(server, false);

        mod.config.randomizerSeed = 0;
        mod.config.save(server);
        ArenaProtection.clear();
        mod.randomizerManager.clear();
        mod.scheduler.clear();
        deleteBackpackFiles(server);
        teamArenas.clear();
        arenasPrebuilt = false;
        for (Team t : mod.teams.all()) t.resetForNewGame();
        WaveBuilderMenu.closeAll(mod);
        PlayerUtils.ensurePlatform(server.getOverworld(), mod.config.lobbyPos);
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
            extra = "Arenen werden gebaut: " + buildJob.progressPercent() + "%";
        } else if (phase == GamePhase.BATTLE && battle != null) {
            // Global boss bar is hidden during BATTLE (per-match bars active).
            // No extra needed.
        }
        bossBar.update(server, phase, phaseTicksRemaining, phaseTicksTotal, extra);

        if (phase == GamePhase.LOBBY || phase == GamePhase.END) return;

        // Auto-save backpacks every 60 seconds during FARM phase.
        if (phase == GamePhase.FARM && server.getTicks() % 1200 == 0) {
            saveBackpacks(server);
        }

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
                if (s > 0) {
                    broadcast(server, Text.literal(phase.name() + " endet in " + s + "s").formatted(Formatting.GRAY));
                    // Sound + Title for final countdown
                    if (s <= 5) {
                        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                            server.getOverworld().playSound(null, p.getBlockPos(),
                                net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(),
                                net.minecraft.sound.SoundCategory.MASTER, 0.8f, s == 1 ? 1.5f : 1f);
                            if (s <= 3) {
                                PlayerUtils.title(p,
                                    Text.literal(String.valueOf(s)).formatted(Formatting.RED, Formatting.BOLD),
                                    Text.literal(phase.name() + " endet gleich!").formatted(Formatting.GRAY));
                            }
                        }
                    }
                }
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
            // Strip instance-specific fields so only variant data remains for hashing.
            net.minecraft.nbt.NbtCompound forHash = nbt.copy();
            forHash.remove("UUID"); forHash.remove("Pos"); forHash.remove("Motion");
            forHash.remove("Rotation"); forHash.remove("FallDistance"); forHash.remove("Fire");
            forHash.remove("Air"); forHash.remove("OnGround"); forHash.remove("PortalCooldown");
            forHash.remove("TicksFrozen"); forHash.remove("Health"); forHash.remove("HurtTime");
            forHash.remove("HurtByTimestamp"); forHash.remove("DeathTime"); forHash.remove("AbsorptionAmount");
            forHash.remove("Brain"); forHash.remove("Leash"); forHash.remove("SleepingX");
            forHash.remove("SleepingY"); forHash.remove("SleepingZ");
            int hash = forHash.toString().hashCode();
            Set<Integer> seen = t.nbtHashes.computeIfAbsent(type, k -> new java.util.HashSet<>());
            if (seen.add(hash)) {
                t.killedNbts.computeIfAbsent(type, k -> new ArrayList<>()).add(nbt);
            }
        }
    }

    private void broadcast(MinecraftServer server, Text t) {
        if (server != null) server.getPlayerManager().broadcast(t, false);
    }

    // ===================== BACKPACK PERSISTENCE =====================

    private static Path backpackDir(MinecraftServer server) {
        return server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("mobarmy_backpacks");
    }

    /** Save all team backpacks to disk. */
    public void saveBackpacks(MinecraftServer server) {
        for (Team t : mod.teams.all()) {
            try {
                t.backpack.save(backpackDir(server).resolve(t.name + ".dat"),
                    server.getRegistryManager());
            } catch (Exception e) {
                MobarmyMod.LOG.error("Failed to save backpack for team {}", t.name, e);
            }
        }
    }

    /** Load all team backpacks from disk. */
    public void loadBackpacks(MinecraftServer server) {
        for (Team t : mod.teams.all()) {
            try {
                t.backpack.load(backpackDir(server).resolve(t.name + ".dat"),
                    server.getRegistryManager());
            } catch (Exception e) {
                MobarmyMod.LOG.error("Failed to load backpack for team {}", t.name, e);
            }
        }
    }

    /** Toggle keepInventory + instant respawn (global gamerules). */
    private void setBattleGamerules(MinecraftServer server, boolean on) {
        GameRules gr = server.getOverworld().getGameRules();
        gr.setValue(GameRules.KEEP_INVENTORY, on, server);
        gr.setValue(GameRules.DO_IMMEDIATE_RESPAWN, on, server);
    }

    /** Delete all backpack files from disk. */
    public void deleteBackpackFiles(MinecraftServer server) {
        Path dir = backpackDir(server);
        try {
            if (Files.exists(dir)) {
                try (var stream = Files.list(dir)) {
                    stream.forEach(f -> { try { Files.deleteIfExists(f); } catch (Exception ignored) {} });
                }
                Files.deleteIfExists(dir);
            }
        } catch (Exception e) {
            MobarmyMod.LOG.error("Failed to delete backpack files", e);
        }
    }
}

