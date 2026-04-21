package mobarmy.lb.mobarmy.commands;

import com.mojang.brigadier.CommandDispatcher;
import mobarmy.lb.mobarmy.MobarmyMod;
import mobarmy.lb.mobarmy.backpack.BackpackScreenFactory;
import mobarmy.lb.mobarmy.team.Team;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class BackpackCommand {
    public static void register(CommandDispatcher<ServerCommandSource> d, MobarmyMod mod) {
        d.register(CommandManager.literal("bp").executes(ctx -> open(ctx.getSource(), mod)));
        d.register(CommandManager.literal("backpack").executes(ctx -> open(ctx.getSource(), mod)));
    }

    private static int open(ServerCommandSource s, MobarmyMod mod) {
        ServerPlayerEntity p = s.getPlayer();
        if (p == null) return 0;
        Team t = mod.teams.get(p);
        if (t == null) {
            s.sendFeedback(() -> Text.literal("Du bist in keinem Team.").formatted(Formatting.RED), false);
            return 0;
        }
        p.openHandledScreen(new BackpackScreenFactory(t));
        return 1;
    }
}

