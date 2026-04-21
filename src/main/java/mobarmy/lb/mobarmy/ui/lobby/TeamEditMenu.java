package mobarmy.lb.mobarmy.ui.lobby;

import com.mojang.authlib.GameProfile;
import mobarmy.lb.mobarmy.MobarmyMod;
import mobarmy.lb.mobarmy.team.Team;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ProfileComponent;
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
 * TEAM EDIT (6 rows). Per-team:
 *  - Header with banner + member count
 *  - Online players grid: green-glow head if member of THIS team, normal head otherwise
 *  - Click a head → toggle membership (add to this team / remove from team)
 *  - Slot 49: cycle team color
 *  - Slot 45: back
 */
public class TeamEditMenu implements NamedScreenHandlerFactory {

    private static final Formatting[] COLOR_CYCLE = {
        Formatting.RED, Formatting.BLUE, Formatting.GREEN, Formatting.YELLOW,
        Formatting.AQUA, Formatting.LIGHT_PURPLE, Formatting.GOLD, Formatting.DARK_PURPLE,
        Formatting.WHITE, Formatting.GRAY, Formatting.BLACK
    };

    private final MobarmyMod mod;
    private final String teamName;

    public TeamEditMenu(MobarmyMod mod, String teamName) {
        this.mod = mod;
        this.teamName = teamName;
    }

    @Override
    public Text getDisplayName() {
        Team t = mod.teams.get(teamName);
        Formatting c = t == null ? Formatting.GRAY : t.color;
        return Text.literal("» " + teamName + " «").formatted(c, Formatting.BOLD);
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
        Team t = mod.teams.get(teamName);
        if (t == null) { LobbyHub.open(p, mod); return; }

        // Back
        if (slot == 45) { p.openHandledScreen(new TeamsMenu(mod)); return; }

        // Cycle color
        if (slot == 49) {
            int idx = 0;
            for (int i = 0; i < COLOR_CYCLE.length; i++) if (COLOR_CYCLE[i] == t.color) { idx = i; break; }
            t.color = COLOR_CYCLE[(idx + 1) % COLOR_CYCLE.length];
            return;
        }

        // Player heads in rows 1..4 (slots 10..16, 19..25, 28..34, 37..43)
        int playerIdx = playerIndexFromSlot(slot);
        if (playerIdx < 0) return;
        List<ServerPlayerEntity> players = onlinePlayers();
        if (playerIdx >= players.size()) return;
        ServerPlayerEntity target = players.get(playerIdx);

        Team currentTeam = mod.teams.get(target);
        if (currentTeam == t) {
            // Remove from this team
            mod.teams.leave(target);
            target.sendMessage(Text.literal("Du wurdest aus Team " + t.name + " entfernt.").formatted(Formatting.YELLOW), false);
        } else {
            mod.teams.joinUuid(target.getUuid(), t.name);
            target.sendMessage(Text.literal("Du bist jetzt im Team " + t.name).formatted(t.color, Formatting.BOLD), false);
        }
    }

    private int playerIndexFromSlot(int slot) {
        // Map slots 10..16, 19..25, 28..34, 37..43 to indexes 0..27
        int row = slot / 9;
        int col = slot % 9;
        if (row < 1 || row > 4) return -1;
        if (col < 1 || col > 7) return -1;
        return (row - 1) * 7 + (col - 1);
    }

    private List<ServerPlayerEntity> onlinePlayers() {
        if (mod.server == null) return List.of();
        return new ArrayList<>(mod.server.getPlayerManager().getPlayerList());
    }

    private void rebuild(SimpleInventory inv) {
        Team t = mod.teams.get(teamName);
        if (t == null) return;

        MenuUtils.drawBorder(inv, 6);

        // Header banner (slot 4)
        ItemStack header = new ItemStack(MenuUtils.bannerFor(t.color));
        MenuUtils.named(header,
            Text.literal(t.name).formatted(t.color, Formatting.BOLD),
            Text.literal("Mitglieder: ").formatted(Formatting.GRAY)
                .append(Text.literal(String.valueOf(t.members.size())).formatted(Formatting.WHITE)),
            Text.literal(""),
            Text.literal("Klicke unten Spieler-Köpfe").formatted(Formatting.YELLOW),
            Text.literal("um sie zuzuweisen / entfernen").formatted(Formatting.YELLOW)
        );
        inv.setStack(4, header);

        // Player grid: rows 1..4
        List<ServerPlayerEntity> players = onlinePlayers();
        for (int i = 0; i < 28; i++) {
            int row = i / 7 + 1;
            int col = i % 7 + 1;
            int slot = row * 9 + col;
            if (i >= players.size()) {
                // Empty slot inside the grid stays as accent glass later
                continue;
            }
            ServerPlayerEntity sp = players.get(i);
            ItemStack head = new ItemStack(Items.PLAYER_HEAD);
            head.set(DataComponentTypes.PROFILE, ProfileComponent.ofStatic(sp.getGameProfile()));
            String playerName = sp.getName().getString();
            Team theirs = mod.teams.get(sp);
            String teamLabel;
            Formatting teamColor;
            if (theirs == null) { teamLabel = "kein Team"; teamColor = Formatting.DARK_GRAY; }
            else { teamLabel = theirs.name; teamColor = theirs.color; }

            boolean isInThisTeam = theirs == t;
            MenuUtils.named(head,
                Text.literal(playerName).formatted(isInThisTeam ? t.color : Formatting.WHITE, Formatting.BOLD),
                Text.literal("Aktuelles Team: ").formatted(Formatting.GRAY).append(Text.literal(teamLabel).formatted(teamColor)),
                Text.literal(""),
                Text.literal(isInThisTeam ? "✔ Klick: Aus Team entfernen" : "+ Klick: Diesem Team beitreten")
                    .formatted(isInThisTeam ? Formatting.GREEN : Formatting.YELLOW)
            );
            if (isInThisTeam) MenuUtils.glow(head);
            inv.setStack(slot, head);
        }

        // Bottom: back + cycle color
        inv.setStack(45, MenuUtils.button(Items.ARROW,
            Text.literal("← Zurück zur Team-Liste").formatted(Formatting.GRAY)
        ));
        inv.setStack(49, MenuUtils.button(Items.PRISMARINE_CRYSTALS,
            Text.literal("Team-Farbe wechseln").formatted(t.color, Formatting.BOLD),
            Text.literal("Klick zum Durchwechseln").formatted(Formatting.GRAY)
        ));

        // Soft accent in remaining empty interior slots
        for (int row = 1; row <= 4; row++) {
            for (int col = 1; col <= 7; col++) {
                int slot = row * 9 + col;
                if (inv.getStack(slot).isEmpty()) inv.setStack(slot, MenuUtils.ACCENT.copy());
            }
        }
    }
}

