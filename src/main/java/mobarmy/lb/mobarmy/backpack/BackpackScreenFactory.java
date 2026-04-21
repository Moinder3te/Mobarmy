package mobarmy.lb.mobarmy.backpack;

import mobarmy.lb.mobarmy.team.Team;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;

public class BackpackScreenFactory implements NamedScreenHandlerFactory {
    private final Team team;

    public BackpackScreenFactory(Team team) { this.team = team; }

    @Override
    public Text getDisplayName() {
        return Text.literal("Backpack — " + team.name).formatted(team.color);
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInv, PlayerEntity player) {
        return GenericContainerScreenHandler.createGeneric9x6(syncId, playerInv, team.backpack);
    }
}

