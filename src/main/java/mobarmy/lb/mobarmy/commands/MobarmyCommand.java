package mobarmy.lb.mobarmy.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import mobarmy.lb.mobarmy.MobarmyMod;
import mobarmy.lb.mobarmy.config.MobarmyConfig;
import mobarmy.lb.mobarmy.game.GamePhase;
import mobarmy.lb.mobarmy.team.Team;
import mobarmy.lb.mobarmy.util.MobUtils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.CompletableFuture;

public class MobarmyCommand {
    public static void register(CommandDispatcher<ServerCommandSource> d, MobarmyMod mod) {
        d.register(CommandManager.literal("mobarmy")
            .then(CommandManager.literal("menu").executes(ctx -> {
                ServerPlayerEntity sp = ctx.getSource().getPlayer();
                if (sp == null) return 0;
                mobarmy.lb.mobarmy.ui.lobby.LobbyHub.open(sp, mod);
                return 1;
            }))
            .then(CommandManager.literal("stats").executes(ctx -> {
                ServerPlayerEntity sp = ctx.getSource().getPlayer();
                if (sp == null) return 0;
                var gs = mod.gameManager.lastGameStats();
                if (gs == null) {
                    ctx.getSource().sendFeedback(() -> Text.literal("Keine Stats vorhanden — noch kein Spiel beendet.").formatted(Formatting.RED), false);
                    return 0;
                }
                mobarmy.lb.mobarmy.ui.stats.StatsMenu.open(sp, gs);
                return 1;
            }))
            .then(CommandManager.literal("start").executes(ctx -> {
                mod.gameManager.startGame(ctx.getSource().getServer());
                return 1;
            }))
            .then(CommandManager.literal("stop").executes(ctx -> {
                mod.gameManager.resetToLobby(ctx.getSource().getServer());
                return 1;
            }))
            .then(CommandManager.literal("skip").executes(ctx -> {
                // Force-advance to next phase
                var gm = mod.gameManager;
                var srv = ctx.getSource().getServer();
                switch (gm.phase()) {
                    case FARM -> gm.startArrange(srv);
                    case ARRANGE -> gm.startBattle(srv);
                    default -> ctx.getSource().sendFeedback(() -> Text.literal("Nichts zu skippen.").formatted(Formatting.GRAY), false);
                }
                return 1;
            }))
            .then(CommandManager.literal("status").executes(ctx -> {
                var gm = mod.gameManager;
                ctx.getSource().sendFeedback(() -> Text.literal("Phase: " + gm.phase() + " (" + gm.phaseTicksRemaining() / 20 + "s)").formatted(Formatting.AQUA), false);
                return 1;
            }))
            .then(CommandManager.literal("setlobby").executes(ctx -> setPos(ctx.getSource(), mod, "lobby")))
            .then(CommandManager.literal("setspectator").executes(ctx -> setPos(ctx.getSource(), mod, "spectator")))
            .then(CommandManager.literal("setspawn").then(CommandManager.literal("a").executes(ctx -> setPos(ctx.getSource(), mod, "spawnA")))
                                                    .then(CommandManager.literal("b").executes(ctx -> setPos(ctx.getSource(), mod, "spawnB"))))
            .then(CommandManager.literal("setmobspawn").executes(ctx -> setPos(ctx.getSource(), mod, "mobs")))
            .then(CommandManager.literal("setarena")
                .then(CommandManager.argument("which", IntegerArgumentType.integer(1, 2))
                    .executes(ctx -> setPos(ctx.getSource(), mod, "arena" + IntegerArgumentType.getInteger(ctx, "which")))))
            .then(CommandManager.literal("buildarena").executes(ctx -> {
                new mobarmy.lb.mobarmy.arena.Arena(mod.config).build(ctx.getSource().getWorld());
                ctx.getSource().sendFeedback(() -> Text.literal("Arena gebaut.").formatted(Formatting.GREEN), true);
                return 1;
            }))
            .then(CommandManager.literal("prepare")
                .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 16))
                    .executes(ctx -> {
                        int n = IntegerArgumentType.getInteger(ctx, "count");
                        mod.gameManager.prepareArenas(ctx.getSource().getServer(), n);
                        return 1;
                    })))
            .then(CommandManager.literal("save").executes(ctx -> {
                mod.config.save(ctx.getSource().getServer());
                ctx.getSource().sendFeedback(() -> Text.literal("Konfiguration gespeichert.").formatted(Formatting.GREEN), false);
                return 1;
            }))
            .then(CommandManager.literal("phase").executes(ctx -> {
                ctx.getSource().sendFeedback(() -> Text.literal("Phase: " + mod.gameManager.phase()).formatted(Formatting.AQUA), false);
                return 1;
            }))
            // ===== DEBUG: schnell Mobs in den Team-Pool füllen =====
            .then(CommandManager.literal("debug")
                .then(CommandManager.literal("givemob")
                    .then(CommandManager.argument("type", StringArgumentType.word())
                        .suggests(MOB_TYPE_SUGGESTIONS)
                        .executes(ctx -> giveMob(ctx.getSource(), mod,
                            StringArgumentType.getString(ctx, "type"), 1, false))
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 1024))
                            .executes(ctx -> giveMob(ctx.getSource(), mod,
                                StringArgumentType.getString(ctx, "type"),
                                IntegerArgumentType.getInteger(ctx, "count"), false)))))
                .then(CommandManager.literal("givebaby")
                    .then(CommandManager.argument("type", StringArgumentType.word())
                        .suggests(MOB_TYPE_SUGGESTIONS)
                        .executes(ctx -> giveMob(ctx.getSource(), mod,
                            StringArgumentType.getString(ctx, "type"), 1, true))
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 1024))
                            .executes(ctx -> giveMob(ctx.getSource(), mod,
                                StringArgumentType.getString(ctx, "type"),
                                IntegerArgumentType.getInteger(ctx, "count"), true)))))
                .then(CommandManager.literal("giveall")
                    .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 1024))
                        .executes(ctx -> giveAll(ctx.getSource(), mod,
                            IntegerArgumentType.getInteger(ctx, "count"), false))
                        .then(CommandManager.literal("baby").executes(ctx -> giveAll(ctx.getSource(), mod,
                            IntegerArgumentType.getInteger(ctx, "count"), true)))))
                .then(CommandManager.literal("clearmobs").executes(ctx -> {
                    ServerPlayerEntity sp = ctx.getSource().getPlayer();
                    if (sp == null) return 0;
                    Team t = mod.teams.get(sp);
                    if (t == null) {
                        ctx.getSource().sendFeedback(() -> Text.literal("Du bist in keinem Team.").formatted(Formatting.RED), false);
                        return 0;
                    }
                    t.killedMobs.clear();
                    t.killedBabies.clear();
                    t.killedNbts.clear();
                    ctx.getSource().sendFeedback(() -> Text.literal("Mob-Pool von Team " + t.name + " geleert.").formatted(Formatting.YELLOW), true);
                    return 1;
                }))
                .then(CommandManager.literal("listmobs").executes(ctx -> {
                    ServerPlayerEntity sp = ctx.getSource().getPlayer();
                    if (sp == null) return 0;
                    Team t = mod.teams.get(sp);
                    if (t == null) {
                        ctx.getSource().sendFeedback(() -> Text.literal("Du bist in keinem Team.").formatted(Formatting.RED), false);
                        return 0;
                    }
                    if (t.killedMobs.isEmpty()) {
                        ctx.getSource().sendFeedback(() -> Text.literal("Pool ist leer.").formatted(Formatting.GRAY), false);
                        return 1;
                    }
                    StringBuilder sb = new StringBuilder("Pool von ").append(t.name).append(": ");
                    for (EntityType<?> type : t.killedMobs.keySet()) {
                        Identifier id = Registries.ENTITY_TYPE.getId(type);
                        int c = t.killedMobs.getInt(type);
                        int babies = t.killedBabies.getInt(type);
                        sb.append(id.getPath()).append(" \u00d7").append(c);
                        if (babies > 0) sb.append(" (").append(babies).append(" baby)");
                        sb.append(", ");
                    }
                    ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()).formatted(Formatting.AQUA), false);
                    return 1;
                }))
            )
        );

        // Standalone alias /lobby
        d.register(CommandManager.literal("lobby").executes(ctx -> {
            ServerPlayerEntity sp = ctx.getSource().getPlayer();
            if (sp == null) return 0;
            mobarmy.lb.mobarmy.ui.lobby.LobbyHub.open(sp, mod);
            return 1;
        }));
    }

    private static int setPos(ServerCommandSource s, MobarmyMod mod, String which) {
        ServerPlayerEntity p = s.getPlayer();
        if (p == null) return 0;
        BlockPos b = p.getBlockPos();
        MobarmyConfig.BlockPosJson pos = new MobarmyConfig.BlockPosJson(b.getX(), b.getY(), b.getZ());
        switch (which) {
            case "lobby" -> mod.config.lobbyPos = pos;
            case "spectator" -> mod.config.spectatorPos = pos;
            case "spawnA" -> mod.config.arenaSpawnA = pos;
            case "spawnB" -> mod.config.arenaSpawnB = pos;
            case "mobs" -> mod.config.mobSpawnCenter = pos;
            case "arena1" -> mod.config.arenaPos1 = pos;
            case "arena2" -> mod.config.arenaPos2 = pos;
        }
        mod.config.save(s.getServer());
        s.sendFeedback(() -> Text.literal(which + " gesetzt auf " + b.toShortString()).formatted(Formatting.GREEN), true);
        return 1;
    }

    // ===================== DEBUG HELPERS =====================

    /** Tab-completion: alle live entity-type IDs. */
    private static final SuggestionProvider<ServerCommandSource> MOB_TYPE_SUGGESTIONS = (ctx, b) -> {
        String remaining = b.getRemainingLowerCase();
        for (Identifier id : Registries.ENTITY_TYPE.getIds()) {
            String path = id.getPath();
            String full = id.toString();
            if (path.startsWith(remaining) || full.startsWith(remaining)) {
                b.suggest(path);
            }
        }
        return b.buildFuture();
    };

    /**
     * Adds {@code count} mobs of the given type to the calling player's team
     * "killed" pool, including a real NBT snapshot per kill so the wave-spawner
     * can recreate exact variants. The mob is created in-memory via
     * {@code type.create(world, SpawnReason.COMMAND)}, snapshotted, then
     * discarded — no entity ever enters the world.
     */
    private static int giveMob(ServerCommandSource s, MobarmyMod mod, String typeStr, int count, boolean baby) {
        ServerPlayerEntity p = s.getPlayer();
        if (p == null) {
            s.sendError(Text.literal("Nur als Spieler nutzbar."));
            return 0;
        }
        Team team = mod.teams.get(p);
        if (team == null) {
            s.sendError(Text.literal("Du bist in keinem Team. /mteam create <name> oder /mteam join <name>"));
            return 0;
        }
        Identifier id = typeStr.contains(":") ? Identifier.tryParse(typeStr) : Identifier.tryParse("minecraft:" + typeStr);
        if (id == null) {
            s.sendError(Text.literal("Ungültiger Typ: " + typeStr));
            return 0;
        }
        EntityType<?> type = Registries.ENTITY_TYPE.get(id);
        if (type == null || !Registries.ENTITY_TYPE.containsId(id)) {
            s.sendError(Text.literal("Unbekannte Entity: " + id));
            return 0;
        }

        ServerWorld world = (ServerWorld) p.getEntityWorld();
        int added = 0;
        int babyAdded = 0;
        for (int i = 0; i < count; i++) {
            Entity ent = type.create(world, SpawnReason.COMMAND);
            if (ent == null) continue;
            // Position braucht es nur damit save() nicht meckert.
            ent.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ(), 0f, 0f);
            if (baby) MobUtils.setBaby(ent, true);
            NbtCompound nbt = MobUtils.snapshotVariantNbt(ent);
            ent.discard();
            if (nbt == null) continue;
            team.killedNbts.computeIfAbsent(type, k -> new java.util.ArrayList<>()).add(nbt);
            team.killedMobs.addTo(type, 1);
            if (baby) team.killedBabies.addTo(type, 1);
            added++;
            if (baby) babyAdded++;
            team.score += 1;
        }
        final int finalAdded = added;
        final int finalBaby = babyAdded;
        s.sendFeedback(() -> Text.literal("[debug] +" + finalAdded + "× " + id.getPath()
            + (finalBaby > 0 ? " (" + finalBaby + " baby)" : "")
            + " in Pool von " + team.name).formatted(Formatting.LIGHT_PURPLE), true);
        return finalAdded;
    }

    /**
     * Adds {@code count} mobs of EVERY living entity type to the calling
     * player's team pool. Skips non-living/utility entities (items, projectiles,
     * markers, players, …) and any type that fails to instantiate via
     * {@code type.create(world, SpawnReason.COMMAND)}.
     */
    private static int giveAll(ServerCommandSource s, MobarmyMod mod, int count, boolean baby) {
        ServerPlayerEntity p = s.getPlayer();
        if (p == null) {
            s.sendError(Text.literal("Nur als Spieler nutzbar."));
            return 0;
        }
        Team team = mod.teams.get(p);
        if (team == null) {
            s.sendError(Text.literal("Du bist in keinem Team. /mteam create <name> oder /mteam join <name>"));
            return 0;
        }

        ServerWorld world = (ServerWorld) p.getEntityWorld();
        int totalAdded = 0;
        int typesAdded = 0;
        int typesSkipped = 0;

        for (Identifier id : Registries.ENTITY_TYPE.getIds()) {
            EntityType<?> type = Registries.ENTITY_TYPE.get(id);
            if (type == null) continue;
            // Nur lebende Mobs, keine Items/Projectiles/Marker/Player
            if (!net.minecraft.entity.SpawnGroup.MONSTER.equals(type.getSpawnGroup())
                && !net.minecraft.entity.SpawnGroup.CREATURE.equals(type.getSpawnGroup())
                && !net.minecraft.entity.SpawnGroup.WATER_CREATURE.equals(type.getSpawnGroup())
                && !net.minecraft.entity.SpawnGroup.WATER_AMBIENT.equals(type.getSpawnGroup())
                && !net.minecraft.entity.SpawnGroup.AMBIENT.equals(type.getSpawnGroup())
                && !net.minecraft.entity.SpawnGroup.AXOLOTLS.equals(type.getSpawnGroup())
                && !net.minecraft.entity.SpawnGroup.UNDERGROUND_WATER_CREATURE.equals(type.getSpawnGroup())) {
                continue;
            }
            int addedForType = 0;
            for (int i = 0; i < count; i++) {
                Entity ent = type.create(world, SpawnReason.COMMAND);
                if (ent == null) break; // dieser Typ unterstützt kein create() -> ganz überspringen
                ent.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ(), 0f, 0f);
                if (baby) MobUtils.setBaby(ent, true);
                NbtCompound nbt = MobUtils.snapshotVariantNbt(ent);
                ent.discard();
                if (nbt == null) continue;
                team.killedNbts.computeIfAbsent(type, k -> new java.util.ArrayList<>()).add(nbt);
                team.killedMobs.addTo(type, 1);
                if (baby) team.killedBabies.addTo(type, 1);
                team.score += 1;
                addedForType++;
            }
            if (addedForType > 0) {
                typesAdded++;
                totalAdded += addedForType;
            } else {
                typesSkipped++;
            }
        }

        final int fAdded = totalAdded;
        final int fTypes = typesAdded;
        final int fSkip = typesSkipped;
        s.sendFeedback(() -> Text.literal("[debug] giveall: +" + fAdded + " Mobs aus " + fTypes
            + " Typen in Pool von " + team.name
            + (fSkip > 0 ? " (" + fSkip + " Typen übersprungen)" : "")
            + (baby ? " [baby]" : "")).formatted(Formatting.LIGHT_PURPLE), true);
        return fAdded;
    }
}


