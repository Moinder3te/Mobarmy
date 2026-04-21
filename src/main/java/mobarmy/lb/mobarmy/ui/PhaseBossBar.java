package mobarmy.lb.mobarmy.ui;

import mobarmy.lb.mobarmy.game.GamePhase;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Server-side boss bar shown to all online players.
 * Displays current game phase + remaining time / wave info.
 * Pure vanilla → no client mod required.
 */
public class PhaseBossBar {
    private final ServerBossBar bar;

    public PhaseBossBar() {
        this.bar = new ServerBossBar(Text.literal("Mobarmy"), BossBar.Color.WHITE, BossBar.Style.PROGRESS);
        this.bar.setVisible(false);
    }

    public void update(MinecraftServer server, GamePhase phase, int remainingTicks, int totalTicks, String extra) {
        // Add online players that are not yet on the bar
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
        }

        switch (phase) {
            case LOBBY -> {
                bar.setName(Text.literal("⚐ Lobby").formatted(Formatting.WHITE));
                bar.setColor(BossBar.Color.WHITE);
                bar.setPercent(1f);
                bar.setVisible(true);
            }
            case FARM -> {
                int s = Math.max(0, remainingTicks / 20);
                String name = "🌾 FARM  §7— §a" + formatTime(s) + "§7 verbleibend";
                if (extra != null && !extra.isEmpty()) name += "  §7| " + extra;
                bar.setName(Text.literal(name).formatted(Formatting.GREEN));
                bar.setColor(BossBar.Color.GREEN);
                bar.setPercent(safePct(remainingTicks, totalTicks));
                bar.setVisible(true);
            }
            case ARRANGE -> {
                int s = Math.max(0, remainingTicks / 20);
                bar.setName(Text.literal("🧩 ANORDNEN  §7— §e" + formatTime(s) + "§7 verbleibend").formatted(Formatting.YELLOW));
                bar.setColor(BossBar.Color.YELLOW);
                bar.setPercent(safePct(remainingTicks, totalTicks));
                bar.setVisible(true);
            }
            case BATTLE -> {
                String name = "⚔ BATTLE";
                if (extra != null && !extra.isEmpty()) name += "  §7" + extra;
                bar.setName(Text.literal(name).formatted(Formatting.RED));
                bar.setColor(BossBar.Color.RED);
                bar.setPercent(1f);
                bar.setVisible(true);
            }
            case END -> {
                bar.setName(Text.literal("🏆 SPIEL VORBEI").formatted(Formatting.GOLD));
                bar.setColor(BossBar.Color.PURPLE);
                bar.setPercent(1f);
                bar.setVisible(true);
            }
        }
    }

    public void hide() { bar.setVisible(false); }

    public void clear() {
        bar.clearPlayers();
        bar.setVisible(false);
    }

    private static float safePct(int r, int t) {
        if (t <= 0) return 1f;
        return Math.max(0f, Math.min(1f, (float) r / t));
    }

    private static String formatTime(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        if (m > 0) return String.format("%d:%02d", m, s);
        return seconds + "s";
    }
}

