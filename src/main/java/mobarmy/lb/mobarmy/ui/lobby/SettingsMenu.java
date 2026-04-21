package mobarmy.lb.mobarmy.ui.lobby;

import mobarmy.lb.mobarmy.MobarmyMod;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
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

/**
 * SETTINGS (5 rows). Three sliders: farm, arrange, respawn.
 *  Layout per row:  [icon] [-big] [-small] [VALUE] [+small] [+big] [   ]
 */
public class SettingsMenu implements NamedScreenHandlerFactory {

    private final MobarmyMod mod;
    public SettingsMenu(MobarmyMod mod) { this.mod = mod; }

    @Override
    public Text getDisplayName() {
        return Text.literal("» Einstellungen «").formatted(Formatting.YELLOW, Formatting.BOLD);
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory pi, PlayerEntity p) {
        SimpleInventory inv = new SimpleInventory(45);
        rebuild(inv);

        return new GenericContainerScreenHandler(ScreenHandlerType.GENERIC_9X5, syncId, pi, inv, 5) {
            @Override
            public void onSlotClick(int slot, int button, SlotActionType action, PlayerEntity who) {
                if (slot < 0 || slot >= 45) { super.onSlotClick(slot, button, action, who); return; }
                if (!(who instanceof ServerPlayerEntity sp)) return;
                handle(slot, sp);
                rebuild(inv);
                this.sendContentUpdates();
            }
            @Override public ItemStack quickMove(PlayerEntity p, int s) { return ItemStack.EMPTY; }
        };
    }

    private void handle(int slot, ServerPlayerEntity p) {
        switch (slot) {
            // Farm row 1: slots 10..15
            case 11 -> changeFarm(-60, p);
            case 12 -> changeFarm(-10, p);
            case 14 -> changeFarm(+10, p);
            case 15 -> changeFarm(+60, p);
            // Arrange row 2: slots 19..24
            case 20 -> changeArrange(-30, p);
            case 21 -> changeArrange(-10, p);
            case 23 -> changeArrange(+10, p);
            case 24 -> changeArrange(+30, p);
            // Respawn row 3: slots 28..33
            case 29 -> changeRespawn(-5, p);
            case 30 -> changeRespawn(-1, p);
            case 32 -> changeRespawn(+1, p);
            case 33 -> changeRespawn(+5, p);

            case 36 -> LobbyHub.open(p, mod); // back
            default -> {}
        }
    }

    private void changeFarm(int d, ServerPlayerEntity p) {
        mod.config.farmDurationSeconds = Math.max(30, mod.config.farmDurationSeconds + d);
        mod.config.save(mod.server);
    }
    private void changeArrange(int d, ServerPlayerEntity p) {
        mod.config.arrangeDurationSeconds = Math.max(15, mod.config.arrangeDurationSeconds + d);
        mod.config.save(mod.server);
    }
    private void changeRespawn(int s, ServerPlayerEntity p) {
        int cur = mod.config.respawnDelayTicks / 20;
        cur = Math.max(3, cur + s);
        mod.config.respawnDelayTicks = cur * 20;
        mod.config.save(mod.server);
    }

    private void rebuild(SimpleInventory inv) {
        MenuUtils.drawBorder(inv, 5);

        // Header
        inv.setStack(4, MenuUtils.named(new ItemStack(Items.CLOCK),
            Text.literal("Einstellungen").formatted(Formatting.YELLOW, Formatting.BOLD),
            Text.literal("Stelle die Phasen-Zeiten ein").formatted(Formatting.GRAY)
        ));

        // Row 1: Farm
        slider(inv, 9,
            Items.WHEAT,
            Text.literal("Farm-Phase").formatted(Formatting.GREEN, Formatting.BOLD),
            "−1m", "−10s", "+10s", "+1m",
            MenuUtils.formatTime(mod.config.farmDurationSeconds),
            Formatting.GREEN);

        // Row 2: Arrange
        slider(inv, 18,
            Items.PAINTING,
            Text.literal("Anordnungs-Phase").formatted(Formatting.YELLOW, Formatting.BOLD),
            "−30s", "−10s", "+10s", "+30s",
            MenuUtils.formatTime(mod.config.arrangeDurationSeconds),
            Formatting.YELLOW);

        // Row 3: Respawn
        slider(inv, 27,
            Items.SKELETON_SKULL,
            Text.literal("Respawn-Verzögerung").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD),
            "−5s", "−1s", "+1s", "+5s",
            (mod.config.respawnDelayTicks / 20) + "s",
            Formatting.LIGHT_PURPLE);

        // Back
        inv.setStack(36, MenuUtils.button(Items.ARROW,
            Text.literal("← Zurück").formatted(Formatting.GRAY)));
    }

    private void slider(SimpleInventory inv, int rowStart, net.minecraft.item.Item icon,
                         Text title, String bigDown, String smallDown, String smallUp, String bigUp,
                         String value, Formatting valueColor) {
        inv.setStack(rowStart + 1, MenuUtils.named(new ItemStack(icon), title));
        inv.setStack(rowStart + 2, MenuUtils.button(Items.RED_CONCRETE,
            Text.literal(bigDown).formatted(Formatting.RED, Formatting.BOLD)));
        inv.setStack(rowStart + 3, MenuUtils.button(Items.RED_STAINED_GLASS,
            Text.literal(smallDown).formatted(Formatting.RED)));
        inv.setStack(rowStart + 4, MenuUtils.glow(MenuUtils.named(new ItemStack(Items.CLOCK),
            Text.literal(value).formatted(valueColor, Formatting.BOLD),
            Text.literal("aktueller Wert").formatted(Formatting.GRAY))));
        inv.setStack(rowStart + 5, MenuUtils.button(Items.LIME_STAINED_GLASS,
            Text.literal(smallUp).formatted(Formatting.GREEN)));
        inv.setStack(rowStart + 6, MenuUtils.button(Items.LIME_CONCRETE,
            Text.literal(bigUp).formatted(Formatting.GREEN, Formatting.BOLD)));
    }
}

