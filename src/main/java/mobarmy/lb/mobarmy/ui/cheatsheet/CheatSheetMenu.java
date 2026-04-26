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
 * Paginated chest-GUI Cheat Sheet showing every block→item mapping the
 * player's scope has discovered so far. Scoped per team / player / global
 * depending on the randomizer mode.
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
    private final List<Map.Entry<Block, Item>> entries;
    private final String scopeLabel;

    public CheatSheetMenu(MobarmyMod mod, ServerPlayerEntity viewer) {
        this.mod = mod;
        String key = mod.randomizerManager.scopeKey(viewer);
        Map<Block, Item> disc = mod.randomizerManager.getDiscovered(key);
        this.entries = new ArrayList<>(disc.entrySet());
        entries.sort(Comparator.comparing(e -> Registries.BLOCK.getId(e.getKey()).toString()));

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
        final int[] page = {0};
        rebuild(inv, page[0]);

        return new GenericContainerScreenHandler(ScreenHandlerType.GENERIC_9X6, syncId, pi, inv, 6) {
            @Override
            public void onSlotClick(int slot, int button, SlotActionType action, PlayerEntity who) {
                if (slot < 0 || slot >= 54) { super.onSlotClick(slot, button, action, who); return; }
                if (!(who instanceof ServerPlayerEntity sp)) return;
                switch (slot) {
                    case 46 -> { sp.closeHandledScreen(); return; }
                    case 48 -> { if (page[0] > 0) page[0]--; }
                    case 50 -> { if ((page[0] + 1) * PER_PAGE < entries.size()) page[0]++; }
                    default -> { return; }
                }
                rebuild(inv, page[0]);
                this.sendContentUpdates();
            }
            @Override public ItemStack quickMove(PlayerEntity p, int s) { return ItemStack.EMPTY; }
        };
    }

    // ========================================================================

    private void rebuild(SimpleInventory inv, int page) {
        for (int i = 0; i < inv.size(); i++) inv.setStack(i, ItemStack.EMPTY);
        MenuUtils.drawBorder(inv, 6);

        int totalPages = Math.max(1, (entries.size() + PER_PAGE - 1) / PER_PAGE);

        // ── Header ──────────────────────────────────────────────────────
        inv.setStack(4, MenuUtils.named(new ItemStack(Items.KNOWLEDGE_BOOK),
            Text.literal("📖 Cheat Sheet").formatted(Formatting.GOLD, Formatting.BOLD),
            Text.literal("Modus: ").formatted(Formatting.GRAY)
                .append(Text.literal(scopeLabel).formatted(Formatting.AQUA)),
            Text.literal("Entdeckt: ").formatted(Formatting.GRAY)
                .append(Text.literal(entries.size() + " Blöcke").formatted(Formatting.WHITE)),
            Text.literal("Seite " + (page + 1) + "/" + totalPages).formatted(Formatting.DARK_GRAY)
        ));

        // ── Content ─────────────────────────────────────────────────────
        if (entries.isEmpty()) {
            inv.setStack(22, MenuUtils.named(new ItemStack(Items.BARRIER),
                Text.literal("Noch nichts entdeckt").formatted(Formatting.GRAY),
                Text.literal("Baue Blöcke ab, um ihre").formatted(Formatting.DARK_GRAY),
                Text.literal("Drops hier zu sehen.").formatted(Formatting.DARK_GRAY)
            ));
        } else {
            int start = page * PER_PAGE;
            for (int i = 0; i < PER_PAGE && (start + i) < entries.size(); i++) {
                Map.Entry<Block, Item> entry = entries.get(start + i);
                Block block = entry.getKey();
                Item drop = entry.getValue();

                ItemStack icon = new ItemStack(block.asItem());
                if (icon.isEmpty()) icon = new ItemStack(Items.BARRIER);
                icon = MenuUtils.glow(icon);

                Identifier blockId = Registries.BLOCK.getId(block);
                Identifier dropId = Registries.ITEM.getId(drop);
                String dropName = capitalize(dropId.getPath().replace('_', ' '));

                icon.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    Text.empty(),
                    Text.literal("  ⬇ Dropt:").formatted(Formatting.GRAY),
                    Text.literal("  ").append(
                        Text.literal(dropName).formatted(Formatting.GOLD, Formatting.BOLD)),
                    Text.empty(),
                    Text.literal("  " + blockId).formatted(Formatting.DARK_GRAY)
                )));

                inv.setStack(CONTENT_SLOTS[i], icon);
            }
        }

        // ── Navigation ──────────────────────────────────────────────────
        inv.setStack(46, MenuUtils.button(Items.ARROW,
            Text.literal("← Schließen").formatted(Formatting.GRAY)));

        if (page > 0) {
            inv.setStack(48, MenuUtils.button(Items.ARROW,
                Text.literal("◀ Vorherige Seite").formatted(Formatting.YELLOW)));
        }

        inv.setStack(49, MenuUtils.named(new ItemStack(Items.PAPER),
            Text.literal("Seite " + (page + 1) + "/" + totalPages).formatted(Formatting.WHITE),
            Text.literal(entries.size() + " Einträge gesamt").formatted(Formatting.GRAY)
        ));

        if ((page + 1) * PER_PAGE < entries.size()) {
            inv.setStack(50, MenuUtils.button(Items.ARROW,
                Text.literal("Nächste Seite ▶").formatted(Formatting.YELLOW)));
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
