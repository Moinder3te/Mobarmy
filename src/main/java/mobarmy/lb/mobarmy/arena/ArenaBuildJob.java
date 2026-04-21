package mobarmy.lb.mobarmy.arena;

import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Chunked, tickable arena builder. Splits the heavy build into small steps so
 * we never block the server thread. Crucially, chunk generation is requested
 * ASYNCHRONOUSLY via chunk-loading tickets — we never call the blocking
 * {@code world.getChunk(x,z)} on the server thread (that was causing the
 * 60 s watchdog crash, because a single noise-gen call can park the server
 * thread for many seconds while ForkJoin workers chew through the chunk).
 */
public class ArenaBuildJob {
    private enum Stage { LOAD_CHUNKS, BUILD, DONE }

    /** Ticket radius: 2 → center chunk reaches level 31 (TICKING/FULL). */
    private static final int TICKET_RADIUS = 2;
    /** Use FORCED — it has no timeout (UNKNOWN expires after 1 tick and is
     *  rejected by addChunkLoadingTicket with IllegalStateException). */
    private static final ChunkTicketType TICKET_TYPE = ChunkTicketType.FORCED;

    private final ServerWorld world;
    private final List<Arena> arenas;
    private final Runnable onDone;

    // ---- LOAD_CHUNKS state ----
    private final List<ChunkPos> chunkQueue = new ArrayList<>();
    /** Chunks we hold tickets on; removed when the job finishes. */
    private final List<ChunkPos> heldTickets = new ArrayList<>();
    /** Future returned by addChunkLoadingTicket for each submitted chunk (parallel to chunkQueue). */
    private final List<CompletableFuture<?>> chunkFutures = new ArrayList<>();
    private int chunkIdx = 0;
    /** How many tickets we have already submitted (≥ chunkIdx). */
    private int chunkSubmitted = 0;
    /** Max outstanding (submitted but not yet FULL) chunks at once. */
    private static final int MAX_INFLIGHT = 24;

    // ---- BUILD state ----
    private int arenaIdx = 0;
    /**
     * 0 = clear (Y-layers)
     * 1 = floor (X-rows)
     * 2 = walls (Y-layers)
     * 3 = ceiling (X-rows)
     * 4 = roof top disk (X-rows)
     * 5 = roof side ring (Y-layers)
     * 6 = extras
     */
    private int phase = 0;
    /** Generic per-phase progress cursor (Y-layer index OR dx-row index). */
    private int yCursor = 0;

    private Stage stage = Stage.LOAD_CHUNKS;

    /** Approx. wall-clock budget per tick — once exceeded, yield to next tick. */
    private static final long TICK_BUDGET_NS = 25_000_000L; // ~25 ms

    public ArenaBuildJob(ServerWorld world, List<Arena> arenas, Runnable onDone) {
        this.world = world;
        this.arenas = arenas;
        this.onDone = onDone;
        // Pre-compute the list of chunks every arena footprint covers (deduplicated).
        java.util.LinkedHashSet<Long> seen = new java.util.LinkedHashSet<>();
        for (Arena a : arenas) {
            int rChunk = (a.radius >> 4) + 1;
            int cx0 = a.center.getX() >> 4;
            int cz0 = a.center.getZ() >> 4;
            for (int cx = cx0 - rChunk; cx <= cx0 + rChunk; cx++) {
                for (int cz = cz0 - rChunk; cz <= cz0 + rChunk; cz++) {
                    if (seen.add(ChunkPos.toLong(cx, cz))) {
                        chunkQueue.add(new ChunkPos(cx, cz));
                    }
                }
            }
        }
    }

    public boolean isDone() { return stage == Stage.DONE; }

    public int progressPercent() {
        int totalChunks = chunkQueue.size();
        if (stage == Stage.DONE) return 100;
        if (stage == Stage.LOAD_CHUNKS) {
            return totalChunks == 0 ? 50 : (chunkIdx * 50) / totalChunks;
        }
        if (arenas.isEmpty()) return 100;
        return 50 + (arenaIdx * 50) / arenas.size();
    }

    /** Do as much work as fits into the per-tick budget. */
    public void tick() {
        long deadline = System.nanoTime() + TICK_BUDGET_NS;
        boolean did = false;
        while (!did || System.nanoTime() < deadline) {
            if (stage == Stage.DONE) return;
            boolean progressed = step();
            did = true;
            if (!progressed) return; // yield until next tick (e.g. waiting on chunk gen)
        }
    }

