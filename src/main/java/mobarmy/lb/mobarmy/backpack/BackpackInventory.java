package mobarmy.lb.mobarmy.backpack;

import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.storage.ReadView;
import net.minecraft.util.ErrorReporter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class BackpackInventory extends SimpleInventory {
    public static final int SIZE = 54;

    public BackpackInventory() {
        super(SIZE);
    }

    /** Create a deep copy snapshot of the current contents. */
    public ItemStack[] snapshot() {
        ItemStack[] copy = new ItemStack[SIZE];
        for (int i = 0; i < SIZE; i++) copy[i] = getStack(i).copy();
        return copy;
    }

    /** Restore contents from a previously taken snapshot. */
    public void restore(ItemStack[] snap) {
        clear();
        int len = Math.min(snap.length, SIZE);
        for (int i = 0; i < len; i++) setStack(i, snap[i].copy());
    }

    /** Save backpack to a compressed NBT file. */
    public void save(Path file, RegistryWrapper.WrapperLookup reg) throws IOException {
        NbtCompound root = new NbtCompound();
        NbtList items = new NbtList();
        for (int i = 0; i < SIZE; i++) {
            ItemStack stack = getStack(i);
            if (stack.isEmpty()) continue;
            NbtWriteView view = NbtWriteView.create(ErrorReporter.EMPTY, reg);
            view.putByte("Slot", (byte) i);
            view.put("Item", ItemStack.CODEC, stack);
            items.add(view.getNbt());
        }
        root.put("Items", items);
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(root, file);
    }

    /** Load backpack from a compressed NBT file. No-op if file doesn't exist. */
    public void load(Path file, RegistryWrapper.WrapperLookup reg) throws IOException {
        clear();
        if (!Files.exists(file)) return;
        NbtCompound root = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
        NbtList items = root.getListOrEmpty("Items");
        for (int j = 0; j < items.size(); j++) {
            NbtCompound entry = items.getCompoundOrEmpty(j);
            ReadView rv = NbtReadView.create(ErrorReporter.EMPTY, reg, entry);
            int slot = rv.getByte("Slot", (byte) -1) & 0xFF;
            if (slot >= SIZE) continue;
            rv.read("Item", ItemStack.CODEC).ifPresent(stack -> setStack(slot, stack));
        }
    }
}

