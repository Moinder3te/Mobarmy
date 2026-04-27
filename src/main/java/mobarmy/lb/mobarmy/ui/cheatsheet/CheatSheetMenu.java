package mobarmy.lb.mobarmy.ui.cheatsheet;

import mobarmy.lb.mobarmy.MobarmyMod;
import mobarmy.lb.mobarmy.config.RandomizerMode;
import mobarmy.lb.mobarmy.ui.lobby.MenuUtils;
import net.minecraft.block.Block;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.*;

/**
 * Paginated chest-GUI Cheat Sheet with three tabs:
 *   ⛏ Block-Drops  — every block→item mapping discovered by mining.
 *   📦 Chest-Loot   — every item→item mapping discovered from chest loot.
 *   🐄 Mob-Drops    — every item→item mapping discovered from mob kills.
 * Toggle via slot 8 button.  Scoped per team / player / global.
 */
public class CheatSheetMenu implements NamedScreenHandlerFactory {

    /** Content slot indices (rows 1-4, columns 1-7). */
    private static final int[] CONTENT_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };
    private static final int PER_PAGE = CONTENT_SLOTS.length; // 28

    private final MobarmyMod mod;
    private final ServerPlayerEntity viewer;
    private final String scopeKey;
    private final String scopeLabel;

    public CheatSheetMenu(MobarmyMod mod, ServerPlayerEntity viewer) {
        this.mod = mod;
        this.viewer = viewer;
        this.scopeKey = mod.randomizerManager.scopeKey(viewer);

        RandomizerMode mode = mod.randomizerManager.mode();
        this.scopeLabel = switch (mode) {
            case GLOBAL -> "Global";
            case PER_TEAM -> {
                var team = mod.teams.get(viewer);
                yield team != null ? "Team " + team.name : "Global";
            }
            case PER_PLAYER -> viewer.getName().getString();
        };
    }

    public static void open(ServerPlayerEntity p, MobarmyMod mod) {
        p.openHandledScreen(new CheatSheetMenu(mod, p));
    }

    @Override
    public Text getDisplayName() {
        return Text.literal("» Cheat Sheet «").formatted(Formatting.GOLD, Formatting.BOLD);
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory pi, PlayerEntity p) {
        SimpleInventory inv = new SimpleInventory(54);
        // [0] = page, [1] = tab (0=blocks, 1=chest)
        final int[] state = {0, 0};
        rebuild(inv, state[0], state[1]);

        return new GenericContainerScreenHandler(ScreenHandlerType.GENERIC_9X6, syncId, pi, inv, 6) {
            @Override
            public void onSlotClick(int slot, int button, SlotActionType action, PlayerEntity who) {
                if (slot < 0 || slot >= 54) { super.onSlotClick(slot, button, action, who); return; }
                if (!(who instanceof ServerPlayerEntity sp)) return;
                switch (slot) {
                    case 46 -> { sp.closeHandledScreen(); return; }
                    case 48 -> { if (state[0] > 0) state[0]--; }
                    case 50 -> { if ((state[0] + 1) * PER_PAGE < entryCount(state[1])) state[0]++; }
                    case 8  -> { state[1] = (state[1] + 1) % 3; state[0] = 0; } // cycle tabs
                    default -> { return; }
                }
                rebuild(inv, state[0], state[1]);
                this.sendContentUpdates();
            }
            @Override public ItemStack quickMove(PlayerEntity p, int s) { return ItemStack.EMPTY; }
        };
    }

    private int entryCount(int tab) {
        return switch (tab) {
            case 0 -> mod.randomizerManager.getDiscovered(scopeKey).size();
            case 1 -> mod.randomizerManager.getItemDiscovered(scopeKey).size();
            case 2 -> mod.randomizerManager.getMobDiscovered(scopeKey).size();
            default -> 0;
        };
    }

    // ========================================================================

    private void rebuild(SimpleInventory inv, int page, int tab) {
        for (int i = 0; i < inv.size(); i++) inv.setStack(i, ItemStack.EMPTY);
        MenuUtils.drawBorder(inv, 6);

        boolean isBlocks = tab == 0;
        boolean isChest = tab == 1;
        boolean isMob = tab == 2;
        int total = entryCount(tab);
        int totalPages = Math.max(1, (total + PER_PAGE - 1) / PER_PAGE);

        // ── Tab toggle (slot 8) — shows NEXT tab name ───────────────────
        String nextTabLabel = switch (tab) {
            case 0 -> "📦 Chest-Loot";
            case 1 -> "🐄 Mob-Drops";
            default -> "⛏ Block-Drops";
        };
        net.minecraft.item.Item nextIcon = switch (tab) {
            case 0 -> Items.CHEST;
            case 1 -> Items.BEEF;
            default -> Items.DIAMOND_PICKAXE;
        };
        Formatting nextColor = switch (tab) {
            case 0 -> Formatting.YELLOW;
            case 1 -> Formatting.RED;
            default -> Formatting.AQUA;
        };
        inv.setStack(8, MenuUtils.glow(MenuUtils.button(nextIcon,
            Text.literal(nextTabLabel).formatted(nextColor, Formatting.BOLD),
            Text.literal("Klick: Nächster Tab").formatted(Formatting.GRAY)
        )));

        // ── Header ──────────────────────────────────────────────────────
        String tabLabel = switch (tab) {
            case 0 -> "⛏ Block-Drops";
            case 1 -> "📦 Chest-Loot";
            default -> "🐄 Mob-Drops";
        };
        String countLabel = isBlocks ? total + " Blöcke" : total + " Items";
        inv.setStack(4, MenuUtils.named(new ItemStack(Items.KNOWLEDGE_BOOK),
            Text.literal("📖 " + tabLabel).formatted(Formatting.GOLD, Formatting.BOLD),
            Text.literal("Modus: ").formatted(Formatting.GRAY)
                .append(Text.literal(scopeLabel).formatted(Formatting.AQUA)),
            Text.literal("Entdeckt: ").formatted(Formatting.GRAY)
                .append(Text.literal(countLabel).formatted(Formatting.WHITE)),
            Text.literal("Seite " + (page + 1) + "/" + totalPages).formatted(Formatting.DARK_GRAY)
        ));

        // ── Content ─────────────────────────────────────────────────────
        if (total == 0) {
            String hint1 = switch (tab) {
                case 0 -> "Baue Blöcke ab, um ihre";
                case 1 -> "Öffne Truhen, um ihre";
                default -> "Töte Mobs, um ihre";
            };
            String hint2 = switch (tab) {
                case 0 -> "Drops hier zu sehen.";
                case 1 -> "Loot-Swaps hier zu sehen.";
                default -> "Drops hier zu sehen.";
            };
            inv.setStack(22, MenuUtils.named(new ItemStack(Items.BARRIER),
                Text.literal("Noch nichts entdeckt").formatted(Formatting.GRAY),
                Text.literal(hint1).formatted(Formatting.DARK_GRAY),
                Text.literal(hint2).formatted(Formatting.DARK_GRAY)
            ));
        } else if (isBlocks) {
            renderBlockEntries(inv, page);
        } else if (isChest) {
            renderItemEntries(inv, page, mod.randomizerManager.getItemDiscovered(scopeKey), "⬆ Statt:");
        } else {
            renderItemEntries(inv, page, mod.randomizerManager.getMobDiscovered(scopeKey), "⬆ Statt:");
        }

        MenuUtils.fillEmpty(inv);

        // ── Navigation ──────────────────────────────────────────────────
        inv.setStack(46, MenuUtils.button(Items.ARROW,
            Text.literal("← Schließen").formatted(Formatting.GRAY)));

        if (page > 0) {
            inv.setStack(48, MenuUtils.button(Items.ARROW,
                Text.literal("◀ Vorherige Seite").formatted(Formatting.YELLOW)));
        }

        inv.setStack(49, MenuUtils.named(new ItemStack(Items.PAPER),
            Text.literal("Seite " + (page + 1) + "/" + totalPages).formatted(Formatting.WHITE),
            Text.literal(total + " Einträge gesamt").formatted(Formatting.GRAY)
        ));

        if ((page + 1) * PER_PAGE < total) {
            inv.setStack(50, MenuUtils.button(Items.ARROW,
                Text.literal("Nächste Seite ▶").formatted(Formatting.YELLOW)));
        }
    }

    private void renderBlockEntries(SimpleInventory inv, int page) {
        Map<Block, Item> disc = mod.randomizerManager.getDiscovered(scopeKey);
        List<Map.Entry<Block, Item>> entries = new ArrayList<>(disc.entrySet());
        entries.sort(Comparator.comparing(e -> Registries.ITEM.getId(e.getValue()).toString()));

        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && (start + i) < entries.size(); i++) {
            Map.Entry<Block, Item> entry = entries.get(start + i);
            Block block = entry.getKey();
            Item drop = entry.getValue();

            ItemStack icon = new ItemStack(drop);
            if (icon.isEmpty()) icon = new ItemStack(Items.BARRIER);
            icon = MenuUtils.glow(icon);

            Identifier blockId = Registries.BLOCK.getId(block);
            String blockName = capitalize(blockId.getPath().replace('_', ' '));

            icon.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.empty(),
                Text.literal("  ⬆ Quelle:").formatted(Formatting.GRAY),
                Text.literal("  ").append(
                    Text.literal(blockName).formatted(Formatting.AQUA, Formatting.BOLD)),
                Text.empty(),
                Text.literal("  " + blockId).formatted(Formatting.DARK_GRAY)
            )));

            inv.setStack(CONTENT_SLOTS[i], icon);
        }
    }

    private void renderItemEntries(SimpleInventory inv, int page, Map<Item, Item> disc, String sourceLabel) {
        List<Map.Entry<Item, Item>> entries = new ArrayList<>(disc.entrySet());
        entries.sort(Comparator.comparing(e -> Registries.ITEM.getId(e.getValue()).toString()));

        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && (start + i) < entries.size(); i++) {
            Map.Entry<Item, Item> entry = entries.get(start + i);
            Item original = entry.getKey();
            Item mapped = entry.getValue();

            ItemStack icon = new ItemStack(mapped);
            if (icon.isEmpty()) icon = new ItemStack(Items.BARRIER);
            icon = MenuUtils.glow(icon);

            Identifier origId = Registries.ITEM.getId(original);
            String origName = capitalize(origId.getPath().replace('_', ' '));

            icon.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.empty(),
                Text.literal("  " + sourceLabel).formatted(Formatting.GRAY),
                Text.literal("  ").append(
                    Text.literal(origName).formatted(Formatting.YELLOW, Formatting.BOLD)),
                Text.empty(),
                Text.literal("  " + origId).formatted(Formatting.DARK_GRAY)
            )));

            inv.setStack(CONTENT_SLOTS[i], icon);
        }
    }

    // ========================================================================

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder();
        boolean cap = true;
        for (char c : s.toCharArray()) {
            if (c == ' ') { sb.append(c); cap = true; }
            else if (cap) { sb.append(Character.toUpperCase(c)); cap = false; }
            else sb.append(c);
        }
        return sb.toString();
    }
}
