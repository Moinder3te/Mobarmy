package mobarmy.lb.mobarmy.randomizer;

import net.minecraft.block.*;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.*;

/**
 * Deterministic, seed-based 1:1 permutation of all "natural" blocks.
 * Breaking block X always drops the item form of mapped block Y.
 */
public class BlockRandomizer {
    private final Map<Block, ItemStack> mapping = new IdentityHashMap<>();
    private long seed;

    public long seed() { return seed; }

    public void init(long seed) {
        this.seed = seed;
        mapping.clear();

        List<Block> sources = new ArrayList<>();
        for (Block b : Registries.BLOCK) {
            if (isEligible(b)) sources.add(b);
        }
        // Stable order (by registry id) so seed is reproducible across launches.
        sources.sort(Comparator.comparing(b -> Registries.BLOCK.getId(b).toString()));

        List<Block> targets = new ArrayList<>(sources);
        Collections.shuffle(targets, new Random(seed));

        for (int i = 0; i < sources.size(); i++) {
            Block src = sources.get(i);
            Block tgt = targets.get(i);
            ItemStack drop = new ItemStack(tgt.asItem());
            if (drop.isEmpty()) drop = new ItemStack(Items.STONE);
            mapping.put(src, drop);
        }
    }

    public ItemStack getDrop(Block b) {
        ItemStack s = mapping.get(b);
        return s == null ? ItemStack.EMPTY : s.copy();
    }

    public boolean has(Block b) { return mapping.containsKey(b); }

    public int mappingSize() { return mapping.size(); }

    private static boolean isEligible(Block b) {
        if (b == Blocks.AIR || b == Blocks.CAVE_AIR || b == Blocks.VOID_AIR) return false;
        if (b == Blocks.BEDROCK || b == Blocks.BARRIER) return false;
        if (b == Blocks.COMMAND_BLOCK || b == Blocks.CHAIN_COMMAND_BLOCK || b == Blocks.REPEATING_COMMAND_BLOCK) return false;
        if (b == Blocks.STRUCTURE_BLOCK || b == Blocks.STRUCTURE_VOID) return false;
        if (b == Blocks.JIGSAW || b == Blocks.LIGHT) return false;
        if (b instanceof BlockEntityProvider) return false; // chests, furnaces etc. retain vanilla
        if (b.asItem() == Items.AIR) return false;
        Identifier id = Registries.BLOCK.getId(b);
        if (id == null) return false;
        return true;
    }
}

