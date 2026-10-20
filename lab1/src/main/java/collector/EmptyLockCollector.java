package collector;

public class EmptyLockCollector implements MetricsCollector {

    private long[] buckets = new long[256];

    @Override
    public synchronized void record(long value) {

    }

    @Override
    public synchronized Snapshot snapshot() {
        long[] copy = buckets.clone();
        Snapshot snapshot = new Snapshot(copy, 0, 0, 0, 0, 0, 0);
        return snapshot;
    }
}
