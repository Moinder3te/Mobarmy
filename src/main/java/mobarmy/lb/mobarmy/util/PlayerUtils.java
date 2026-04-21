package mobarmy.lb.mobarmy.util;

import mobarmy.lb.mobarmy.config.MobarmyConfig;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.Set;

public final class PlayerUtils {
    private PlayerUtils() {}

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
        // Remove harmful effects but preserve beneficial ones (Night Vision, etc.)
        p.getStatusEffects().removeIf(inst -> !inst.getEffectType().value().isBeneficial());
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

