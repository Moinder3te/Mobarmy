package mobarmy.lb.mobarmy.randomizer;

import mobarmy.lb.mobarmy.MobarmyMod;
import mobarmy.lb.mobarmy.config.RandomizerMode;
import mobarmy.lb.mobarmy.team.Team;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Manages one or more {@link BlockRandomizer} instances depending on the
 * configured {@link RandomizerMode}, and tracks per-scope block discoveries
 * for the Cheat Sheet UI.
 */
public class RandomizerManager {
    private RandomizerMode mode = RandomizerMode.GLOBAL;
    private final Map<String, BlockRandomizer> randomizers = new LinkedHashMap<>();
    /** Per-scope discovered block→item mapping for the Cheat Sheet. */
    private final Map<String, Map<Block, Item>> discovered = new HashMap<>();
    private long baseSeed;

    public RandomizerMode mode() { return mode; }
    public long baseSeed() { return baseSeed; }

    /**
     * Initialize all randomizer(s) for the current game.
     * Must be called at game start when teams are known.
     */
    public void init(long baseSeed, RandomizerMode mode, Collection<Team> teams) {
        this.baseSeed = baseSeed;
        this.mode = mode;
        randomizers.clear();
        discovered.clear();

        switch (mode) {
            case GLOBAL -> {
                BlockRandomizer r = new BlockRandomizer();
                r.init(baseSeed);
                randomizers.put("global", r);
            }
            case PER_TEAM -> {
                for (Team t : teams) {
                    BlockRandomizer r = new BlockRandomizer();
                    r.init(baseSeed ^ ((long) t.name.hashCode() * 0x9E3779B97F4A7C15L));
                    randomizers.put("team:" + t.name.toLowerCase(Locale.ROOT), r);
                }
            }
            case PER_PLAYER -> {
                for (Team t : teams) {
                    for (UUID member : t.members) {
                        BlockRandomizer r = new BlockRandomizer();
                        r.init(baseSeed ^ member.getLeastSignificantBits());
                        randomizers.put("player:" + member, r);
                    }
                }
            }
        }
    }

    /** Get the randomizer for a specific player (respects current mode). */
    public @Nullable BlockRandomizer getFor(@Nullable ServerPlayerEntity player) {
        if (randomizers.isEmpty()) return null;
        if (player == null) {
            return randomizers.values().iterator().next();
        }
        return switch (mode) {
            case GLOBAL -> randomizers.values().iterator().next();
            case PER_TEAM -> {
                Team t = MobarmyMod.INSTANCE.teams.get(player);
                yield t != null
                    ? randomizers.getOrDefault("team:" + t.name.toLowerCase(Locale.ROOT),
                        randomizers.values().iterator().next())
                    : randomizers.values().iterator().next();
            }
            case PER_PLAYER -> {
                String key = "player:" + player.getUuid();
                BlockRandomizer r = randomizers.get(key);
                if (r == null) {
                    // Late-join: create on-demand (deterministic).
                    r = new BlockRandomizer();
                    r.init(baseSeed ^ player.getUuid().getLeastSignificantBits());
                    randomizers.put(key, r);
                }
                yield r;
            }
        };
    }

    /** True if any randomizer has a mapping for this block. */
    public boolean has(Block b) {
        BlockRandomizer r = randomizers.values().stream().findFirst().orElse(null);
        return r != null && r.has(b);
    }

    /** True if any randomizer has an item mapping for this item. */
    public boolean hasItem(Item i) {
        BlockRandomizer r = randomizers.values().stream().findFirst().orElse(null);
        return r != null && r.hasItem(i);
    }

    /** Get the scope key used for discovery tracking. */
    public String scopeKey(@Nullable ServerPlayerEntity player) {
        if (player == null) return "global";
        return switch (mode) {
            case GLOBAL -> "global";
            case PER_TEAM -> {
                Team t = MobarmyMod.INSTANCE.teams.get(player);
                yield t != null ? "team:" + t.name.toLowerCase(Locale.ROOT) : "global";
            }
            case PER_PLAYER -> "player:" + player.getUuid();
        };
    }

    /** Record that a block→item drop was discovered. */
    public void recordDiscovery(String scopeKey, Block block, Item item) {
        discovered.computeIfAbsent(scopeKey, k -> new LinkedHashMap<>()).put(block, item);
    }

    /** Get all discovered block→item mappings for a scope. */
    public Map<Block, Item> getDiscovered(String scopeKey) {
        return discovered.getOrDefault(scopeKey, Map.of());
    }

    public boolean hasAnyRandomizer() {
        return !randomizers.isEmpty();
    }

    public void clear() {
        randomizers.clear();
        discovered.clear();
    }
}
