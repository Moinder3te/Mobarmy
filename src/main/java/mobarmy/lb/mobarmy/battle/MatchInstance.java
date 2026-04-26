package mobarmy.lb.mobarmy.battle;

import mobarmy.lb.mobarmy.MobarmyMod;
import mobarmy.lb.mobarmy.arena.Arena;
import mobarmy.lb.mobarmy.team.Team;
import mobarmy.lb.mobarmy.util.PlayerUtils;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One parallel match = one attacker team fighting in its own arena against the
 * defender's pre-built waves. Many {@link MatchInstance}s run at the same time
 * (one per team per round), orchestrated by {@link BattleController}.
 */
public class MatchInstance {
    public final Team attacker;
    public final Team defender;
    public final Arena arena;
    public final WaveSpawner spawner = new WaveSpawner();

    public int waveIndex = 0;
    public boolean waitingForWave = false;
    public boolean finished = false;
    /** True if the team cleared ALL waves, false if they wiped. */
    public boolean cleared = false;

    // =================== TIMING ===================
    /** Server tick when the match actually started (first wave spawned). */
    private long matchStartTick = -1;
    /** Server tick when the match ended (all waves cleared or wipe). */
    private long matchEndTick = -1;
    /** Server tick when the current wave started. */
    private long waveStartTick = -1;
    /** Per-wave clear times in ticks. Index = wave number. */
    public final List<Long> waveTimes = new ArrayList<>();
    /** Total match duration in ticks (set on finish). */
    public long totalTimeTicks = Long.MAX_VALUE; // MAX = wiped / DNF
    /** Deaths in this match. */
    public int deaths = 0;

    /** Countdown ticks until the first wave spawns.
     *  Needs to be long enough for the client to finish loading arena chunks
     *  after the dimension teleport (prevents "Timed out" kick). */
    private int startCountdown = 200; // 10 seconds
    /** Ticks until next wave after previous wave cleared. */
    private int nextWaveDelay = 0;
    /** Whether we've already broadcast the wipe / victory message. */
    private boolean ended = false;

    // =================== PAUSE (all offline) ===================
    /** True while every team member is offline — match is frozen. */
    private boolean paused = false;
    /** Accumulated ticks spent paused — subtracted from total time. */
    private long pausedTicks = 0;
    /** Pause ticks accumulated during the current wave only. */
    private long wavePausedTicks = 0;
    /** Server tick when the current pause started (-1 = not paused). */
    private long pauseStartTick = -1;

    /** Per-match boss bar visible only to this team's players. */
    private final ServerBossBar matchBar;

    public MatchInstance(Team attacker, Team defender, Arena arena) {
        this.attacker = attacker;
        this.defender = defender;
        this.arena = arena;
        this.matchBar = new ServerBossBar(
            Text.literal("⚔ Battle").formatted(Formatting.RED, Formatting.BOLD),
            BossBar.Color.RED, BossBar.Style.PROGRESS);
        this.matchBar.setVisible(true);
    }

