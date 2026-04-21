package mobarmy.lb.mobarmy.util;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HoglinEntity;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.entity.mob.ZoglinEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;

/**
 * Helpers for entity baby-state and full-NBT variant snapshotting.
 */
public final class MobUtils {
    private MobUtils() {}

    public static boolean isBaby(Entity e) {
        return e instanceof LivingEntity le && le.isBaby();
    }

    public static void setBaby(Entity e, boolean baby) {
        if (e instanceof PassiveEntity p) { p.setBaby(baby); return; }
        if (e instanceof ZombieEntity z)  { z.setBaby(baby); return; }
        if (e instanceof PiglinEntity p)  { p.setBaby(baby); return; }
        if (e instanceof HoglinEntity h)  { h.setBaby(baby); return; }
        if (e instanceof ZoglinEntity z)  { z.setBaby(baby); return; }
    }

    /**
     * Snapshot an entity's full NBT (including its "id" type tag) so the wave
     * spawner can later reconstruct an exact-variant copy via
     * {@link net.minecraft.entity.EntityType#loadEntityWithPassengers}.
     * Returns null if the entity is gone or saving fails.
     *
     * 1.21.11 uses the WriteView/ReadView abstractions; we use NbtWriteView
     * to materialise the data as a real NbtCompound for storage.
     */
    public static NbtCompound snapshotVariantNbt(Entity entity) {
        if (entity == null || entity.isRemoved()) return null;
        try {
            var registry = entity.getEntityWorld().getRegistryManager();
            NbtWriteView view = NbtWriteView.create(ErrorReporter.EMPTY, registry);
            if (!entity.saveSelfData(view)) return null;
            return view.getNbt();
        } catch (Throwable t) {
            return null;
        }
    }
}



