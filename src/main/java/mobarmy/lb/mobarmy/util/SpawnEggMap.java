package mobarmy.lb.mobarmy.util;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public final class SpawnEggMap {
    public static final String TAG_ENTITY = "MobarmyEntity";

    private SpawnEggMap() {}

    public static ItemStack stackFor(EntityType<?> type, int count) {
        SpawnEggItem egg = SpawnEggItem.forEntity(type);
        ItemStack stack = egg != null ? new ItemStack(egg) : new ItemStack(Items.GHAST_TEAR);
        stack.setCount(Math.max(1, Math.min(64, count)));

        NbtCompound nbt = new NbtCompound();
        nbt.putString(TAG_ENTITY, Registries.ENTITY_TYPE.getId(type).toString());
        nbt.putInt("MobarmyCount", count);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));

        Text name = Text.translatable(type.getTranslationKey()).copy().append(" x" + count);
        stack.set(DataComponentTypes.CUSTOM_NAME, name);
        return stack;
    }

    public static EntityType<?> readType(ItemStack stack) {
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (comp == null) return null;
        NbtCompound nbt = comp.copyNbt();
        String s = nbt.getString(TAG_ENTITY).orElse("");
        if (s.isEmpty()) return null;
        Identifier id = Identifier.tryParse(s);
        if (id == null) return null;
        return Registries.ENTITY_TYPE.get(id);
    }
}

