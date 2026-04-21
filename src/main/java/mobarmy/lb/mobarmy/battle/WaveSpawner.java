package mobarmy.lb.mobarmy.battle;

import mobarmy.lb.mobarmy.MobarmyMod;
import mobarmy.lb.mobarmy.arena.Arena;
import mobarmy.lb.mobarmy.config.MobarmyConfig;
import mobarmy.lb.mobarmy.team.Team;
import mobarmy.lb.mobarmy.util.MobUtils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class WaveSpawner {
    /** Tag attached to every wave mob — used by the global spawn-blocker
     *  ({@code MobarmyMod.ENTITY_LOAD} hook) and by {@code purgeIntruders}
     *  to distinguish "ours" from "natural" spawns. MUST be set BEFORE the
     *  entity is added to the world, otherwise the load-event would discard
     *  it as an intruder. */
    public static final String WAVE_TAG = "mobarmy_wave";

    public final Set<UUID> aliveMobs = new HashSet<>();

    /** Mobs die noch gespawnt werden müssen (gestaffelt, nicht alle auf einmal). */
    private final Deque<EntityType<?>> pending = new ArrayDeque<>();
    /** Spawn-Kontext für die Queue (zwischen tick-Aufrufen festgehalten). */
    private Arena pendingArena;
    private List<ServerPlayerEntity> pendingTargets = new ArrayList<>();
    private Team pendingDefender;

    /** True solange die Welle noch läuft (Mobs in Welt ODER in Queue). */
    public boolean isWaveCleared() { return aliveMobs.isEmpty() && pending.isEmpty(); }

    public void spawnWave(ServerWorld world, Arena arena, List<EntityType<?>> mobs,
                          List<ServerPlayerEntity> targets, Team defender) {
        aliveMobs.clear();
        pending.clear();
        if (mobs.isEmpty()) return;

        // Wave-start ambience — einmalig beim Wellen-Beginn, nicht pro Mob.
        BlockPos center = arena.mobSpawnCenter;
        world.playSound(null, center, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.HOSTILE, 1.5f, 0.6f);
        world.playSound(null, center, SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.HOSTILE, 0.4f, 1.4f);

        // Alle Mobs in die Queue. Tick() spawnt sie nach und nach.
        for (EntityType<?> t : mobs) if (t != null) pending.add(t);
        this.pendingArena = arena;
        this.pendingTargets = targets;
        this.pendingDefender = defender;
        MobarmyMod.LOG.info("Wave queued: {} mobs (max alive {})", pending.size(), -1);
    }

    /**
     * Pro Server-Tick aufgerufen: Spawnt bis zu {@code spawnsPerTick} Mobs aus
     * der Queue, aber nur solange {@code aliveMobs.size() < maxAliveMobs}. So
     * laggt der Server nicht und es entstehen keine Riesen-Hordenexplosionen.
     */
    public void tick(ServerWorld world, MobarmyConfig cfg) {
        if (pending.isEmpty()) return;
        if (pendingArena == null) { pending.clear(); return; }
        Random rng = world.getRandom();
        int budget = Math.max(1, cfg.mobSpawnsPerTick);
        int max = Math.max(1, cfg.maxAliveMobs);
        int spread = Math.max(1, cfg.mobSpawnRadius);
        BlockPos center = pendingArena.mobSpawnCenter;

        while (budget-- > 0 && !pending.isEmpty() && aliveMobs.size() < max) {
            EntityType<?> type = pending.poll();
            if (type == null) continue;
            BlockPos pos = center.add(
                rng.nextInt(spread * 2 + 1) - spread,
                0,
                rng.nextInt(spread * 2 + 1) - spread);

            // Dezenter Spawn-FX pro Mob (kein Lightning mehr — sonst blendet's
            // bei großen Wellen total).
            world.spawnParticles(ParticleTypes.LARGE_SMOKE,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                10, 0.3, 0.4, 0.3, 0.04);
            world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                6, 0.2, 0.3, 0.2, 0.02);
            world.playSound(null, pos, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE,
                SoundCategory.HOSTILE, 0.4f, 1.6f);

            Entity ent = spawnMob(world, type, pos, pendingDefender, rng);
            if (ent == null) continue;
            if (ent instanceof MobEntity mob) {
                mob.setPersistent();
                mob.setCanPickUpLoot(false);
                if (!pendingTargets.isEmpty()) {
                    mob.setTarget(pendingTargets.get(rng.nextInt(pendingTargets.size())));
                }
            }
            // Prevent Warden from burrowing — keep anger high so it stays active.
            if (ent instanceof net.minecraft.entity.mob.WardenEntity warden) {
                if (!pendingTargets.isEmpty()) {
                    ServerPlayerEntity target = pendingTargets.get(rng.nextInt(pendingTargets.size()));
                    warden.increaseAngerAt(target, 150, false);
                }
            }
            // Neutral mobs: activate anger so they actually attack players.
            if (ent instanceof net.minecraft.entity.mob.Angerable angerable) {
                angerable.universallyAnger();
                if (!pendingTargets.isEmpty()) {
                    ServerPlayerEntity target = pendingTargets.get(rng.nextInt(pendingTargets.size()));
                    angerable.setTarget(target);
                }
            }
            // Piglins use brain-based AI — trigger revenge behavior.
            if (ent instanceof net.minecraft.entity.mob.PiglinEntity piglin) {
                if (!pendingTargets.isEmpty()) {
                    piglin.setAttacker(pendingTargets.get(rng.nextInt(pendingTargets.size())));
                }
            }
            // Llamas only attack via revenge — fake a hit.
            if (ent instanceof net.minecraft.entity.passive.LlamaEntity llama) {
                if (!pendingTargets.isEmpty()) {
                    llama.setAttacker(pendingTargets.get(rng.nextInt(pendingTargets.size())));
                }
            }
            aliveMobs.add(ent.getUuid());
            if (!ent.getCommandTags().contains(WAVE_TAG)) ent.addCommandTag(WAVE_TAG);
        }
    }

    /**
     * Spawn one mob, preserving variant NBT from a defender's kill pool if any.
     * The freshly created entity is tagged with {@link #WAVE_TAG} BEFORE it is
     * added to the world, so the arena-dimension spawn-blocker will not discard
     * it. Falls back to a manual create→tag→spawn sequence if no NBT pool exists.
     */
    private Entity spawnMob(ServerWorld world, EntityType<?> type, BlockPos pos, Team defender, Random rng) {
        if (defender != null) {
            java.util.List<net.minecraft.nbt.NbtCompound> pool = defender.killedNbts.get(type);
            if (pool != null && !pool.isEmpty()) {
                net.minecraft.nbt.NbtCompound src = pool.get(rng.nextInt(pool.size())).copy();
                // Strip identity / position so the new entity gets a fresh UUID
                // and spawns at our chosen pos.
                src.remove("UUID"); src.remove("Pos"); src.remove("Motion"); src.remove("Rotation");
                src.remove("Tags"); src.remove("Brain");
                Entity loaded = EntityType.loadEntityWithPassengers(src, world, SpawnReason.EVENT, e -> {
                    e.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                        rng.nextFloat() * 360f, 0f);
                    // PRE-TAG: must be set before world.spawnEntity, otherwise the
                    // global ENTITY_LOAD blocker would kill the wave mob.
                    e.addCommandTag(WAVE_TAG);
                    if (!world.spawnEntity(e)) return null;
                    return e;
                });
                if (loaded != null) return loaded;
                // Fallthrough to plain spawn on failure.
            }
        }
        // Plain spawn — replicate type.spawn() so we can pre-tag.
        Entity ent = type.create(world, SpawnReason.EVENT);
        if (ent == null) return null;
        ent.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
            rng.nextFloat() * 360f, 0f);
        ent.addCommandTag(WAVE_TAG);
        if (ent instanceof MobEntity mob) {
            mob.initialize(world, world.getLocalDifficulty(pos), SpawnReason.EVENT, null);
        }
        if (!world.spawnEntity(ent)) return null;
        return ent;
    }

    public void onMobDeath(LivingEntity mob) { aliveMobs.remove(mob.getUuid()); }

    public void clear(ServerWorld world) {
        for (UUID id : aliveMobs) {
            Entity e = world.getEntity(id);
            if (e != null) e.discard();
        }
        aliveMobs.clear();
        pending.clear();
        pendingArena = null;
        pendingTargets = new ArrayList<>();
        pendingDefender = null;
    }
}


