package mobarmy.lb.mobarmy.mixin;

import mobarmy.lb.mobarmy.MobarmyMod;
import mobarmy.lb.mobarmy.game.GamePhase;
import mobarmy.lb.mobarmy.randomizer.BlockRandomizer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Randomizes drops from living entities (mob loot) during the FARM phase.
 * Uses the same {@code itemMapping} as chest loot for consistency.
 * Only active when {@code mobRandomizerEnabled} is true in config.
 * Skips players — only mob drops are randomized.
 */
@Mixin(Entity.class)
public abstract class MobDropMixin {

    @Inject(
        method = "dropStack(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/item/ItemStack;)Lnet/minecraft/entity/ItemEntity;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void mobarmy$randomizeMobDrop(ServerWorld world, ItemStack stack, CallbackInfoReturnable<ItemEntity> cir) {
        Entity self = (Entity) (Object) this;
        // Only randomize drops from living entities (mobs), not players or block entities.
        if (!(self instanceof LivingEntity) || self instanceof PlayerEntity) return;
        if (stack == null || stack.isEmpty()) return;

        MobarmyMod mod = MobarmyMod.INSTANCE;
        if (mod == null || mod.gameManager == null) return;
        if (!mod.gameManager.isPhase(GamePhase.FARM)) return;
        if (!mod.config.mobRandomizerEnabled) return;

        // Find nearest player as scope key (the killer).
        ServerPlayerEntity nearest = (ServerPlayerEntity) world.getClosestPlayer(self, 32);
        BlockRandomizer randomizer = mod.randomizerManager.getFor(nearest);
        if (randomizer == null) return;

        ItemStack mapped = randomizer.getItemDrop(stack.getItem());
        if (mapped.isEmpty()) return;

        ItemStack swapped = mapped.copy();
        swapped.setCount(stack.getCount());

        // Record discovery.
        if (nearest != null) {
            String scopeKey = mod.randomizerManager.scopeKey(nearest);
            mod.randomizerManager.recordMobDiscovery(scopeKey, stack.getItem(), mapped.getItem());
        }

        // Spawn the swapped item ourselves and cancel vanilla.
        ItemEntity ie = new ItemEntity(world,
            self.getX(), self.getEyeY() - 0.3, self.getZ(), swapped);
        ie.setToDefaultPickupDelay();
        world.spawnEntity(ie);
        cir.setReturnValue(ie);
    }
}
