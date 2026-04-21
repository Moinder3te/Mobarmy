package mobarmy.lb.mobarmy.ui.stats;

import mobarmy.lb.mobarmy.ui.lobby.MenuUtils;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-page stats UI opened at game end.
 *
 * Pages:
 *   0 = Overview (rankings, awards)
 *   1..N = Per-team detail page (one page per team in ranking order)
 */
public class StatsMenu implements NamedScreenHandlerFactory {

    private static final int ROWS = 6;
    private static final int SLOTS = ROWS * 9;

    private final GameStats stats;
    private int page;

    public StatsMenu(GameStats stats, int page) {
        this.stats = stats;
        this.page = page;
    }

    public static void open(ServerPlayerEntity p, GameStats stats) {
        open(p, stats, 0);
    }

    public static void open(ServerPlayerEntity p, GameStats stats, int page) {
        p.openHandledScreen(new StatsMenu(stats, page));
    }

    private int totalPages() {
        return 1 + stats.ranking.size(); // overview + one per team
    }

    @Override
    public Text getDisplayName() {
        if (page == 0) {
            return Text.literal("✦ Endergebnis ✦").formatted(Formatting.GOLD, Formatting.BOLD);
        } else {
            GameStats.TeamStats ts = stats.ranking.get(page - 1);
            return Text.literal("✦ " + ts.name + " — Details ✦").formatted(ts.color, Formatting.BOLD);
        }
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory pi, PlayerEntity p) {
        SimpleInventory inv = new SimpleInventory(SLOTS);
        rebuild(inv);

        return new GenericContainerScreenHandler(ScreenHandlerType.GENERIC_9X6, syncId, pi, inv, ROWS) {
            @Override
            public void onSlotClick(int slot, int button, SlotActionType action, PlayerEntity who) {
                if (slot < 0 || slot >= SLOTS) { super.onSlotClick(slot, button, action, who); return; }
                if (!(who instanceof ServerPlayerEntity sp)) return;
                handleClick(slot, sp);
            }
            @Override public ItemStack quickMove(PlayerEntity p, int s) { return ItemStack.EMPTY; }
        };
    }

    private void handleClick(int slot, ServerPlayerEntity p) {
        // Bottom-left: previous page
        if (slot == SLOTS - 9 && page > 0) {
            open(p, stats, page - 1);
            return;
        }
        // Bottom-right: next page
        if (slot == SLOTS - 1 && page < totalPages() - 1) {
            open(p, stats, page + 1);
            return;
        }
        // Overview page: click on a team ranking entry → go to that team's page
        if (page == 0) {
            // Team entries are at slots 19-25 (row 2, cols 1-7) for up to 7 teams
            int col = slot % 9;
            int row = slot / 9;
            if (row >= 2 && row <= 3 && col >= 1 && col <= 7) {
                int teamIdx = (row - 2) * 7 + (col - 1);
                if (teamIdx < stats.ranking.size()) {
                    open(p, stats, teamIdx + 1);
                }
            }
        }
    }

    private void rebuild(SimpleInventory inv) {
        // Clear all
        for (int i = 0; i < SLOTS; i++) inv.setStack(i, ItemStack.EMPTY);
        MenuUtils.drawBorder(inv, ROWS);

        if (page == 0) {
            buildOverviewPage(inv);
        } else {
            buildTeamPage(inv, stats.ranking.get(page - 1), page - 1);
        }

        // Navigation bar (bottom row)
        buildNavBar(inv);
    }

