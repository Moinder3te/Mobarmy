package mobarmy.lb.mobarmy.util;

import mobarmy.lb.mobarmy.config.MobarmyConfig;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.Set;

public final class PlayerUtils {
    private PlayerUtils() {}

    /** Ensures a 3×3 stone platform exists below the given position so players don't fall. */
    public static void ensurePlatform(ServerWorld world, MobarmyConfig.BlockPosJson pos) {
        BlockPos below = new BlockPos(pos.x, pos.y - 1, pos.z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos p = below.add(dx, 0, dz);
                if (world.getBlockState(p).isAir()) {
                    world.setBlockState(p, Blocks.STONE.getDefaultState());
                }
            }
        }
    }

    public static void teleport(ServerPlayerEntity p, ServerWorld w, MobarmyConfig.BlockPosJson pos) {
        teleport(p, w, new Vec3d(pos.x + 0.5, pos.y, pos.z + 0.5));
    }

    public static void teleport(ServerPlayerEntity p, ServerWorld w, Vec3d pos) {
        p.teleport(w, pos.x, pos.y, pos.z, Set.of(), p.getYaw(), p.getPitch(), false);
        p.setVelocity(Vec3d.ZERO);
        p.fallDistance = 0;
    }

    public static void heal(ServerPlayerEntity p) {
        p.setHealth(p.getMaxHealth());
        p.getHungerManager().setFoodLevel(20);
        p.getHungerManager().setSaturationLevel(5f);
        // Remove harmful effects via removeStatusEffect() so attribute
        // modifiers (e.g. Slowness speed penalty) are properly cleaned up.
        var toRemove = new java.util.ArrayList<net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.effect.StatusEffect>>();
        for (var inst : p.getStatusEffects()) {
            if (!inst.getEffectType().value().isBeneficial()) {
                toRemove.add(inst.getEffectType());
            }
        }
        for (var type : toRemove) p.removeStatusEffect(type);
        p.extinguish();
        p.setFireTicks(0);
    }

    public static void setMode(ServerPlayerEntity p, GameMode mode) {
        p.changeGameMode(mode);
    }

    public static void title(ServerPlayerEntity p, Text title, Text sub) {
        p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleS2CPacket(title));
        if (sub != null) p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.SubtitleS2CPacket(sub));
    }
}

