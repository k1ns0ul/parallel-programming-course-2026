package collector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class DoubleBufferedCollector implements MetricsCollector{

    private static class State {
        private final Buffer[] buffers = {
                new Buffer(),
                new Buffer()
        };
        private final AtomicInteger inside = new AtomicInteger(-1);
    }

    private static class Buffer {
        private long[] buckets = new long[256];
        private long count = 0;
        private long sum = 0;
        private long min = Long.MAX_VALUE;
        private long max = 0;
    }

    private volatile int active = 0;
    private final List<State> list = new ArrayList<>();
    private final Object snapLock = new Object();
    private final Buffer total = new Buffer();

    private final ThreadLocal<State> tLocal = ThreadLocal.withInitial(() -> {
        State state = new State();
        synchronized (snapLock){
            list.add(state);
        }
        return state;
    });

    @Override
    public void record(long value){
        State recState = tLocal.get();
        while(true){
            int b = active;
            recState.inside.set(b);
            int bProm = active;
            if(bProm != b){
                recState.inside.setRelease(-1);
                continue;
            }

            try {
                Buffer buffer = recState.buffers[b];
                int index = (int) Math.min(value / 4, 255);

                buffer.buckets[index]++;
                buffer.count++;
                buffer.sum += value;

                if(value < buffer.min){
                    buffer.min = value;
                }
                if(value > buffer.max){
                    buffer.max = value;
                }
            } finally {
                recState.inside.setRelease(-1);
            }

            return;
        }
    }

    @Override
    public Snapshot snapshot(){
        synchronized (snapLock){ // не можем одноврменно выполнять другой снимок и регистрировать новое состояние в list.
            int curActive = active;
            active = 1 - curActive;
            for(State state: list){
                while(state.inside.get() == curActive){
                    Thread.onSpinWait();
                }

                Buffer buffer = state.buffers[curActive];

                for(int i = 0; i < total.buckets.length; i++){
                    total.buckets[i] += buffer.buckets[i];
                    buffer.buckets[i] = 0;
                }

                total.count += buffer.count;
                total.sum += buffer.sum;

                if(buffer.min < total.min){
                    total.min = buffer.min;
                }
                if(buffer.max > total.max){
                    total.max = buffer.max;
                }

                buffer.count = 0;
                buffer.sum = 0;
                buffer.min = Long.MAX_VALUE;
                buffer.max = 0;
            }

            long[] copy = total.buckets.clone();
            long snapshotMin = total.count == 0 ? 0 : total.min;
            long p50 = calculatePercentile(copy, total.count, 0.50);
            long p99 = calculatePercentile(copy, total.count, 0.99);

            Snapshot snapshot = new Snapshot(
                    copy, total.count, total.sum, snapshotMin, total.max, p50, p99
            );
            return snapshot;

        }
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
