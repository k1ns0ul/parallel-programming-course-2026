package collector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

public class ThreadLocalCollector implements MetricsCollector {
    private static class State {
        private AtomicLongArray buckets = new AtomicLongArray(256);
        private AtomicLong count = new AtomicLong(0);
        private AtomicLong sum = new AtomicLong(0);
        private AtomicLong min = new AtomicLong(Long.MAX_VALUE);
        private AtomicLong max = new AtomicLong(0);
    }
    private final List<State> list = new ArrayList<>();
    private final Object lock = new Object();
    private final ThreadLocal<State> tLocal = ThreadLocal.withInitial(() -> {
        State state = new State();
        synchronized (lock) {
            list.add(state);
        }
        return state;
    });

    @Override
    public void record(long value) {
        State recState = tLocal.get();
        int index = (int) Math.min(value / 4, 255);

        long currentBucket = recState.buckets.getPlain(index);
        recState.buckets.setRelease(index, currentBucket + 1);

        long currentCount = recState.count.getPlain();
        recState.count.setRelease(currentCount + 1);

        long currentSum = recState.sum.getPlain();
        recState.sum.setRelease(currentSum + value);

        long currentMin = recState.min.getPlain();
        if(value < currentMin){
            recState.min.setRelease(value);
        }

        long currentMax = recState.max.getPlain();
        if(value > currentMax){
            recState.max.setRelease(value);
        }
    }

    @Override
    public Snapshot snapshot(){
        List<State> copyList;
        synchronized (lock){
            copyList = new ArrayList<>(list);
        }

        long[] copy = new long[256];
        long countLocal = 0;
        long sumLocal = 0;
        long minLocal = Long.MAX_VALUE;
        long maxLocal = 0;

        for(State state: copyList){
            for(int i = 0; i < copy.length; i++){
                copy[i] += state.buckets.get(i);
            }

            countLocal += state.count.get();
            sumLocal += state.sum.get();

            long currentMin = state.min.get();
            if(currentMin < minLocal){
                minLocal = currentMin;
            }

            long currentMax = state.max.get();
            if(currentMax > maxLocal){
                maxLocal = currentMax;
            }
        }

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
