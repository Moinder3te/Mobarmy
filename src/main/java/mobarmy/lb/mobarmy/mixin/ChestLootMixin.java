package mobarmy.lb.mobarmy.mixin;

import mobarmy.lb.mobarmy.MobarmyMod;
import mobarmy.lb.mobarmy.game.GamePhase;
import mobarmy.lb.mobarmy.randomizer.BlockRandomizer;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.LootTable;
import net.minecraft.registry.RegistryKey;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Randomizes chest loot using the same randomizer mapping as block drops.
 * Activates only during {@link GamePhase#FARM}.
 * <p>
 * Injects into {@code createMenu} on the concrete
 * {@link LootableContainerBlockEntity} class. That method calls
 * {@code generateLoot(player)} internally before creating the screen
 * handler, so by RETURN the inventory is filled with vanilla loot and
 * we can swap every item through the player's randomizer.
 * <p>
 * We cannot inject into the {@code generateLoot} default method on
 * {@code LootableInventory} directly — Mixin cannot target default
 * interface methods that aren't overridden in the concrete class.
 */
@Mixin(LootableContainerBlockEntity.class)
public abstract class ChestLootMixin {

    @Shadow
    protected RegistryKey<LootTable> lootTable;

    @Unique
    private boolean mobarmy$lootPending = false;

    @Inject(method = "createMenu", at = @At("HEAD"))
    private void mobarmy$captureBeforeMenu(int syncId, PlayerInventory playerInventory,
                                            PlayerEntity player, CallbackInfoReturnable<ScreenHandler> cir) {
        mobarmy$lootPending = (this.lootTable != null);
    }

    @Inject(method = "createMenu", at = @At("RETURN"))
    private void mobarmy$randomizeAfterMenu(int syncId, PlayerInventory playerInventory,
                                             PlayerEntity player, CallbackInfoReturnable<ScreenHandler> cir) {
        if (!mobarmy$lootPending) return;
        mobarmy$lootPending = false;

        MobarmyMod mod = MobarmyMod.INSTANCE;
        if (mod == null || mod.gameManager == null) return;
        if (!mod.gameManager.isPhase(GamePhase.FARM)) return;

        if (!mod.config.chestRandomizerEnabled) return;

        ServerPlayerEntity sp = player instanceof ServerPlayerEntity spe ? spe : null;
        BlockRandomizer randomizer = mod.randomizerManager.getFor(sp);
        if (randomizer == null) return;

        Inventory inv = (Inventory) (Object) this;

        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;

            ItemStack mapped = randomizer.getItemDrop(stack.getItem());
            if (mapped.isEmpty()) continue;

            ItemStack swapped = mapped.copy();
            swapped.setCount(stack.getCount());
            inv.setStack(i, swapped);

            if (sp != null) {
                String scopeKey = mod.randomizerManager.scopeKey(sp);
                // Block-based discovery (for block drops cheatsheet tab).
                if (stack.getItem() instanceof net.minecraft.item.BlockItem bi) {
                    mod.randomizerManager.recordDiscovery(scopeKey, bi.getBlock(), mapped.getItem());
                }
                // Item-based discovery (for chest loot cheatsheet tab).
                mod.randomizerManager.recordItemDiscovery(scopeKey, stack.getItem(), mapped.getItem());
            }
        }
    }
}
