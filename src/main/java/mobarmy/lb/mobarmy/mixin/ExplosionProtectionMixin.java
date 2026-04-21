package mobarmy.lb.mobarmy.mixin;

import mobarmy.lb.mobarmy.arena.ArenaProtection;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.explosion.Explosion;
import net.minecraft.world.explosion.ExplosionBehavior;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Prevents explosions (TNT, Creeper, Ghast, etc.) from destroying
 * blocks that are part of a protected arena structure.
 */
@Mixin(ExplosionBehavior.class)
public class ExplosionProtectionMixin {

    @Inject(method = "canDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void mobarmy$blockArenaExplosion(Explosion explosion, BlockView world, BlockPos pos,
                                              BlockState state, float power,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (ArenaProtection.isProtected(pos)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "getBlastResistance", at = @At("HEAD"), cancellable = true)
    private void mobarmy$infiniteResistance(Explosion explosion, BlockView world, BlockPos pos,
                                             BlockState state, FluidState fluid,
                                             CallbackInfoReturnable<Optional<Float>> cir) {
        if (ArenaProtection.isProtected(pos)) {
            cir.setReturnValue(Optional.of(3600000f));
        }
    }
}
