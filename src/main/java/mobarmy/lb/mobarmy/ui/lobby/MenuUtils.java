package mobarmy.lb.mobarmy.ui.lobby;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/** Shared helpers + visual styling for all lobby menus. */
public final class MenuUtils {
    private MenuUtils() {}

    public static final ItemStack BORDER = named(new ItemStack(Items.GRAY_STAINED_GLASS_PANE), Text.literal(" "));
    public static final ItemStack ACCENT = named(new ItemStack(Items.LIGHT_BLUE_STAINED_GLASS_PANE), Text.literal(" "));

    public static ItemStack named(ItemStack stack, Text name, Text... lore) {
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("").append(name).styled(s -> s.withItalic(false)));
        if (lore.length > 0) {
            stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(lore)));
        }
        return stack;
    }

    public static ItemStack button(Item item, Text name, Text... lore) {
        return named(new ItemStack(item), name, lore);
    }

    public static ItemStack glow(ItemStack stack) {
        stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    /** Fills the inventory with a clean border (top + bottom + left + right cols) using GRAY glass. */
    public static void drawBorder(SimpleInventory inv, int rows) {
        int cols = 9;
        for (int c = 0; c < cols; c++) {
            inv.setStack(c, BORDER.copy());                   // top row
            inv.setStack((rows - 1) * cols + c, BORDER.copy()); // bottom row
        }
        for (int r = 1; r < rows - 1; r++) {
            inv.setStack(r * cols, BORDER.copy());            // left col
            inv.setStack(r * cols + 8, BORDER.copy());        // right col
        }
    }

    /** Fills empty slots with a soft accent so the menu looks finished. */
    public static void fillEmpty(SimpleInventory inv) {
        ItemStack air = ItemStack.EMPTY;
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isEmpty()) inv.setStack(i, ACCENT.copy());
        }
    }

    public static String formatTime(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        if (m > 0) return String.format("%d:%02d min", m, s);
        return seconds + "s";
    }

    public static Item woolFor(Formatting f) {
        return switch (f) {
            case RED, DARK_RED -> Items.RED_WOOL;
            case BLUE, DARK_BLUE -> Items.BLUE_WOOL;
            case GREEN, DARK_GREEN -> Items.GREEN_WOOL;
            case YELLOW -> Items.YELLOW_WOOL;
            case AQUA -> Items.LIGHT_BLUE_WOOL;
            case DARK_AQUA -> Items.CYAN_WOOL;
            case LIGHT_PURPLE -> Items.MAGENTA_WOOL;
            case DARK_PURPLE -> Items.PURPLE_WOOL;
            case GOLD -> Items.ORANGE_WOOL;
            case BLACK -> Items.BLACK_WOOL;
            case GRAY, DARK_GRAY -> Items.GRAY_WOOL;
            default -> Items.WHITE_WOOL;
        };
    }

    public static Item bannerFor(Formatting f) {
        return switch (f) {
            case RED, DARK_RED -> Items.RED_BANNER;
            case BLUE, DARK_BLUE -> Items.BLUE_BANNER;
            case GREEN, DARK_GREEN -> Items.GREEN_BANNER;
            case YELLOW -> Items.YELLOW_BANNER;
            case AQUA -> Items.LIGHT_BLUE_BANNER;
            case DARK_AQUA -> Items.CYAN_BANNER;
            case LIGHT_PURPLE -> Items.MAGENTA_BANNER;
            case DARK_PURPLE -> Items.PURPLE_BANNER;
            case GOLD -> Items.ORANGE_BANNER;
            case BLACK -> Items.BLACK_BANNER;
            case GRAY, DARK_GRAY -> Items.GRAY_BANNER;
            default -> Items.WHITE_BANNER;
        };
    }

    public static Formatting nextColor(int index) {
        Formatting[] palette = {
            Formatting.RED, Formatting.BLUE, Formatting.GREEN, Formatting.YELLOW,
            Formatting.AQUA, Formatting.LIGHT_PURPLE, Formatting.GOLD, Formatting.DARK_PURPLE
        };
        return palette[index % palette.length];
    }
}

