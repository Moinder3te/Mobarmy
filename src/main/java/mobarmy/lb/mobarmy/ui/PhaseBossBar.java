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
        // Remove disconnected players and add new ones.
        for (ServerPlayerEntity p : new java.util.ArrayList<>(bar.getPlayers())) {
            if (p.isDisconnected()) bar.removePlayer(p);
        }
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
                Text name = Text.literal("🌾 FARM  ").formatted(Formatting.GREEN)
                    .append(Text.literal("— ").formatted(Formatting.GRAY))
                    .append(Text.literal(formatTime(s)).formatted(Formatting.GREEN))
                    .append(Text.literal(" verbleibend").formatted(Formatting.GRAY));
                if (extra != null && !extra.isEmpty()) {
                    name = name.copy().append(Text.literal("  | ").formatted(Formatting.GRAY))
                        .append(Text.literal(extra).formatted(Formatting.YELLOW));
                }
                bar.setName(name);
                bar.setColor(BossBar.Color.GREEN);
                bar.setPercent(safePct(remainingTicks, totalTicks));
                bar.setVisible(true);
            }
            case ARRANGE -> {
                int s = Math.max(0, remainingTicks / 20);
                bar.setName(Text.literal("🧩 ANORDNEN  ").formatted(Formatting.YELLOW)
                    .append(Text.literal("— ").formatted(Formatting.GRAY))
                    .append(Text.literal(formatTime(s)).formatted(Formatting.YELLOW))
                    .append(Text.literal(" verbleibend").formatted(Formatting.GRAY)));
                bar.setColor(BossBar.Color.YELLOW);
                bar.setPercent(safePct(remainingTicks, totalTicks));
                bar.setVisible(true);
            }
            case BATTLE -> {
                // Each MatchInstance has its own per-team boss bar; hide the global one.
                bar.setVisible(false);
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