    // =========================================================================
    //  PAGE 0: OVERVIEW
    // =========================================================================
    private void buildOverviewPage(SimpleInventory inv) {
        // Row 0: Title (slot 4)
        inv.setStack(4, MenuUtils.glow(MenuUtils.named(new ItemStack(Items.NETHER_STAR),
            Text.literal("⚔ ENDERGEBNIS ⚔").formatted(Formatting.GOLD, Formatting.BOLD),
            Text.literal("Klicke ein Team für Details").formatted(Formatting.GRAY)
        )));

        // Row 1: Awards bar
        // Farm MVP (slot 10 → col 1)
        if (stats.farmMvp != null) {
            inv.setStack(10, MenuUtils.named(new ItemStack(Items.DIAMOND_SWORD),
                Text.literal("🗡 Farm-MVP").formatted(Formatting.RED, Formatting.BOLD),
                Text.literal(stats.farmMvp.name).formatted(stats.farmMvp.color),
                Text.literal(stats.farmMvp.totalFarmKills + " Kills").formatted(Formatting.GRAY)
            ));
        }

        // Winner trophy (slot 13 → center)
        if (stats.winner != null) {
            inv.setStack(13, MenuUtils.glow(MenuUtils.named(new ItemStack(Items.GOLDEN_APPLE),
                Text.literal("🏆 GEWINNER 🏆").formatted(Formatting.GOLD, Formatting.BOLD),
                Text.literal(stats.winner.name).formatted(stats.winner.color, Formatting.BOLD),
                Text.literal(stats.winner.score + " Punkte").formatted(Formatting.YELLOW)
            )));
        }

        // Most diverse (slot 16 → col 7)
        if (stats.mostDiverse != null) {
            inv.setStack(16, MenuUtils.named(new ItemStack(Items.SPAWNER),
                Text.literal("🌍 Artenvielfalt").formatted(Formatting.GREEN, Formatting.BOLD),
                Text.literal(stats.mostDiverse.name).formatted(stats.mostDiverse.color),
                Text.literal(stats.mostDiverse.uniqueMobTypes + " verschiedene Arten").formatted(Formatting.GRAY)
            ));
        }

        // Rows 2-3: Team ranking entries (clickable)
        String[] medals = {"🥇", "🥈", "🥉"};
        for (int i = 0; i < stats.ranking.size() && i < 14; i++) {
            GameStats.TeamStats ts = stats.ranking.get(i);
            int row = 2 + i / 7;
            int col = 1 + i % 7;
            int slot = row * 9 + col;

            String medal = i < medals.length ? medals[i] + " " : "#" + (i + 1) + " ";
            Item wool = MenuUtils.woolFor(ts.color);
            ItemStack stack = i == 0 ? MenuUtils.glow(new ItemStack(wool)) : new ItemStack(wool);

            List<Text> lore = new ArrayList<>();
            lore.add(Text.literal("Score: " + ts.score + " Punkte").formatted(Formatting.YELLOW));
            lore.add(Text.literal("Farm: " + ts.totalFarmKills + " Kills (" + ts.uniqueMobTypes + " Arten)").formatted(Formatting.GRAY));
            lore.add(Text.literal("Battle: " + ts.wavesCleared + " Wellen überlebt").formatted(Formatting.GRAY));
            lore.add(Text.empty());
            lore.add(Text.literal("» Klicke für Details").formatted(Formatting.AQUA));

            stack.set(DataComponentTypes.CUSTOM_NAME,
                Text.literal("").append(Text.literal(medal + ts.name).formatted(ts.color, Formatting.BOLD)).styled(s -> s.withItalic(false)));
            stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
            inv.setStack(slot, stack);
        }

        // Row 4: Score comparison bar
        buildScoreBar(inv, 4);
    }

    /** Visual score comparison using colored glass panes across row. */
    private void buildScoreBar(SimpleInventory inv, int row) {
        if (stats.ranking.isEmpty()) return;
        int maxScore = stats.ranking.get(0).score;
        if (maxScore <= 0) return;

        // Calculate proportional widths (7 usable slots: cols 1-7)
        int totalSlots = 7;
        // Just show farm vs battle split for the winner
        GameStats.TeamStats w = stats.ranking.get(0);
        int farmSlots = Math.max(1, (int) Math.round((double) w.totalFarmKills / maxScore * totalSlots));
        int battleSlots = totalSlots - farmSlots;

        for (int c = 1; c <= 7; c++) {
            int slot = row * 9 + c;
            if (c <= farmSlots) {
                inv.setStack(slot, MenuUtils.named(new ItemStack(Items.LIME_STAINED_GLASS_PANE),
                    Text.literal("Farm-Punkte: " + w.totalFarmKills).formatted(Formatting.GREEN)));
            } else {
                inv.setStack(slot, MenuUtils.named(new ItemStack(Items.RED_STAINED_GLASS_PANE),
                    Text.literal("Battle-Punkte: " + w.battlePoints).formatted(Formatting.RED)));
            }
        }
    }

