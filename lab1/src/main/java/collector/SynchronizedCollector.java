package collector;

public class SynchronizedCollector implements MetricsCollector{

    private long[] buckets = new long[256];
    private long count = 0;
    private long sum = 0;
    private long min = Long.MAX_VALUE;
    private long max = 0;

    @Override
    public synchronized void record(long value) {
        int index = (int) Math.min(value / 4, 255);
        buckets[index] += 1;
        count++;
        sum += value;
        if(value < min){
            min = value;
        }
        if (value > max){
            max = value;
        }
    }

    @Override
    public synchronized Snapshot snapshot() {
        long[] copy = buckets.clone();
        long snapshotMin = count == 0 ? 0 : min;
        long p50 = calculatePercentile(copy, count, 0.50);
        long p99 = calculatePercentile(copy, count, 0.99);

        Snapshot snapshot = new Snapshot(copy, count, sum, snapshotMin, max, p50, p99);
        return snapshot;
    }

    private long calculatePercentile(long[] buckets, long count, double fraction){
        if(count == 0){
            return 0;
        }
        double prg = count * fraction;
        long accumulated = 0;
        int res = 0;
        for(int i = 0; i < buckets.length; i++){
            accumulated += buckets[i];
            if (accumulated >= prg){
                res = i * 4;
                break;
            }
        }
        return res;
    }
}
