package mobarmy.lb.mobarmy.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class TaskScheduler {
    private record Task(long runAtTick, Runnable r) {}

    private final Deque<Task> tasks = new ArrayDeque<>();
    private long currentTick = 0L;

    public void schedule(int delayTicks, Runnable r) {
        tasks.add(new Task(currentTick + Math.max(0, delayTicks), r));
    }

    public void tick() {
        currentTick++;
        // Collect due tasks first, then run — avoids ConcurrentModificationException
        // if a running task calls schedule() (which adds to the same deque).
        List<Runnable> ready = new ArrayList<>();
        var it = tasks.iterator();
        while (it.hasNext()) {
            Task t = it.next();
            if (t.runAtTick <= currentTick) {
                it.remove();
                ready.add(t.r);
            }
        }
        for (Runnable r : ready) {
            try { r.run(); } catch (Throwable th) { th.printStackTrace(); }
        }
    }

    public void clear() { tasks.clear(); }
}

