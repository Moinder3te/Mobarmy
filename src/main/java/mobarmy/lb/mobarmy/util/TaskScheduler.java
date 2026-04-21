package mobarmy.lb.mobarmy.util;

import java.util.ArrayDeque;
import java.util.Deque;

public class TaskScheduler {
    private record Task(long runAtTick, Runnable r) {}

    private final Deque<Task> tasks = new ArrayDeque<>();
    private long currentTick = 0L;

    public void schedule(int delayTicks, Runnable r) {
        tasks.add(new Task(currentTick + Math.max(0, delayTicks), r));
    }

    public void tick() {
        currentTick++;
        var it = tasks.iterator();
        while (it.hasNext()) {
            Task t = it.next();
            if (t.runAtTick <= currentTick) {
                it.remove();
                try { t.r.run(); } catch (Throwable th) { th.printStackTrace(); }
            }
        }
    }

    public void clear() { tasks.clear(); }
}

