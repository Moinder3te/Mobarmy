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

import java.util.ArrayList;
import java.util.List;

/**
 * TEAMS LIST (5 rows). Shows all teams as banners.
 *  - Left-click → open TeamEditMenu for that team
 *  - Right-click → delete team (with confirmation via second click)
 *  - Slot 49: Create new team (auto colour + name)
 *  - Slot 45: Back to hub
 */
public class TeamsMenu implements NamedScreenHandlerFactory {

    private final MobarmyMod mod;
    /** Tracks pending delete: team name awaiting confirmation, null = none. */
    private String pendingDeleteTeam = null;

    public TeamsMenu(MobarmyMod mod) { this.mod = mod; }

    @Override
    public Text getDisplayName() {
        return Text.literal("» Teams «").formatted(Formatting.AQUA, Formatting.BOLD);
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
                handle(slot, button, sp);
                rebuild(inv);
                this.sendContentUpdates();
            }
            @Override public ItemStack quickMove(PlayerEntity p, int s) { return ItemStack.EMPTY; }
        };
    }

    private void handle(int slot, int button, ServerPlayerEntity p) {
        // Back
        if (slot == 36) { LobbyHub.open(p, mod); return; }

        // Create new
        if (slot == 40) {
            int n = mod.teams.size();
            String name = "Team" + (n + 1);
            int suffix = n + 1;
            while (mod.teams.get(name) != null) name = "Team" + (++suffix);
            Formatting color = MenuUtils.nextColor(n);
            mod.teams.create(name, color);
            p.sendMessage(Text.literal("Team " + name + " erstellt.").formatted(color), false);
            return;
        }

        // Team slots: row 1 (9..17) and row 2 (18..26) → up to 18 teams visible
        if (slot >= 9 && slot <= 26) {
            int idx = slot - 9;
            List<Team> teams = new ArrayList<>(mod.teams.all());
            if (idx >= teams.size()) return;
            Team t = teams.get(idx);
            if (button == 0) {
                // Left click → open team detail
                pendingDeleteTeam = null;
                p.openHandledScreen(new TeamEditMenu(mod, t.name));
            } else if (button == 1) {
                // Right click → delete team (with confirmation)
                if (t.name.equals(pendingDeleteTeam)) {
                    mod.teams.remove(t.name);
                    p.sendMessage(Text.literal("Team " + t.name + " gelöscht.").formatted(Formatting.RED), false);
                    pendingDeleteTeam = null;
                } else {
                    pendingDeleteTeam = t.name;
                    p.sendMessage(Text.literal("Nochmal Rechtsklick auf " + t.name + " zum Bestätigen!")
                        .formatted(Formatting.RED, Formatting.BOLD), false);
                }
            }
        }
    }

    private void rebuild(SimpleInventory inv) {
        MenuUtils.drawBorder(inv, 5);

        // Header
        inv.setStack(4, MenuUtils.named(new ItemStack(Items.WRITABLE_BOOK),
            Text.literal("Teams verwalten").formatted(Formatting.AQUA, Formatting.BOLD),
            Text.literal("Linksklick: Spieler zuweisen").formatted(Formatting.GRAY),
            Text.literal("Rechtsklick: Team löschen").formatted(Formatting.RED)
        ));

        // Team list (rows 1+2, slots 9..26 = 18 slots)
        List<Team> teams = new ArrayList<>(mod.teams.all());
        for (int i = 0; i < 18; i++) {
            int slot = 9 + i;
            if (i < teams.size()) {
                Team t = teams.get(i);
                ItemStack banner = new ItemStack(MenuUtils.bannerFor(t.color));
                MenuUtils.named(banner,
                    Text.literal(t.name).formatted(t.color, Formatting.BOLD),
                    Text.literal("Mitglieder: ").formatted(Formatting.GRAY)
                        .append(Text.literal(String.valueOf(t.members.size())).formatted(Formatting.WHITE)),
                    Text.literal(""),
                    Text.literal("▶ Linksklick: Spieler zuweisen").formatted(Formatting.YELLOW),
                    Text.literal("✖ Rechtsklick: Team löschen").formatted(Formatting.RED)
                );
                inv.setStack(slot, banner);
            }
        }

        // Bottom: back + create
        inv.setStack(36, MenuUtils.button(Items.ARROW,
            Text.literal("← Zurück").formatted(Formatting.GRAY)
        ));
        inv.setStack(40, MenuUtils.glow(MenuUtils.button(Items.WHITE_BANNER,
            Text.literal("+ Neues Team").formatted(Formatting.GREEN, Formatting.BOLD),
            Text.literal("Erstellt automatisch ein Team").formatted(Formatting.GRAY)
        )));
    }
}

