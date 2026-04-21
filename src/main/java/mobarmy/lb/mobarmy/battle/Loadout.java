package mobarmy.lb.mobarmy.battle;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

public final class Loadout {
    private Loadout() {}

    public static void apply(ServerPlayerEntity p) {
        p.getInventory().clear();
        p.equipStack(EquipmentSlot.HEAD,  new ItemStack(Items.IRON_HELMET));
        p.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        p.equipStack(EquipmentSlot.LEGS,  new ItemStack(Items.IRON_LEGGINGS));
        p.equipStack(EquipmentSlot.FEET,  new ItemStack(Items.IRON_BOOTS));

        p.getInventory().insertStack(new ItemStack(Items.IRON_SWORD));
        p.getInventory().insertStack(new ItemStack(Items.BOW));
        p.getInventory().insertStack(new ItemStack(Items.SHIELD));
        p.getInventory().insertStack(new ItemStack(Items.COOKED_BEEF, 16));
        p.getInventory().insertStack(new ItemStack(Items.ARROW, 32));
        p.getInventory().insertStack(new ItemStack(Items.GOLDEN_APPLE, 2));
    }
}

