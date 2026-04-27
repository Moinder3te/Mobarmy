package mobarmy.lb.mobarmy;

import mobarmy.lb.mobarmy.arena.ArenaDimension;
import mobarmy.lb.mobarmy.arena.ArenaProtection;
import mobarmy.lb.mobarmy.battle.WaveSpawner;
import mobarmy.lb.mobarmy.commands.BackpackCommand;
import mobarmy.lb.mobarmy.commands.MobarmyCommand;
import mobarmy.lb.mobarmy.commands.TeamCommand;
import mobarmy.lb.mobarmy.config.MobarmyConfig;
import mobarmy.lb.mobarmy.game.GameManager;
import mobarmy.lb.mobarmy.game.GamePhase;
import mobarmy.lb.mobarmy.randomizer.RandomizerManager;
import mobarmy.lb.mobarmy.team.TeamManager;
import mobarmy.lb.mobarmy.util.TaskScheduler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;

public class MobarmyMod implements ModInitializer {
    public static final Logger LOG = MobarmyRef.LOG;

    /** Static singleton — vom Mixin {@code BlockDropStackMixin} gelesen,
     *  weil Mixins keine Konstruktor-Injection bekommen. */
    public static MobarmyMod INSTANCE;

    public MinecraftServer server;
    public MobarmyConfig config = new MobarmyConfig();
    public final TeamManager teams = new TeamManager();
    public final RandomizerManager randomizerManager = new RandomizerManager();
    public final TaskScheduler scheduler = new TaskScheduler();
    public final GameManager gameManager = new GameManager(this);

    @Override
    public void onInitialize() {
        INSTANCE = this;
        LOG.info("Mobarmy initializing");

        ServerLifecycleEvents.SERVER_STARTED.register(srv -> {
            this.server = srv;
            this.config = MobarmyConfig.load(srv);
            if (config.randomizerSeed != 0) {
                randomizerManager.init(config.randomizerSeed, config.randomizerMode, java.util.List.of());
            }
            // Defense-in-depth: lock down arena dimension immediately on
            // startup, so even before the first /mobarmy prepare runs no
            // natural mob spawning, weather or fire-tick can kick in.
            ArenaDimension.applyGameRules(srv.getWorld(ArenaDimension.WORLD_KEY));

            // ROLLBACK: in einer früheren Version wurden KEEP_INVENTORY +
            // DO_IMMEDIATE_RESPAWN versehentlich auch in der Overworld/Lobby
            // gesetzt (weil ArenaDimension.get() auf Overworld zurückfällt
            // wenn das Datapack fehlt). Wir setzen sie hier einmalig zurück
            // damit alte Welten den Bug los werden. In Arena-Dim bleiben sie
            // weiterhin true (siehe ArenaDimension.applyGameRules oben, das
            // jetzt eine Identity-Prüfung gegen WORLD_KEY hat).
            net.minecraft.server.world.ServerWorld ow = srv.getOverworld();
            if (ow != null) {
                try {
                    var gr = ow.getGameRules();
                    gr.setValue(net.minecraft.world.rule.GameRules.KEEP_INVENTORY, false, srv);
                    gr.setValue(net.minecraft.world.rule.GameRules.DO_IMMEDIATE_RESPAWN, false, srv);
                } catch (Throwable ignored) {}
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            if (config != null) config.save(srv);
        });

        CommandRegistrationCallback.EVENT.register((d, reg, env) -> {
            MobarmyCommand.register(d, this);
            TeamCommand.register(d, this);
            BackpackCommand.register(d, this);
        });

        ServerTickEvents.END_SERVER_TICK.register(srv -> {
            scheduler.tick();
            gameManager.tick(srv);
        });

        // Block-Randomizer wird zentral in BlockDropStackMixin erledigt:
        // jedes Block.dropStack(...) tauscht den Stack BEVOR Vanilla das
        // ItemEntity erstellt. Damit gilt der Swap für JEDEN Drop-Pfad
        // (Spieler-Break, scheduledTick-Ketten wie Zuckerrohr/Bambus,
        // TNT-Explosionen, Pistons, Wasser-Brüche, Skulk-Spread, ...) und
        // wir brauchen weder PlayerBlockBreakEvents noch ENTITY_LOAD-Hooks.

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            // Spieler-Tod: Vanilla regelt Sofort-Respawn am gesetzten
            // Cache-Punkt (siehe BattleController.startRound) inkl.
            // keepInventory + Totem. Wir planen nur den Rück-Teleport
            // zum Kampf-Pad nach respawnDelayTicks (default 30 s).
            if (entity instanceof ServerPlayerEntity sp) {
                if (gameManager.phase() != GamePhase.BATTLE || gameManager.battle() == null) return;
                var match = gameManager.battle().findMatchForAttacker(sp);
                if (match == null) return;
                // Track arena death for stats.
                match.onPlayerDeath();
                final java.util.UUID uuid = sp.getUuid();
                final var arena = match.arena;
                int delay = config.respawnDelayTicks;
                int totalSeconds = delay / 20;
                // Countdown action bar: show remaining seconds every second.
                for (int s = totalSeconds; s > 0; s--) {
                    final int sec = s;
                    scheduler.schedule((totalSeconds - s) * 20, () -> {
                        if (server == null || gameManager.phase() != GamePhase.BATTLE) return;
                        ServerPlayerEntity pp = server.getPlayerManager().getPlayer(uuid);
                        if (pp == null || !pp.isAlive()) return;
                        pp.sendMessage(net.minecraft.text.Text.literal(
                            "⚔ Zurück in die Arena in " + sec + "s")
                            .formatted(net.minecraft.util.Formatting.RED), true);
                    });
                }
                scheduler.schedule(delay, () -> {
                    if (server == null) return;
                    if (gameManager.phase() != GamePhase.BATTLE) return;
                    ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                    if (p == null || !p.isAlive()) return;
                    var arenaWorld = mobarmy.lb.mobarmy.arena.ArenaDimension.get(server);
                    mobarmy.lb.mobarmy.util.PlayerUtils.heal(p);
                    mobarmy.lb.mobarmy.util.PlayerUtils.setMode(p, net.minecraft.world.GameMode.SURVIVAL);
                    mobarmy.lb.mobarmy.util.PlayerUtils.teleport(p, arenaWorld, arena.spawnA);
                    // Re-give mob tracker after respawn.
                    mobarmy.lb.mobarmy.battle.MobTracker.removeAll(p);
                    p.getInventory().setStack(8, mobarmy.lb.mobarmy.battle.MobTracker.create());
                    // Sound cue on re-entry.
                    arenaWorld.playSound(null, p.getBlockPos(),
                        net.minecraft.sound.SoundEvents.ENTITY_ENDERMAN_TELEPORT,
                        net.minecraft.sound.SoundCategory.PLAYERS, 0.8f, 1.2f);
                });
                return;
            }
            ServerPlayerEntity killer = null;
            if (source.getAttacker() instanceof ServerPlayerEntity sp) killer = sp;
            else if (entity.getAttacker() instanceof ServerPlayerEntity sp) killer = sp;
            if (killer != null) gameManager.onMobKill(entity, killer);
            else if (gameManager.phase() == GamePhase.BATTLE && gameManager.battle() != null) {
                gameManager.battle().onMobDeath(entity);
            }
        });

