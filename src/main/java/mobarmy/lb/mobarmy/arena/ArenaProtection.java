package mobarmy.lb.mobarmy.arena;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.util.math.BlockPos;

/**
 * Tracks every block position that the {@link ArenaBuilder} placed.
 * The break-handler in {@link mobarmy.lb.mobarmy.MobarmyMod} cancels any
 * non-creative break on a position in this set, making the arena structure
 * indestructible — but blocks the players placed themselves are NOT in here
 * and remain freely breakable.
 *
 * Cleared at the start of every new game (in {@link mobarmy.lb.mobarmy.game.GameManager#startGame}).
 */
public final class ArenaProtection {
    private static final LongSet PROTECTED = new LongOpenHashSet();

    private ArenaProtection() {}

    public static synchronized void add(BlockPos pos) { PROTECTED.add(pos.asLong()); }
    public static synchronized void remove(BlockPos pos) { PROTECTED.remove(pos.asLong()); }
    public static synchronized boolean isProtected(BlockPos pos) { return PROTECTED.contains(pos.asLong()); }
    public static synchronized void clear() { PROTECTED.clear(); }
}

