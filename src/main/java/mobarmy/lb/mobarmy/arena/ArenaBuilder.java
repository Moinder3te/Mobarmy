package mobarmy.lb.mobarmy.arena;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
public final class ArenaBuilder {
    private ArenaBuilder() {}
    public static void build(ServerWorld w, Arena a) {
        for (int y = a.floorY; y <= a.ceilY + 4; y++) clearLayer(w, a, y);
        floor(w, a);
        for (int y = a.floorY + 1; y <= a.ceilY - 1; y++) wallsAtY(w, a, y);
        ceilingFullSeaLantern(w, a);
        stoneRoof(w, a);
        wallLanterns(w, a);
        interiorLights(w, a);
        mobSpawnPad(w, a);
        playerSpawnPad(w, a);
        deathRoom(w, a);
    }
    public static void clearLayer(ServerWorld w, Arena a, int y) {
        int r = a.radius;
        int rSq = r * r;
        int cx = a.center.getX(), cz = a.center.getZ();
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > rSq) continue;
                p.set(cx + dx, y, cz + dz);
                if (!w.getBlockState(p).isAir()) {
                    w.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
                }
                ArenaProtection.remove(p);
            }
        }
    }
    public static void floor(ServerWorld w, Arena a) {
        int r = a.radius;
        for (int dx = -r; dx <= r; dx++) floorRow(w, a, dx);
    }
    /** One x-row of the floor disk (≈ 2r blocks). Cheap enough for one tick. */
    public static void floorRow(ServerWorld w, Arena a, int dx) {
        int r = a.radius;
        int rSq = r * r;
        int cx = a.center.getX(), cz = a.center.getZ();
        for (int dz = -r; dz <= r; dz++) {
            if (dx * dx + dz * dz > rSq) continue;
            int x = cx + dx, z = cz + dz;
            Block b = ((x + z) & 1) == 0 ? Blocks.POLISHED_DIORITE : Blocks.POLISHED_ANDESITE;
            if (dx == 0 || dz == 0) b = Blocks.SMOOTH_QUARTZ;
            set(w, x, a.floorY, z, b);
        }
    }
    public static void wallsAtY(ServerWorld w, Arena a, int y) {
        int r = a.radius;
        int rSq = r * r;
        int innerSq = (r - 1) * (r - 1);
        int cx = a.center.getX(), cz = a.center.getZ();
        int yMin = a.floorY + 1;
        int yMax = a.ceilY - 1;
        Block band;
        if (y - yMin <= 2) band = Blocks.STONE_BRICKS;
        else if (y == yMax) band = Blocks.CHISELED_STONE_BRICKS;
        else band = Blocks.GLASS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int dSq = dx * dx + dz * dz;
                if (dSq > rSq || dSq <= innerSq) continue;
                set(w, cx + dx, y, cz + dz, band);
            }
        }
    }
    public static void ceilingFullSeaLantern(ServerWorld w, Arena a) {
        int r = a.radius;
        for (int dx = -r; dx <= r; dx++) ceilingRow(w, a, dx);
    }
    /** One x-row of the ceiling disk (sea-lantern). */
    public static void ceilingRow(ServerWorld w, Arena a, int dx) {
        int r = a.radius;
        int rSq = r * r;
        int cx = a.center.getX(), cz = a.center.getZ();
        for (int dz = -r; dz <= r; dz++) {
            if (dx * dx + dz * dz > rSq) continue;
            set(w, cx + dx, a.ceilY, cz + dz, Blocks.SEA_LANTERN);
        }
    }
    public static void stoneRoof(ServerWorld w, Arena a) {
        int r = a.radius;
        for (int dx = -r; dx <= r; dx++) stoneRoofTopRow(w, a, dx);
        int yMin = a.ceilY + 1, roofY = a.ceilY + 3;
        for (int y = yMin; y < roofY; y++) stoneRoofRingAtY(w, a, y);
    }
    /** One x-row of the stone-brick top disk (3 blocks above ceiling). */
    public static void stoneRoofTopRow(ServerWorld w, Arena a, int dx) {
        int r = a.radius;
        int rSq = r * r;
        int cx = a.center.getX(), cz = a.center.getZ();
        int roofY = a.ceilY + 3;
        for (int dz = -r; dz <= r; dz++) {
            if (dx * dx + dz * dz > rSq) continue;
            set(w, cx + dx, roofY, cz + dz, Blocks.STONE_BRICKS);
        }
    }
    /** One Y-layer of the cylindrical side ring between ceiling and roof. */
    public static void stoneRoofRingAtY(ServerWorld w, Arena a, int y) {
        int r = a.radius;
        int rSq = r * r;
        int innerSq = (r - 1) * (r - 1);
        int cx = a.center.getX(), cz = a.center.getZ();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int dSq = dx * dx + dz * dz;
                if (dSq > rSq || dSq <= innerSq) continue;
                set(w, cx + dx, y, cz + dz, Blocks.STONE_BRICKS);
            }
        }
    }
    /**
     * Sparse grid of invisible {@link Blocks#LIGHT} source blocks throughout
     * the arena interior. Each LIGHT block emits level 15 light, has no
     * collision and is invisible — perfect to brighten huge arenas where
     * sea-lantern ceilings can't reach the floor (light only travels 14
     * blocks, but our arenas are radius 120 / height 55).
     *
     * Placed at 3 vertical layers (just above floor, mid, just below ceiling)
     * on a 12-block horizontal grid → ~ 21×21×3 = 1300 blocks per arena, all
     * placed in a single tick (cheap: no neighbour updates needed for LIGHT).
     */
    public static void interiorLights(ServerWorld w, Arena a) {
        int r = a.radius;
        int rSq = (r - 2) * (r - 2); // stay 2 blocks inside the wall
        int cx = a.center.getX(), cz = a.center.getZ();
        int spacing = 12;
        int yLo = a.floorY + 2;
        int yMid = (a.floorY + a.ceilY) / 2;
        int yHi = a.ceilY - 1;
        int[] ys = (yMid > yLo + 4 && yMid < yHi - 4) ? new int[]{yLo, yMid, yHi} : new int[]{yLo, yHi};
        for (int dx = -r; dx <= r; dx += spacing) {
            for (int dz = -r; dz <= r; dz += spacing) {
                if (dx * dx + dz * dz > rSq) continue;
                int x = cx + dx, z = cz + dz;
                for (int y : ys) set(w, x, y, z, Blocks.LIGHT);
            }
        }
    }
    public static void wallLanterns(ServerWorld w, Arena a) {
        int r = a.radius;
        int cx = a.center.getX(), cz = a.center.getZ();
        int y = a.floorY + 1;
        int n = 16;
        for (int i = 0; i < n; i++) {
            double ang = (2 * Math.PI * i) / n;
            int x = cx + (int) Math.round((r - 1) * Math.cos(ang));
            int z = cz + (int) Math.round((r - 1) * Math.sin(ang));
            set(w, x, y, z, Blocks.SEA_LANTERN);
        }
    }
    public static void mobSpawnPad(ServerWorld w, Arena a) {
        BlockPos c = a.mobSpawnCenter;
        int y = c.getY() - 1;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                set(w, c.getX() + dx, y, c.getZ() + dz, Blocks.POLISHED_BLACKSTONE);
        set(w, c.getX(), y, c.getZ(), Blocks.GILDED_BLACKSTONE);
    }
    public static void playerSpawnPad(ServerWorld w, Arena a) {
        int y = a.floorY;
        int sx = (int) Math.floor(a.spawnA.x);
        int sz = (int) Math.floor(a.spawnA.z);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                set(w, sx + dx, y, sz + dz, Blocks.EMERALD_BLOCK);
    }
    public static void deathRoom(ServerWorld w, Arena a) {
        int sx = (int) Math.floor(a.spectator.x);
        int sy = (int) Math.floor(a.spectator.y);
        int sz = (int) Math.floor(a.spectator.z);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    int x = sx + dx, y = sy + dy, z = sz + dz;
                    boolean isShellX = dx == -2 || dx == 2;
                    boolean isShellZ = dz == -2 || dz == 2;
                    boolean isShellY = dy == -1 || dy == 3;
                    if (isShellX || isShellZ || isShellY) {
                        boolean isCorner = (isShellX && isShellZ) || (isShellX && isShellY) || (isShellZ && isShellY);
                        Block b = isCorner ? Blocks.CHISELED_STONE_BRICKS : Blocks.GLASS;
                        set(w, x, y, z, b);
                    } else {
                        BlockPos p = new BlockPos(x, y, z);
                        w.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
                        ArenaProtection.remove(p);
                    }
                }
            }
        }
        set(w, sx, sy + 3, sz, Blocks.SEA_LANTERN);
    }
    private static void set(ServerWorld w, int x, int y, int z, Block b) {
        BlockPos p = new BlockPos(x, y, z);
        w.setBlockState(p, b.getDefaultState(), 2);
        if (b != Blocks.AIR) ArenaProtection.add(p);
        else ArenaProtection.remove(p);
    }
}
