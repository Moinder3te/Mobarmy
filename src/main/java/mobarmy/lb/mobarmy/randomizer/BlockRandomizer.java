package mobarmy.lb.mobarmy.randomizer;

import net.minecraft.block.*;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.*;

/**
 * Deterministic, seed-based randomizer.
 * Each eligible source block is mapped to a random item from the full
 * Minecraft item registry (blocks, tools, food, materials — everything).
 */
public class BlockRandomizer {
    private final Map<Block, ItemStack> mapping = new IdentityHashMap<>();
    /** Item→Item mapping for chest loot. BlockItems reuse the block mapping;
     *  non-block items get an independent random mapping (same RNG stream). */
    private final Map<Item, ItemStack> itemMapping = new IdentityHashMap<>();
    private long seed;

    public long seed() { return seed; }

    public void init(long seed) {
        this.seed = seed;
        mapping.clear();
        itemMapping.clear();

        // --- source blocks (what you can break) ---
        List<Block> sources = new ArrayList<>();
        for (Block b : Registries.BLOCK) {
            if (isEligibleSource(b)) sources.add(b);
        }
        sources.sort(Comparator.comparing(b -> Registries.BLOCK.getId(b).toString()));

        // --- target items (what you can get) — full item registry ---
        List<Item> targets = new ArrayList<>();
        for (Item item : Registries.ITEM) {
            if (isEligibleTarget(item)) targets.add(item);
        }
        targets.sort(Comparator.comparing(i -> Registries.ITEM.getId(i).toString()));

        Random rng = new Random(seed);
        for (Block src : sources) {
            Item tgt = targets.get(rng.nextInt(targets.size()));
            mapping.put(src, new ItemStack(tgt));
        }

        // Build item→item mapping for chest loot randomization.
        // BlockItems whose block is in the block mapping reuse that target
        // (so Stone→Diamond in blocks means Stone-item→Diamond in chests).
        // All other items get an independent random mapping.
        for (Item src : targets) {
            if (src instanceof net.minecraft.item.BlockItem bi && mapping.containsKey(bi.getBlock())) {
                itemMapping.put(src, mapping.get(bi.getBlock()).copy());
            } else {
                Item tgt = targets.get(rng.nextInt(targets.size()));
                itemMapping.put(src, new ItemStack(tgt));
            }
        }
    }

    public ItemStack getDrop(Block b) {
        ItemStack s = mapping.get(b);
        return s == null ? ItemStack.EMPTY : s.copy();
    }

    public boolean has(Block b) { return mapping.containsKey(b); }

    /** Get the randomized item for chest loot. */
    public ItemStack getItemDrop(Item i) {
        ItemStack s = itemMapping.get(i);
        return s == null ? ItemStack.EMPTY : s.copy();
    }

    public boolean hasItem(Item i) { return itemMapping.containsKey(i); }

    public int mappingSize() { return mapping.size(); }

    /** Blocks that participate as randomizer sources (what you break). */
    private static boolean isEligibleSource(Block b) {
        if (b == Blocks.AIR || b == Blocks.CAVE_AIR || b == Blocks.VOID_AIR) return false;
        if (b == Blocks.BEDROCK || b == Blocks.BARRIER) return false;
        if (b == Blocks.COMMAND_BLOCK || b == Blocks.CHAIN_COMMAND_BLOCK || b == Blocks.REPEATING_COMMAND_BLOCK) return false;
        if (b == Blocks.STRUCTURE_BLOCK || b == Blocks.STRUCTURE_VOID) return false;
        if (b == Blocks.JIGSAW || b == Blocks.LIGHT) return false;
        // Shulker boxes are BlockEntityProvider but should be randomizable —
        // their contents are spilled separately by the mixin.
        if (b instanceof ShulkerBoxBlock) { /* allow */ }
        else if (b instanceof BlockEntityProvider) return false;
        if (b.asItem() == Items.AIR) return false;
        Identifier id = Registries.BLOCK.getId(b);
        if (id == null) return false;
        return true;
    }

    /** Items that can appear as randomized drops. Excludes only technical junk. */
    private static boolean isEligibleTarget(Item item) {
        if (item == Items.AIR) return false;
        if (item == Items.COMMAND_BLOCK || item == Items.CHAIN_COMMAND_BLOCK || item == Items.REPEATING_COMMAND_BLOCK) return false;
        if (item == Items.COMMAND_BLOCK_MINECART) return false;
        if (item == Items.STRUCTURE_BLOCK || item == Items.STRUCTURE_VOID) return false;
        if (item == Items.JIGSAW || item == Items.LIGHT) return false;
        if (item == Items.DEBUG_STICK || item == Items.KNOWLEDGE_BOOK) return false;
        Identifier id = Registries.ITEM.getId(item);
        if (id == null) return false;
        return true;
    }
}

