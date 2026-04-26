package mobarmy.lb.mobarmy.ui.lobby;

import mobarmy.lb.mobarmy.MobarmyMod;
import mobarmy.lb.mobarmy.team.Team;
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
 * MAIN HUB (3 rows). Big buttons:
 *   [TEAMS] [SETTINGS] [START]
 *   plus status header and stop/close at bottom.
 */
public class LobbyHub implements NamedScreenHandlerFactory {

    public static void open(ServerPlayerEntity p, MobarmyMod mod) {
        p.openHandledScreen(new LobbyHub(mod));
    }

    private final MobarmyMod mod;
    public LobbyHub(MobarmyMod mod) { this.mod = mod; }

    @Override
    public Text getDisplayName() {
        return Text.literal("» Mobarmy «").formatted(Formatting.GOLD, Formatting.BOLD);
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory pi, PlayerEntity p) {
        SimpleInventory inv = new SimpleInventory(27);
        rebuild(inv);

        return new GenericContainerScreenHandler(ScreenHandlerType.GENERIC_9X3, syncId, pi, inv, 3) {
            @Override
            public void onSlotClick(int slot, int button, SlotActionType action, PlayerEntity who) {
                if (slot < 0 || slot >= 27) { super.onSlotClick(slot, button, action, who); return; }
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
            case 11 -> p.openHandledScreen(new TeamsMenu(mod));     // Teams
            case 13 -> {                                              // Start
                p.closeHandledScreen();
                mod.gameManager.startGame(mod.server);
            }
            case 15 -> p.openHandledScreen(new SettingsMenu(mod));   // Settings
            case 22 -> {                                              // Stop / Reset
                mod.gameManager.resetToLobby(mod.server);
                p.closeHandledScreen();
            }
            default -> {}
        }
    }

    private void rebuild(SimpleInventory inv) {
        // Border
        MenuUtils.drawBorder(inv, 3);

        // Status header (slot 4 = top center)
        int teams = mod.teams.size();
        int playersOnline = mod.server == null ? 0 : mod.server.getPlayerManager().getPlayerList().size();
        int playersInTeams = 0;
        for (Team t : mod.teams.all()) playersInTeams += t.members.size();
        inv.setStack(4, MenuUtils.named(new ItemStack(Items.NETHER_STAR),
            Text.literal("Mobarmy").formatted(Formatting.GOLD, Formatting.BOLD),
            Text.literal("Phase: ").formatted(Formatting.GRAY)
                .append(Text.literal(mod.gameManager.phase().name()).formatted(Formatting.AQUA)),
            Text.literal("Teams: ").formatted(Formatting.GRAY)
                .append(Text.literal(String.valueOf(teams)).formatted(Formatting.WHITE)),
            Text.literal("Spieler online: ").formatted(Formatting.GRAY)
                .append(Text.literal(String.valueOf(playersOnline)).formatted(Formatting.WHITE))
                .append(Text.literal(" (" + playersInTeams + " im Team)").formatted(Formatting.DARK_GRAY)),
            Text.literal("Randomizer: ").formatted(Formatting.GRAY)
                .append(Text.literal(mod.config.randomizerMode.displayName).formatted(Formatting.AQUA))
        ));

        // 3 main buttons (centered: 11, 13, 15)
        inv.setStack(11, MenuUtils.button(Items.WHITE_BANNER,
            Text.literal("Teams verwalten").formatted(Formatting.AQUA, Formatting.BOLD),
            Text.literal("Teams erstellen, löschen,").formatted(Formatting.GRAY),
            Text.literal("Spieler zuweisen").formatted(Formatting.GRAY)
        ));

        ItemStack startBtn;
        boolean canStart = teams >= 2;
        if (canStart) {
            startBtn = MenuUtils.glow(MenuUtils.button(Items.LIME_CONCRETE,
                Text.literal("▶ SPIEL STARTEN").formatted(Formatting.GREEN, Formatting.BOLD),
                Text.literal("Klicken um zu beginnen").formatted(Formatting.GREEN)
            ));
        } else {
            startBtn = MenuUtils.button(Items.GRAY_CONCRETE,
                Text.literal("▶ Spiel starten").formatted(Formatting.DARK_GRAY),
                Text.literal("Mindestens 2 Teams nötig").formatted(Formatting.RED)
            );
        }
        inv.setStack(13, startBtn);

        inv.setStack(15, MenuUtils.button(Items.CLOCK,
            Text.literal("Einstellungen").formatted(Formatting.YELLOW, Formatting.BOLD),
            Text.literal("Farm-Zeit, Anordnungs-Zeit,").formatted(Formatting.GRAY),
            Text.literal("Respawn-Verzögerung").formatted(Formatting.GRAY)
        ));

        // Bottom controls
        inv.setStack(22, MenuUtils.button(Items.BARRIER,
            Text.literal("⏹ Stop / Reset").formatted(Formatting.RED, Formatting.BOLD),
            Text.literal("Bricht laufendes Spiel ab").formatted(Formatting.GRAY)
        ));
    }
}

