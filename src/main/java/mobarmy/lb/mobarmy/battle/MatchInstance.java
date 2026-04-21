package mobarmy.lb.mobarmy.battle;

import mobarmy.lb.mobarmy.MobarmyMod;
import mobarmy.lb.mobarmy.arena.Arena;
import mobarmy.lb.mobarmy.team.Team;
import mobarmy.lb.mobarmy.util.PlayerUtils;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
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

    /** Countdown ticks until the first wave spawns.
     *  Needs to be long enough for the client to finish loading arena chunks
     *  after the dimension teleport (prevents "Timed out" kick). */
    private int startCountdown = 200; // 10 seconds
    /** Ticks until next wave after previous wave cleared. */
    private int nextWaveDelay = 0;
    /** Whether we've already broadcast the wipe / victory message. */
    private boolean ended = false;

    public MatchInstance(Team attacker, Team defender, Arena arena) {
        this.attacker = attacker;
        this.defender = defender;
        this.arena = arena;
    }

    /** Called every server tick by the BattleController. */
    public void tick(ServerWorld world, MobarmyMod mod) {
        if (finished) return;

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
                ended = true;
            }
            spawner.clear(world);
            finished = true;
            return;
        }

        // Wave cleared → score + next wave.
        if (waitingForWave && spawner.isWaveCleared()) {
            waitingForWave = false;
            attacker.score += 10;
            broadcastToTeam(world.getServer(), attacker,
                Text.literal("Welle " + (waveIndex + 1) + " geschafft! +10").formatted(Formatting.GREEN, Formatting.BOLD));
            waveIndex++;
            if (waveIndex >= mod.config.waveCount || waveIndex >= defender.waves.size()) {
                broadcastToTeam(world.getServer(), attacker,
                    Text.literal("Alle Wellen überlebt! 🏆").formatted(Formatting.GOLD, Formatting.BOLD));
                finished = true;
                return;
            }
            nextWaveDelay = mod.config.waveDelayTicks;
        }
    }

    private void startWave(ServerWorld world, MobarmyMod mod) {
        if (waveIndex >= defender.waves.size()) {
            MobarmyMod.LOG.warn("[Battle] {} vs {}: waveIndex {} >= waves.size {} — finishing match",
                attacker.name, defender.name, waveIndex, defender.waves.size());
            finished = true;
            return;
        }
        List<EntityType<?>> mobs = defender.waves.get(waveIndex);
        if (mobs.isEmpty()) {
            MobarmyMod.LOG.warn("[Battle] {} vs {}: wave {} is empty — skipping",
                attacker.name, defender.name, waveIndex);
            waveIndex++;
            if (waveIndex >= mod.config.waveCount || waveIndex >= defender.waves.size()) {
                finished = true;
                return;
            }
            nextWaveDelay = 1; // retry next wave on next tick
            return;
        }
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

    public boolean isAttacker(PlayerEntity p) { return attacker.members.contains(p.getUuid()); }

    private void broadcastToTeam(MinecraftServer srv, Team t, Text msg) {
        for (UUID u : t.members) {
            ServerPlayerEntity p = srv.getPlayerManager().getPlayer(u);
            if (p != null) p.sendMessage(msg, false);
        }
    }
}