    /** Called every server tick by the BattleController. */
    public void tick(ServerWorld world, MobarmyMod mod) {
        if (finished) return;

        // ===== PAUSE: all team members offline → freeze match =====
        boolean anyOnline = !onlineAttackers(world.getServer()).isEmpty();
        if (!anyOnline && !paused) {
            // Enter pause.
            paused = true;
            pauseStartTick = world.getServer().getTicks();
            matchBar.setName(Text.literal("⏸ Pausiert — Warte auf Spieler…")
                .formatted(Formatting.GRAY, Formatting.ITALIC));
            matchBar.setColor(BossBar.Color.WHITE);
            MobarmyMod.LOG.info("[Battle] {} vs {}: PAUSED (all offline)",
                attacker.name, defender.name);
            return;
        }
        if (paused) {
            if (!anyOnline) return; // still paused
            // Resume: player came back online.
            long now = world.getServer().getTicks();
            long thisPause = now - pauseStartTick;
            pausedTicks += thisPause;
            wavePausedTicks += thisPause;
            pauseStartTick = -1;
            paused = false;
            matchBar.setColor(BossBar.Color.RED);
            MobarmyMod.LOG.info("[Battle] {} vs {}: RESUMED (paused {} ticks)",
                attacker.name, defender.name, pausedTicks);
            // Re-teleport returning players to arena.
            for (ServerPlayerEntity p : onlineAttackers(world.getServer())) {
                if (!arena.contains(p.getX(), p.getY(), p.getZ())) {
                    mobarmy.lb.mobarmy.util.PlayerUtils.heal(p);
                    mobarmy.lb.mobarmy.util.PlayerUtils.setMode(p, GameMode.SURVIVAL);
                    mobarmy.lb.mobarmy.util.PlayerUtils.teleport(p, world, arena.spawnA);
                    MobTracker.removeAll(p);
                    p.getInventory().setStack(8, MobTracker.create());
                }
            }
        }

        // Update per-match boss bar: ensure team players are tracked and show wave info.
        updateMatchBar(world.getServer(), mod);

        // Update Mob Tracker compasses every 5 ticks.
        if (world.getServer().getTicks() % 5 == 0) {
            MobTracker.updateAll(this, world);
        }

        // Gestaffeltes Spawnen aus der Queue (max-alive-Limit, spawnsPerTick).
        spawner.tick(world, mod.config);

        // Dramatic pause before first wave.
        if (startCountdown > 0) {
            startCountdown--;
            if (startCountdown == 0) startWave(world, mod);
            return;
        }
        // Between-wave delay.
        if (nextWaveDelay > 0) {
            nextWaveDelay--;
            if (nextWaveDelay == 0) startWave(world, mod);
            return;
        }

        // Wipe check — attackers dead mid-wave.
        if (spawner.aliveMobs.size() > 0 && !anyAttackerAlive(world.getServer())) {
            if (!ended) {
                broadcastToTeam(world.getServer(), attacker,
                    Text.literal("Dein Team wurde in der Arena besiegt!").formatted(Formatting.RED, Formatting.BOLD));
                playSoundToTeam(world, SoundEvents.ENTITY_WITHER_DEATH, 0.5f, 1f);
                ended = true;
            }
            spawner.clear(world);
            finishMatch(world.getServer(), false);
            return;
        }

        // Wave cleared → time it + next wave.
        if (waitingForWave && spawner.isWaveCleared()) {
            waitingForWave = false;
            long now = world.getServer().getTicks();
            long waveDuration = waveStartTick > 0 ? (now - waveStartTick) - wavePausedTicks : 0;
            if (waveDuration < 0) waveDuration = 0;
            waveTimes.add(waveDuration);
            String timeStr = formatTicks(waveDuration);
            broadcastToTeam(world.getServer(), attacker,
                Text.literal("Welle " + (waveIndex + 1) + " geschafft! (" + timeStr + ")")
                    .formatted(Formatting.GREEN, Formatting.BOLD));
            playSoundToTeam(world, SoundEvents.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            waveIndex++;
            if (waveIndex >= mod.config.waveCount || waveIndex >= defender.waves.size()) {
                broadcastToTeam(world.getServer(), attacker,
                    Text.literal("Alle Wellen überlebt! 🏆").formatted(Formatting.GOLD, Formatting.BOLD));
                playSoundToTeam(world, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                finishMatch(world.getServer(), true);
                return;
            }
            nextWaveDelay = mod.config.waveDelayTicks;
        }
    }

    private void finishMatch(MinecraftServer srv, boolean allCleared) {
        this.cleared = allCleared;
        this.matchEndTick = srv.getTicks();
        if (matchStartTick > 0) {
            this.totalTimeTicks = (matchEndTick - matchStartTick) - pausedTicks;
            if (this.totalTimeTicks < 0) this.totalTimeTicks = 0;
        } else if (allCleared) {
            // All waves were empty / won by default → instant clear.
            this.totalTimeTicks = 0;
        }
        finished = true;
        // Remove boss bar and mob tracker from all players.
        matchBar.setVisible(false);
        matchBar.clearPlayers();
        for (ServerPlayerEntity p : onlineAttackers(srv)) {
            MobTracker.removeAll(p);
        }
    }

    public void onPlayerDeath() {
        if (finished) return;
        deaths++;
    }

    /** Force-cleanup boss bar (used by resetToLobby when match is aborted). */
    public void forceCleanup() {
        matchBar.setVisible(false);
        matchBar.clearPlayers();
    }

    private void startWave(ServerWorld world, MobarmyMod mod) {
        if (waveIndex >= defender.waves.size()) {
            // No waves to fight → attacker wins by default.
            finishMatch(world.getServer(), true);
            return;
        }
        List<EntityType<?>> mobs = defender.waves.get(waveIndex);
        if (mobs.isEmpty()) {
            MobarmyMod.LOG.warn("[Battle] {} vs {}: wave {} is empty — skipping",
                attacker.name, defender.name, waveIndex);
            waveTimes.add(0L); // empty wave = 0 time
            waveIndex++;
            if (waveIndex >= mod.config.waveCount || waveIndex >= defender.waves.size()) {
                finishMatch(world.getServer(), true);
                return;
            }
            nextWaveDelay = 1;
            return;
        }
        long now = world.getServer().getTicks();
        if (matchStartTick < 0) matchStartTick = now;
        waveStartTick = now;
        wavePausedTicks = 0;
        List<ServerPlayerEntity> targets = onlineAttackers(world.getServer());
        MobarmyMod.LOG.info("[Battle] {} vs {}: starting wave {} with {} mobs, {} targets",
            attacker.name, defender.name, waveIndex + 1, mobs.size(), targets.size());
        for (ServerPlayerEntity p : targets) {
            PlayerUtils.title(p,
                Text.literal("WELLE " + (waveIndex + 1)).formatted(Formatting.RED, Formatting.BOLD),
                Text.literal(mobs.size() + " Mobs nähern sich…").formatted(Formatting.YELLOW));
        }
        spawner.spawnWave(world, arena, mobs, targets, defender);
        waitingForWave = true;
    }

    public static String formatTicks(long ticks) {
        long totalSec = ticks / 20;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        long tenths = (ticks % 20) * 10 / 20;
        if (min > 0) return String.format("%d:%02d.%d", min, sec, tenths);
        return String.format("%d.%ds", sec, tenths);
    }

    /** Safety-net purge: the global ENTITY_LOAD blocker in MobarmyMod already
     *  prevents non-wave mobs from ever appearing in the arena dimension. This
     *  method only catches edge cases (e.g. mobs summoned via /summon, leftover
     *  entities from a previous round, dispenser-spawned entities). It will
     *  NEVER touch wave mobs because they all carry {@link WaveSpawner#WAVE_TAG},
     *  which is set BEFORE the entity is added to the world. */
    public void purgeIntruders(ServerWorld world) {
        Box box = arena.box();
        List<LivingEntity> intruders = world.getEntitiesByClass(LivingEntity.class, box, e ->
            !(e instanceof net.minecraft.entity.player.PlayerEntity)
            && !e.getCommandTags().contains(WaveSpawner.WAVE_TAG));
        for (LivingEntity e : intruders) e.discard();
    }

    public boolean anyAttackerAlive(MinecraftServer srv) {
        for (ServerPlayerEntity p : onlineAttackers(srv)) {
            // Spieler im Death-Screen (health=0, !isAlive) zählen weiterhin
            // als "im Match", weil sie via Vanilla-Respawn am Team-Spawnpoint
            // (siehe BattleController.startRound) wiederkommen können. Erst
            // wenn sie offline gehen oder freiwillig in Spectator schalten,
            // gilt die Seite als gewipt.
            if (!p.isSpectator()) return true;
        }
        return false;
    }

    public List<ServerPlayerEntity> onlineAttackers(MinecraftServer srv) {
        List<ServerPlayerEntity> list = new ArrayList<>();
        for (UUID u : attacker.members) {
            ServerPlayerEntity p = srv.getPlayerManager().getPlayer(u);
            if (p != null) list.add(p);
        }
        return list;
    }

    private void updateMatchBar(MinecraftServer srv, MobarmyMod mod) {
        // Sync player list: add new, remove disconnected.
        for (ServerPlayerEntity p : new ArrayList<>(matchBar.getPlayers())) {
            if (p.isDisconnected()) matchBar.removePlayer(p);
        }
        for (ServerPlayerEntity p : onlineAttackers(srv)) {
            if (!matchBar.getPlayers().contains(p)) matchBar.addPlayer(p);
        }
        int totalWaves = Math.min(mod.config.waveCount, defender.waves.size());
        int currentWave = Math.min(waveIndex + 1, totalWaves);
        matchBar.setName(Text.literal("⚔ Battle  ").formatted(Formatting.RED)
            .append(Text.literal("— ").formatted(Formatting.GRAY))
            .append(Text.literal("Welle " + currentWave + "/" + totalWaves).formatted(Formatting.WHITE)));
        matchBar.setPercent(totalWaves > 0 ? (float) waveIndex / totalWaves : 1f);
    }

    public boolean isAttacker(PlayerEntity p) { return attacker.members.contains(p.getUuid()); }

    private void broadcastToTeam(MinecraftServer srv, Team t, Text msg) {
        for (UUID u : t.members) {
            ServerPlayerEntity p = srv.getPlayerManager().getPlayer(u);
            if (p != null) p.sendMessage(msg, false);
        }
    }

    private void playSoundToTeam(ServerWorld world, net.minecraft.sound.SoundEvent sound, float volume, float pitch) {
        for (ServerPlayerEntity p : onlineAttackers(world.getServer())) {
            world.playSound(null, p.getBlockPos(), sound, SoundCategory.PLAYERS, volume, pitch);
        }
    }
}

