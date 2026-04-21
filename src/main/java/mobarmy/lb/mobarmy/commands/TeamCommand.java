package mobarmy.lb.mobarmy.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import mobarmy.lb.mobarmy.MobarmyMod;
import mobarmy.lb.mobarmy.team.Team;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class TeamCommand {
    public static void register(CommandDispatcher<ServerCommandSource> d, MobarmyMod mod) {
        d.register(CommandManager.literal("mteam")
            .then(CommandManager.literal("create")
                .then(CommandManager.argument("name", StringArgumentType.word())
                    .then(CommandManager.argument("color", StringArgumentType.word())
                        .executes(ctx -> create(ctx.getSource(), mod,
                            StringArgumentType.getString(ctx, "name"),
                            StringArgumentType.getString(ctx, "color"))))
                    .executes(ctx -> create(ctx.getSource(), mod,
                        StringArgumentType.getString(ctx, "name"), "white"))))
            .then(CommandManager.literal("join")
                .then(CommandManager.argument("name", StringArgumentType.word())
                    .executes(ctx -> join(ctx.getSource(), mod, StringArgumentType.getString(ctx, "name")))))
            .then(CommandManager.literal("leave")
                .executes(ctx -> leave(ctx.getSource(), mod)))
            .then(CommandManager.literal("list")
                .executes(ctx -> list(ctx.getSource(), mod)))
            .then(CommandManager.literal("remove")
                .then(CommandManager.argument("name", StringArgumentType.word())
                    .executes(ctx -> {
                        boolean ok = mod.teams.remove(StringArgumentType.getString(ctx, "name"));
                        ctx.getSource().sendFeedback(() -> Text.literal(ok ? "Team gelöscht." : "Nicht gefunden.").formatted(ok ? Formatting.GREEN : Formatting.RED), false);
                        return 1;
                    })))
        );
    }

    private static int create(ServerCommandSource s, MobarmyMod mod, String name, String colorName) {
        Formatting f = Formatting.byName(colorName);
        if (f == null || !f.isColor()) f = Formatting.WHITE;
        Team t = mod.teams.create(name, f);
        if (t == null) {
            s.sendFeedback(() -> Text.literal("Team existiert bereits.").formatted(Formatting.RED), false);
            return 0;
        }
        Formatting fc = f;
        s.sendFeedback(() -> Text.literal("Team " + name + " erstellt.").formatted(fc), true);
        return 1;
    }

    private static int join(ServerCommandSource s, MobarmyMod mod, String name) {
        ServerPlayerEntity p = s.getPlayer();
        if (p == null) return 0;
        if (mod.teams.join(p, name)) {
            s.sendFeedback(() -> Text.literal("Du bist nun in Team " + name).formatted(Formatting.GREEN), false);
            return 1;
        }
        s.sendFeedback(() -> Text.literal("Team nicht gefunden.").formatted(Formatting.RED), false);
        return 0;
    }

    private static int leave(ServerCommandSource s, MobarmyMod mod) {
        ServerPlayerEntity p = s.getPlayer();
        if (p == null) return 0;
        mod.teams.leave(p);
        s.sendFeedback(() -> Text.literal("Team verlassen.").formatted(Formatting.YELLOW), false);
        return 1;
    }

    private static int list(ServerCommandSource s, MobarmyMod mod, MobarmyMod ignore) { return list(s, mod); }
    private static int list(ServerCommandSource s, MobarmyMod mod) {
        if (mod.teams.size() == 0) {
            s.sendFeedback(() -> Text.literal("Keine Teams.").formatted(Formatting.GRAY), false);
            return 0;
        }
        for (Team t : mod.teams.all()) {
            String members = String.join(", ", t.members.stream().map(u -> {
                var p = s.getServer().getPlayerManager().getPlayer(u);
                return p != null ? p.getName().getString() : u.toString().substring(0, 8);
            }).toList());
            s.sendFeedback(() -> Text.literal(t.name + " [" + t.members.size() + "]: " + members).formatted(t.color), false);
        }
        return 1;
    }
}


