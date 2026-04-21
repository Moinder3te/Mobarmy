package mobarmy.lb.mobarmy.arena;

import mobarmy.lb.mobarmy.config.MobarmyConfig;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Cylindrical arena instance. All geometry is derived from {@link MobarmyConfig}'s
 * {@code arenaRadius}, {@code arenaFloorY} and {@code arenaHeight}, plus a per-arena
 * world offset so each team has its own arena far apart from the others.
 *
 *  - Floor: a filled disc of radius {@code radius} at Y={@code floorY}.
 *  - Walls: a 1-block ring at every Y between floor+1 and ceil-1.
 *  - Ceiling: filled disc of sea lanterns at Y={@code ceilY}.
 *  - Stone roof: filled disc 3 blocks above ceiling — kills skylight.
 *  - Mob-spawn pad in centre, attacker spawn pad on −X side, spectator cube above.
 */
public class Arena {
    public final BlockPos offset;
    public final int radius;
    public final int floorY;
    public final int ceilY;
    /** Centre of the floor (Y=floorY). */
    public final BlockPos center;
    public final Vec3d spawnA;
    public final Vec3d spawnB;
    public final Vec3d spectator;
    public final BlockPos mobSpawnCenter;

    public Arena(MobarmyConfig cfg) { this(cfg, BlockPos.ORIGIN); }

    public Arena(MobarmyConfig cfg, BlockPos offset) {
        this.offset = offset;
        this.radius = Math.max(8, cfg.arenaRadius);
        this.floorY = cfg.arenaFloorY;
        this.ceilY  = cfg.arenaFloorY + Math.max(8, cfg.arenaHeight);
        int cx = offset.getX();
        int cz = offset.getZ();
        this.center = new BlockPos(cx, floorY, cz);
        // Player spawn pads on opposite sides, 8 blocks in from the wall.
        int spawnInset = Math.max(8, radius - 12);
        this.spawnA = new Vec3d(cx - spawnInset + 0.5, floorY + 1, cz + 0.5);
        this.spawnB = new Vec3d(cx + spawnInset + 0.5, floorY + 1, cz + 0.5);
        this.mobSpawnCenter = new BlockPos(cx, floorY + 1, cz);
        // Spectator cube floats well above the stone roof.
        this.spectator = new Vec3d(cx + 0.5, ceilY + 12, cz + 0.5);
    }

    /** True if (x,y,z) lies inside the cylindrical interior (inclusive). */
    public boolean contains(double x, double y, double z) {
        if (y < floorY || y > ceilY + 4) return false;
        double dx = x - (center.getX() + 0.5);
        double dz = z - (center.getZ() + 0.5);
        return dx * dx + dz * dz <= (radius + 0.5) * (radius + 0.5);
    }

    /** Axis-aligned bounding box of the arena cylinder + roof gap. */
    public Box box() {
        return new Box(
            center.getX() - radius,     floorY,         center.getZ() - radius,
            center.getX() + radius + 1, ceilY + 5,      center.getZ() + radius + 1
        );
    }

    /** AABB minimum corner — convenience for code that does grid scans. */
    public BlockPos minCorner() {
        return new BlockPos(center.getX() - radius, floorY, center.getZ() - radius);
    }

    /** AABB maximum corner (inclusive of last block). */
    public BlockPos maxCorner() {
        return new BlockPos(center.getX() + radius, ceilY + 4, center.getZ() + radius);
    }

    /** Build the arena (cylindrical structure + lighting + pads). */
    public void build(ServerWorld world) {
        ArenaBuilder.build(world, this);
    }
}