    // =========================================================================
    //  PAGE 1+: TEAM DETAILS
    // =========================================================================
    private void buildTeamPage(SimpleInventory inv, GameStats.TeamStats ts, int teamIdx) {
        // Row 0: Team header (slot 4)
        String[] medals = {"🥇", "🥈", "🥉"};
        String medal = teamIdx < medals.length ? medals[teamIdx] + " " : "#" + (teamIdx + 1) + " ";
        Item banner = MenuUtils.bannerFor(ts.color);
        inv.setStack(4, MenuUtils.glow(MenuUtils.named(new ItemStack(banner),
            Text.literal(medal + ts.name).formatted(ts.color, Formatting.BOLD),
            Text.literal("Platz " + (teamIdx + 1) + " von " + stats.ranking.size()).formatted(Formatting.GRAY),
            Text.literal(ts.memberCount + " Spieler").formatted(Formatting.GRAY)
        )));

        // Row 1: Stat cards
        // Total score (slot 10)
        inv.setStack(10, MenuUtils.named(new ItemStack(Items.EXPERIENCE_BOTTLE),
            Text.literal("Score: " + ts.score).formatted(Formatting.YELLOW, Formatting.BOLD),
            Text.literal("Farm: " + ts.totalFarmKills + " Punkte").formatted(Formatting.GREEN),
            Text.literal("Battle: " + ts.battlePoints + " Punkte").formatted(Formatting.RED)
        ));

        // Farm kills (slot 12)
        inv.setStack(12, MenuUtils.named(new ItemStack(Items.IRON_SWORD),
            Text.literal("🗡 Farm-Kills: " + ts.totalFarmKills).formatted(Formatting.RED, Formatting.BOLD),
            Text.literal(ts.uniqueMobTypes + " verschiedene Arten").formatted(Formatting.GRAY),
            ts.totalBabyKills > 0
                ? Text.literal(ts.totalBabyKills + " davon Babys").formatted(Formatting.LIGHT_PURPLE)
                : Text.literal("Keine Babys").formatted(Formatting.DARK_GRAY)
        ));

        // Waves survived (slot 14)
        inv.setStack(14, MenuUtils.named(new ItemStack(Items.SHIELD),
            Text.literal("⚔ Wellen: " + ts.wavesCleared + " überlebt").formatted(Formatting.AQUA, Formatting.BOLD),
            Text.literal(ts.battlePoints + " Battle-Punkte").formatted(Formatting.GRAY)
        ));

        // Diversity (slot 16)
        inv.setStack(16, MenuUtils.named(new ItemStack(Items.SPYGLASS),
            Text.literal("🌍 Artenvielfalt: " + ts.uniqueMobTypes).formatted(Formatting.GREEN, Formatting.BOLD),
            Text.literal("Verschiedene Mob-Arten").formatted(Formatting.GRAY)
        ));

        // Rows 2-4: Mob list (up to 21 mobs, 7 per row)
        for (int i = 0; i < ts.topMobs.size() && i < 21; i++) {
            GameStats.MobEntry mob = ts.topMobs.get(i);
            int row = 2 + i / 7;
            int col = 1 + i % 7;
            int slot = row * 9 + col;

            Item item = mobToItem(mob);
            List<Text> lore = new ArrayList<>();
            lore.add(Text.literal("×" + mob.count + " getötet").formatted(Formatting.YELLOW));
            if (mob.babyCount > 0) {
                lore.add(Text.literal(mob.babyCount + " Babys").formatted(Formatting.LIGHT_PURPLE));
            }

            ItemStack stack = new ItemStack(item);
            stack.set(DataComponentTypes.CUSTOM_NAME,
                Text.literal("").append(Text.literal(mob.name).formatted(Formatting.WHITE, Formatting.BOLD)).styled(s -> s.withItalic(false)));
            stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
            if (i == 0) stack = MenuUtils.glow(stack);
            inv.setStack(slot, stack);
        }
        // If more than 21 mobs, show a "..." indicator
        if (ts.topMobs.size() > 21) {
            inv.setStack(4 * 9 + 8, MenuUtils.named(new ItemStack(Items.PAPER),
                Text.literal("... und " + (ts.topMobs.size() - 21) + " weitere").formatted(Formatting.GRAY)));
        }
    }

