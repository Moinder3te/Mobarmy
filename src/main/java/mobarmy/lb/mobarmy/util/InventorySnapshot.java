package mobarmy.lb.mobarmy.util;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Captures and restores player inventories. Used to give attackers
 * the same loadout (= their farm-phase inventory) at the start of every match
 * during the round-robin battle phase.
 */
public class InventorySnapshot {
    private final Map<UUID, ItemStack[]> snapshots = new HashMap<>();

    /** Snapshot every team member's current inventory. */
    public void snapshot(ServerPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        int size = inv.size();
        ItemStack[] copy = new ItemStack[size];
        for (int i = 0; i < size; i++) copy[i] = inv.getStack(i).copy();
        snapshots.put(player.getUuid(), copy);
    }

    /** Restore a previously taken snapshot. Returns false if no snapshot exists. */
    public boolean restore(ServerPlayerEntity player) {
        ItemStack[] copy = snapshots.get(player.getUuid());
        if (copy == null) return false;
        PlayerInventory inv = player.getInventory();
        inv.clear();
        int size = Math.min(copy.length, inv.size());
        for (int i = 0; i < size; i++) inv.setStack(i, copy[i].copy());
        return true;
    }

    public boolean has(UUID uuid) { return snapshots.containsKey(uuid); }

    public void clear() { snapshots.clear(); }
}