        // ALLOW_DEATH-Hook für Spieler entfernt (vorher: Tod abgefangen +
        // gameManager.onPlayerDeath). Vanilla regelt jetzt alles.

        // Players in the wave-builder UI (ARRANGE phase) take no damage at all.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayerEntity)) return true;
            if (gameManager.phase() == GamePhase.ARRANGE) return false;
            return true;
        });

        // Prevent players from breaking arena structure blocks.
        // In the arena dimension: block ALL breaks unless it's BATTLE phase
        // AND the position is not a protected structure block.
        // This prevents destruction when visiting the dimension outside of games.
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
            if (world.isClient()) return true;
            // Always protect ArenaProtection-tracked positions (any dimension).
            if (ArenaProtection.isProtected(pos)) return false;
            // In the arena dimension, only allow breaking during BATTLE phase.
            if (world instanceof net.minecraft.server.world.ServerWorld sw
                    && sw.getRegistryKey() == ArenaDimension.WORLD_KEY) {
                return gameManager.isPhase(GamePhase.BATTLE);
            }
            return true;
        });

        // === HARD MOB-SPAWN BLOCKER for the arena dimension ===
        // Anything entering the arena world that isn't (a) a player, (b) one of
        // our wave mobs (pre-tagged WAVE_TAG by WaveSpawner BEFORE spawnEntity),
        // or (c) a harmless ambient entity (lightning bolt, item, xp orb, arrow,
        // experience, etc.) is discarded the very tick it loads — so natural
        // mob spawns can never appear, and our wave mobs are NEVER touched.
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (world.getRegistryKey() != ArenaDimension.WORLD_KEY) return;
            if (!(entity instanceof MobEntity mob)) return;            // only living monsters/animals
            if (entity.getCommandTags().contains(WaveSpawner.WAVE_TAG)) return; // ours
            // Defense-in-depth: if this UUID is currently tracked as a live wave
            // mob (e.g. the chunk just reloaded after a player respawn and the
            // tag got stripped somehow), preserve it and re-tag instead of
            // killing it.
            if (gameManager.battle() != null) {
                for (var match : gameManager.battle().matches()) {
                    if (match.spawner.aliveMobs.contains(entity.getUuid())) {
                        entity.addCommandTag(WaveSpawner.WAVE_TAG);
                        mob.setPersistent();
                        return;
                    }
                }
            }
            entity.discard();
        });

        LOG.info("Mobarmy ready");
    }
}