    // =========================================================================
    //  NAVIGATION BAR (bottom row)
    // =========================================================================
    private void buildNavBar(SimpleInventory inv) {
        int bottomRow = (ROWS - 1) * 9;

        // Page indicator (center)
        inv.setStack(bottomRow + 4, MenuUtils.named(new ItemStack(Items.PAPER),
            Text.literal("Seite " + (page + 1) + "/" + totalPages()).formatted(Formatting.WHITE),
            page == 0
                ? Text.literal("Übersicht").formatted(Formatting.GOLD)
                : Text.literal("Team-Details").formatted(Formatting.AQUA)
        ));

        // Previous arrow (left)
        if (page > 0) {
            inv.setStack(bottomRow, MenuUtils.named(new ItemStack(Items.ARROW),
                Text.literal("◀ Zurück").formatted(Formatting.YELLOW, Formatting.BOLD),
                page == 1
                    ? Text.literal("Zur Übersicht").formatted(Formatting.GRAY)
                    : Text.literal("Zum vorherigen Team").formatted(Formatting.GRAY)
            ));
        }

        // Next arrow (right)
        if (page < totalPages() - 1) {
            inv.setStack(bottomRow + 8, MenuUtils.named(new ItemStack(Items.ARROW),
                Text.literal("Weiter ▶").formatted(Formatting.YELLOW, Formatting.BOLD),
                Text.literal("Zum nächsten Team").formatted(Formatting.GRAY)
            ));
        }

        // Close button
        inv.setStack(bottomRow + 6, MenuUtils.named(new ItemStack(Items.BARRIER),
            Text.literal("Schließen").formatted(Formatting.RED)));
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    /** Map mob type to a representative item for display. */
    private static Item mobToItem(GameStats.MobEntry mob) {
        String name = mob.name.toLowerCase();
        if (name.contains("zombie")) return Items.ROTTEN_FLESH;
        if (name.contains("skeleton")) return Items.BONE;
        if (name.contains("creeper")) return Items.GUNPOWDER;
        if (name.contains("spider")) return Items.STRING;
        if (name.contains("enderman")) return Items.ENDER_PEARL;
        if (name.contains("blaze")) return Items.BLAZE_ROD;
        if (name.contains("ghast")) return Items.GHAST_TEAR;
        if (name.contains("slime")) return Items.SLIME_BALL;
        if (name.contains("witch")) return Items.GLASS_BOTTLE;
        if (name.contains("guardian")) return Items.PRISMARINE_SHARD;
        if (name.contains("phantom")) return Items.PHANTOM_MEMBRANE;
        if (name.contains("drowned")) return Items.TRIDENT;
        if (name.contains("pillager")) return Items.CROSSBOW;
        if (name.contains("vindicator")) return Items.IRON_AXE;
        if (name.contains("evoker")) return Items.TOTEM_OF_UNDYING;
        if (name.contains("ravager")) return Items.SADDLE;
        if (name.contains("wither")) return Items.WITHER_SKELETON_SKULL;
        if (name.contains("piglin")) return Items.GOLD_INGOT;
        if (name.contains("hoglin")) return Items.COOKED_PORKCHOP;
        if (name.contains("zoglin")) return Items.ROTTEN_FLESH;
        if (name.contains("warden")) return Items.SCULK_CATALYST;
        if (name.contains("iron_golem") || name.contains("iron golem")) return Items.IRON_BLOCK;
        if (name.contains("wolf")) return Items.BONE;
        if (name.contains("bee")) return Items.HONEYCOMB;
        if (name.contains("llama")) return Items.LEATHER;
        if (name.contains("fox")) return Items.SWEET_BERRIES;
        if (name.contains("dolphin")) return Items.COD;
        if (name.contains("panda")) return Items.BAMBOO;
        if (name.contains("cow")) return Items.LEATHER;
        if (name.contains("pig")) return Items.PORKCHOP;
        if (name.contains("sheep")) return Items.WHITE_WOOL;
        if (name.contains("chicken")) return Items.FEATHER;
        if (name.contains("rabbit")) return Items.RABBIT_FOOT;
        if (name.contains("squid")) return Items.INK_SAC;
        if (name.contains("cat") || name.contains("ocelot")) return Items.STRING;
        if (name.contains("horse")) return Items.SADDLE;
        if (name.contains("donkey") || name.contains("mule")) return Items.CHEST;
        if (name.contains("turtle")) return Items.TURTLE_SCUTE;
        if (name.contains("frog")) return Items.SLIME_BALL;
        if (name.contains("axolotl")) return Items.TROPICAL_FISH;
        if (name.contains("goat")) return Items.GOAT_HORN;
        if (name.contains("camel")) return Items.CACTUS;
        if (name.contains("sniffer")) return Items.TORCHFLOWER_SEEDS;
        if (name.contains("breeze")) return Items.WIND_CHARGE;
        if (name.contains("silverfish")) return Items.STONE;
        if (name.contains("endermite")) return Items.ENDER_EYE;
        if (name.contains("magma")) return Items.MAGMA_CREAM;
        if (name.contains("shulker")) return Items.SHULKER_SHELL;
        if (name.contains("stray")) return Items.TIPPED_ARROW;
        if (name.contains("husk")) return Items.SAND;
        if (name.contains("vex")) return Items.IRON_SWORD;
        return Items.SPAWNER;
    }
}