    /** One unit of work. Returns true if something was actually done. */
    private boolean step() {
        switch (stage) {
            case LOAD_CHUNKS: return stepLoadChunks();
            case BUILD:       return stepBuild();
            default:          return false;
        }
    }

    private boolean stepLoadChunks() {
        ServerChunkManager scm = world.getChunkManager();

        // 1) Submit more tickets up to the in-flight cap.
        while (chunkSubmitted < chunkQueue.size()
                && (chunkSubmitted - chunkIdx) < MAX_INFLIGHT) {
            ChunkPos cp = chunkQueue.get(chunkSubmitted++);
            // Async loading ticket: schedules generation on worker threads and
            // returns a Future that completes once the chunk reaches FULL.
            // We must ALSO retain a long-lived ticket via addTicket so the
            // chunk doesn't get unloaded between LOAD_CHUNKS and BUILD.
            CompletableFuture<?> fut = scm.addChunkLoadingTicket(
                TICKET_TYPE, cp, TICKET_RADIUS);
            scm.addTicket(TICKET_TYPE, cp, TICKET_RADIUS);
            chunkFutures.add(fut);
            heldTickets.add(cp);
        }

        // 2) Advance chunkIdx past any chunks whose load future has completed.
        // This is fully non-blocking — we never call a method that parks the
        // server thread waiting on chunk gen workers.
        boolean advanced = false;
        while (chunkIdx < chunkSubmitted) {
            CompletableFuture<?> fut = chunkFutures.get(chunkIdx);
            if (fut != null && !fut.isDone()) break;
            chunkIdx++;
            advanced = true;
        }

        // 3) Done loading?
        if (chunkIdx >= chunkQueue.size()) {
            stage = Stage.BUILD;
            return true;
        }

        // If nothing advanced this step, yield — chunk gen is in flight on workers.
        return advanced;
    }

    private boolean stepBuild() {
        if (arenaIdx >= arenas.size()) {
            stage = Stage.DONE;
            // Release all chunk tickets we were holding.
            ServerChunkManager scm = world.getChunkManager();
            for (ChunkPos cp : heldTickets) {
                try { scm.removeTicket(TICKET_TYPE, cp, TICKET_RADIUS); } catch (Throwable ignored) {}
            }
            heldTickets.clear();
            if (onDone != null) {
                try { onDone.run(); } catch (Throwable ignored) {}
            }
            return false;
        }
        Arena a = arenas.get(arenaIdx);
        switch (phase) {
            case 0: { // Clear interior layer by layer
                int y = a.floorY + yCursor;
                ArenaBuilder.clearLayer(world, a, y);
                yCursor++;
                if (a.floorY + yCursor > a.ceilY + 4) { phase = 1; yCursor = 0; }
                return true;
            }
            case 1: { // Floor disk row by row (dx = -r .. r)
                int dx = -a.radius + yCursor;
                ArenaBuilder.floorRow(world, a, dx);
                yCursor++;
                if (dx >= a.radius) { phase = 2; yCursor = 0; }
                return true;
            }
            case 2: { // Walls layer by layer
                int y = a.floorY + 1 + yCursor;
                ArenaBuilder.wallsAtY(world, a, y);
                yCursor++;
                if (a.floorY + 1 + yCursor > a.ceilY - 1) { phase = 3; yCursor = 0; }
                return true;
            }
            case 3: { // Ceiling disk row by row
                int dx = -a.radius + yCursor;
                ArenaBuilder.ceilingRow(world, a, dx);
                yCursor++;
                if (dx >= a.radius) { phase = 4; yCursor = 0; }
                return true;
            }
            case 4: { // Roof top disk row by row
                int dx = -a.radius + yCursor;
                ArenaBuilder.stoneRoofTopRow(world, a, dx);
                yCursor++;
                if (dx >= a.radius) { phase = 5; yCursor = 0; }
                return true;
            }
            case 5: { // Roof side ring layer by layer (ceilY+1 .. ceilY+2)
                int y = a.ceilY + 1 + yCursor;
                ArenaBuilder.stoneRoofRingAtY(world, a, y);
                yCursor++;
                if (y >= a.ceilY + 2) { phase = 6; yCursor = 0; }
                return true;
            }
            case 6: {
                ArenaBuilder.wallLanterns(world, a);
                ArenaBuilder.interiorLights(world, a);
                ArenaBuilder.mobSpawnPad(world, a);
                ArenaBuilder.playerSpawnPad(world, a);
                ArenaBuilder.deathRoom(world, a);
                phase = 0;
                yCursor = 0;
                arenaIdx++;
                return true;
            }
            default: phase = 0; return true;
        }
    }
}
