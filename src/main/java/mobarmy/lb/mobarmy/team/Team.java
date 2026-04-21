package mobarmy.lb.mobarmy.team;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import mobarmy.lb.mobarmy.backpack.BackpackInventory;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Formatting;

import java.util.*;

public class Team {
    public final String name;
    public Formatting color;
    public final Set<UUID> members = new HashSet<>();
    public final BackpackInventory backpack = new BackpackInventory();

    /** Mobs killed during farm phase. */
    public final Object2IntOpenHashMap<EntityType<?>> killedMobs = new Object2IntOpenHashMap<>();

    /** Subset of killedMobs: how many of those kills were baby variants (UI hint). */
    public final Object2IntOpenHashMap<EntityType<?>> killedBabies = new Object2IntOpenHashMap<>();

    /** Per-type bag of NBT snapshots taken at kill-time. The wave spawner picks
     *  one at random when spawning a mob of that type, so wolf variants, frog
     *  colours, cat skins, horse coats etc. are preserved exactly. */
    public final Map<EntityType<?>, List<NbtCompound>> killedNbts = new HashMap<>();

    /** Wave assignments: index 0..waveCount-1, each list = entities to spawn. */
    public final List<List<EntityType<?>>> waves = new ArrayList<>();

    public boolean wavesSubmitted = false;

    public Team(String name, Formatting color) {
        this.name = name;
        this.color = color;
    }

    public void resetForNewGame() {
        killedMobs.clear();
        killedBabies.clear();
        killedNbts.clear();
        waves.clear();
        wavesSubmitted = false;
        backpack.clear();
    }
}


