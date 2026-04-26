package mobarmy.lb.mobarmy.ui.lobby;

import mobarmy.lb.mobarmy.MobarmyMod;
import mobarmy.lb.mobarmy.config.RandomizerMode;
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
 * SETTINGS (6 rows). Three time sliders + randomizer mode selector.
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
        SimpleInventory inv = new SimpleInventory(54);
        rebuild(inv);

        return new GenericContainerScreenHandler(ScreenHandlerType.GENERIC_9X6, syncId, pi, inv, 6) {
            @Override
            public void onSlotClick(int slot, int button, SlotActionType action, PlayerEntity who) {
                if (slot < 0 || slot >= 54) { super.onSlotClick(slot, button, action, who); return; }
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
            // Wave Count row 4: slots 38..42
            case 38 -> changeWaveCount(-3, p);
            case 39 -> changeWaveCount(-1, p);
            case 41 -> changeWaveCount(+1, p);
            case 42 -> changeWaveCount(+3, p);
            // Max Alive Mobs row 5: slots 47..51
            case 47 -> changeMaxAlive(-20, p);
            case 48 -> changeMaxAlive(-5, p);
            case 50 -> changeMaxAlive(+5, p);
            case 51 -> changeMaxAlive(+20, p);

            // Randomizer mode toggle (header)
            case 7 -> cycleMode(+1, p);

            case 53 -> LobbyHub.open(p, mod); // back
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
    private void changeWaveCount(int d, ServerPlayerEntity p) {
        mod.config.waveCount = Math.max(1, Math.min(10, mod.config.waveCount + d));
        mod.config.save(mod.server);
    }
    private void changeMaxAlive(int d, ServerPlayerEntity p) {
        mod.config.maxAliveMobs = Math.max(5, Math.min(200, mod.config.maxAliveMobs + d));
        mod.config.save(mod.server);
    }
    private void cycleMode(int dir, ServerPlayerEntity p) {
        RandomizerMode cur = mod.config.randomizerMode;
        mod.config.randomizerMode = dir > 0 ? cur.next() : cur.prev();
        mod.config.save(mod.server);
    }

    private void rebuild(SimpleInventory inv) {
        MenuUtils.drawBorder(inv, 6);

        // Header
        inv.setStack(4, MenuUtils.named(new ItemStack(Items.CLOCK),
            Text.literal("Einstellungen").formatted(Formatting.YELLOW, Formatting.BOLD),
            Text.literal("Stelle die Spielparameter ein").formatted(Formatting.GRAY)
        ));

        // Randomizer mode toggle (header row, slot 7)
        RandomizerMode mode = mod.config.randomizerMode;
        net.minecraft.item.Item modeIcon = switch (mode) {
            case GLOBAL -> Items.ENDER_PEARL;
            case PER_TEAM -> Items.WHITE_BANNER;
            case PER_PLAYER -> Items.PLAYER_HEAD;
        };
        Formatting modeColor = switch (mode) {
            case GLOBAL -> Formatting.GREEN;
            case PER_TEAM -> Formatting.YELLOW;
            case PER_PLAYER -> Formatting.RED;
        };
        inv.setStack(7, MenuUtils.glow(MenuUtils.named(new ItemStack(modeIcon),
            Text.literal("Randomizer: " + mode.displayName).formatted(modeColor, Formatting.BOLD),
            Text.literal(mode.description).formatted(Formatting.GRAY),
            Text.literal("Klicke zum Wechseln").formatted(Formatting.DARK_GRAY)
        )));

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

        // Row 4: Wave Count
        slider(inv, 36,
            Items.SPAWNER,
            Text.literal("Wellen-Anzahl").formatted(Formatting.RED, Formatting.BOLD),
            "−3", "−1", "+1", "+3",
            String.valueOf(mod.config.waveCount),
            Formatting.RED);

        // Row 5: Max Alive Mobs
        slider(inv, 45,
            Items.IRON_SWORD,
            Text.literal("Max. Mobs gleichzeitig").formatted(Formatting.AQUA, Formatting.BOLD),
            "−20", "−5", "+5", "+20",
            String.valueOf(mod.config.maxAliveMobs),
            Formatting.AQUA);

        // Back button (slot 53, far right of row 5)
        inv.setStack(53, MenuUtils.button(Items.ARROW,
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

