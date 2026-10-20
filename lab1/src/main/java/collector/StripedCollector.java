package collector;

import java.util.concurrent.atomic.AtomicLong;

public class StripedCollector implements MetricsCollector {
    private long[] buckets = new long[256];
    private Object[] locks = new Object[16];
    private AtomicLong count = new AtomicLong(0);
    private AtomicLong sum = new AtomicLong(0);
    private AtomicLong min = new AtomicLong(Long.MAX_VALUE);
    private AtomicLong max = new AtomicLong(0);

    public StripedCollector(){
        for(int i = 0; i < locks.length; i++){
            locks[i] = new Object();
        }
    }

    @Override
    public void record(long value){
        int index = (int) Math.min(value / 4, 255);
        int lockIndex = index % 16;

        synchronized (locks[lockIndex]){
            buckets[index] += 1;
        }
        count.incrementAndGet();
        sum.addAndGet(value);
        while (true){
            long currentMin = min.get();
            if(value >= currentMin){
                break;
            }
            if(min.compareAndSet(currentMin, value)) {
                break;
            }
        }
        while (true){
            long currentMax = max.get();
            if(value <= currentMax){
                break;
            }
            if(max.compareAndSet(currentMax, value)) {
                break;
            }
        }

    }

    @Override
    public Snapshot snapshot(){
        long[] copy = new long[buckets.length];

        for(int i = 0; i < locks.length; i++){
            synchronized (locks[i]){
                for(int s = i; s < buckets.length; s += locks.length){
                    copy[s] = buckets[s];
                }
            }
        }

        long countLocal = count.get();
        long sumLocal = sum.get();
        long minLocal = min.get();
        long maxLocal = max.get();

        long snapshotMin = countLocal == 0 ? 0 : minLocal;
        long p50 = calculatePercentile(copy, countLocal, 0.50);
        long p99 = calculatePercentile(copy, countLocal, 0.99);

        Snapshot snapshot = new Snapshot(copy, countLocal, sumLocal, snapshotMin, maxLocal, p50, p99);
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
