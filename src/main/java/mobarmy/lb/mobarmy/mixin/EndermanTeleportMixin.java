package mobarmy.lb.mobarmy.mixin;

import mobarmy.lb.mobarmy.battle.WaveSpawner;
import net.minecraft.entity.mob.EndermanEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents Endermen that are wave mobs from teleporting out of the arena.
 * Only affects entities tagged with {@link WaveSpawner#WAVE_TAG}.
 */
@Mixin(EndermanEntity.class)
public abstract class EndermanTeleportMixin {

    @Inject(method = "teleportRandomly", at = @At("HEAD"), cancellable = true)
    private void mobarmy$blockTeleport(CallbackInfoReturnable<Boolean> cir) {
        EndermanEntity self = (EndermanEntity) (Object) this;
        if (self.getCommandTags().contains(WaveSpawner.WAVE_TAG)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "teleportTo(Lnet/minecraft/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
    private void mobarmy$blockTeleportToEntity(net.minecraft.entity.Entity target, CallbackInfoReturnable<Boolean> cir) {
        EndermanEntity self = (EndermanEntity) (Object) this;
        if (self.getCommandTags().contains(WaveSpawner.WAVE_TAG)) {
            cir.setReturnValue(false);
        }
    }
}
