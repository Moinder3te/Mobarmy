package mobarmy.lb.mobarmy.battle;

import mobarmy.lb.mobarmy.arena.ArenaDimension;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LodestoneTrackerComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A lodestone-compass item that always points toward the nearest wave mob.
 * Given to each attacker on arena entry; updated every few ticks.
 */
public final class MobTracker {
    private MobTracker() {}

    private static final String MARKER_KEY = "mobarmy_tracker";

    /** Create a fresh Mob Tracker compass item. */
    public static ItemStack create() {
        ItemStack stack = new ItemStack(Items.COMPASS);
        stack.set(DataComponentTypes.CUSTOM_NAME,
            Text.literal("").append(Text.literal("⚔ Mob-Tracker").formatted(Formatting.RED, Formatting.BOLD))
                .styled(s -> s.withItalic(false)));
        stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            Text.literal("Zeigt zum nächsten Mob").formatted(Formatting.GRAY)
        )));
        // Marker tag so we can identify this item later.
        NbtCompound tag = new NbtCompound();
        tag.putBoolean(MARKER_KEY, true);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        return stack;
    }

    /** Check whether an ItemStack is a Mob Tracker. */
    public static boolean isTracker(ItemStack stack) {
        if (stack.isEmpty() || !stack.isOf(Items.COMPASS)) return false;
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (data == null) return false;
        return data.copyNbt().getBoolean(MARKER_KEY, false);
    }

    /**
     * Update all Mob Tracker compasses in a match's attacker team.
     * Called every few ticks from MatchInstance.tick().
     */
    public static void updateAll(MatchInstance match, ServerWorld world) {
        for (ServerPlayerEntity player : match.onlineAttackers(world.getServer())) {
            NearestMob nearest = findNearest(player, match, world);
            updatePlayerTracker(player, nearest, world);
        }
    }

    private static void updatePlayerTracker(ServerPlayerEntity player, NearestMob nearest, ServerWorld world) {
        // Find the tracker in inventory.
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!isTracker(stack)) continue;

            if (nearest == null) {
                // No mobs alive — clear target, show "Keine Mobs".
                stack.set(DataComponentTypes.LODESTONE_TRACKER,
                    new LodestoneTrackerComponent(Optional.empty(), false));
                stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    Text.literal("Keine Mobs in Reichweite").formatted(Formatting.DARK_GRAY)
                )));
            } else {
                // Point compass to the nearest mob.
                GlobalPos target = GlobalPos.create(world.getRegistryKey(),
                    nearest.entity.getBlockPos());
                stack.set(DataComponentTypes.LODESTONE_TRACKER,
                    new LodestoneTrackerComponent(Optional.of(target), false));

                String mobName = getMobName(nearest.entity);
                int dist = (int) Math.round(nearest.distance);
                stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    Text.literal(mobName).formatted(Formatting.YELLOW),
                    Text.literal(dist + " Blöcke entfernt").formatted(Formatting.GRAY)
                )));
                stack.set(DataComponentTypes.CUSTOM_NAME,
                    Text.literal("").append(Text.literal("⚔ " + mobName + " (" + dist + "m)")
                        .formatted(Formatting.RED, Formatting.BOLD)).styled(s -> s.withItalic(false)));
            }
            // Only update the first tracker found.
            return;
        }
    }

    private static NearestMob findNearest(ServerPlayerEntity player, MatchInstance match, ServerWorld world) {
        Entity closest = null;
        double closestDist = Double.MAX_VALUE;

        for (UUID mobId : match.spawner.aliveMobs) {
            Entity e = world.getEntity(mobId);
            if (e == null || !e.isAlive()) continue;
            double dist = player.squaredDistanceTo(e);
            if (dist < closestDist) {
                closestDist = dist;
                closest = e;
            }
        }
        if (closest == null) return null;
        return new NearestMob(closest, Math.sqrt(closestDist));
    }

    private static String getMobName(Entity entity) {
        Identifier id = Registries.ENTITY_TYPE.getId(entity.getType());
        if (id == null) return "???";
        String raw = id.getPath().replace('_', ' ');
        if (raw.isEmpty()) return "???";
        return raw.substring(0, 1).toUpperCase() + raw.substring(1);
    }

    /** Remove all Mob Tracker items from a player's inventory. */
    public static void removeAll(ServerPlayerEntity player) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            if (isTracker(player.getInventory().getStack(i))) {
                player.getInventory().setStack(i, ItemStack.EMPTY);
            }
        }
    }

    private record NearestMob(Entity entity, double distance) {}
}
