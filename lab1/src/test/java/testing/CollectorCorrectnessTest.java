package testing;

import collector.*;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

public class CollectorCorrectnessTest {
    private static final long[] VALUES = {0, 1, 3, 4, 5, 7, 8, 10, 1023, 1024, 4096};

    public static void main(String[] args) throws InterruptedException {
        verify(SingleThreadCollector::new, false);
        verify(SynchronizedCollector::new, true);
        verify(StripedCollector::new, true);
        verify(ThreadLocalCollector::new, true);
        verify(DoubleBufferedCollector::new, true);
        simultaneousReaders(SynchronizedCollector::new);
        simultaneousReaders(DoubleBufferedCollector::new);
        System.out.println("All correctness checks: PASS");
    }

    private static void verify(Supplier<MetricsCollector> factory, boolean concurrent) throws InterruptedException {
        MetricsCollector collector = factory.get();
        check(collector.snapshot(), 0, 0, 0, 0, new long[256], 0, 0);
        collector.record(5);
        collector.record(7);
        collector.record(10);
        Snapshot saved = collector.snapshot();
        long[] expected = new long[256];
        expected[1] = 2;
        expected[2] = 1;
        check(saved, 3, 22, 5, 10, expected, 4, 8);
        collector.record(6);
        Snapshot next = collector.snapshot();
        require(next.count() == 4 && next.sum() == 28, "Cumulative snapshot");
        check(saved, 3, 22, 5, 10, expected, 4, 8);
        next.buckets()[1] = -100;
        require(collector.snapshot().buckets()[1] == 3, "Snapshot must not expose internal buckets");
        collector.record(2);
        require(collector.snapshot().min() == 2, "Minimum after buffer reuse");
        require(collector.snapshot().count() == 5, "Repeated snapshot must not duplicate records");

        MetricsCollector target = factory.get();
        int writers = concurrent ? 4 : 1;
        int repetitions = 2000;
        Thread[] threads = new Thread[writers];
        Throwable[] errors = new Throwable[writers];
        CountDownLatch start = new CountDownLatch(1);
        for(int i = 0; i < writers; i++){
            int index = i;
            threads[i] = new Thread(() -> {
                try {
                    start.await();
                    for(int j = 0; j < repetitions; j++){
                        for(long value: VALUES){
                            target.record(value);
                        }
                    }
                } catch(Throwable e){
                    errors[index] = e;
                }
            });
            threads[i].setDaemon(true);
            threads[i].start();
        }
        start.countDown();
        joinAll(threads, errors);
        long[] buckets = new long[256];
        long sum = 0;
        for(long value: VALUES){
            buckets[(int) Math.min(value / 4, 255)] += (long) writers * repetitions;
            sum += value * writers * repetitions;
        }
        check(target.snapshot(), (long) VALUES.length * writers * repetitions,
                sum, 0, 4096, buckets, 4, 1020);
        System.out.println(target.getClass().getSimpleName() + ": PASS");
    }

    private static void simultaneousReaders(Supplier<MetricsCollector> factory) throws InterruptedException {
        MetricsCollector collector = factory.get();
        Thread[] threads = new Thread[6];
        Throwable[] errors = new Throwable[6];
        CountDownLatch start = new CountDownLatch(1);
        for(int i = 0; i < threads.length; i++){
            int index = i;
            threads[i] = new Thread(() -> {
                try {
                    start.await();
                    if(index < 4){
                        for(int j = 0; j < 25000; j++){
                            collector.record(7);
                        }
                    } else {
                        for(int j = 0; j < 1000; j++){
                            Snapshot s = collector.snapshot();
                            long[] buckets = new long[256];
                            buckets[1] = s.count();
                            long edge = s.count() == 0 ? 0 : 7;
                            long percentile = s.count() == 0 ? 0 : 4;
                            check(s, s.count(), s.count() * 7, edge, edge,
                                    buckets, percentile, percentile);
                        }
                    }
                } catch(Throwable e){
                    errors[index] = e;
                }
            });
            threads[i].setDaemon(true);
            threads[i].start();
        }
        start.countDown();
        joinAll(threads, errors);
        long[] buckets = new long[256];
        buckets[1] = 100000;
        check(collector.snapshot(), 100000, 700000, 7, 7, buckets, 4, 4);
        System.out.println(collector.getClass().getSimpleName() + " concurrent readers: PASS");
    }

    private static void joinAll(Thread[] threads, Throwable[] errors) throws InterruptedException {
        for(int i = 0; i < threads.length; i++){
            threads[i].join(15000);
            require(!threads[i].isAlive(), "Thread timeout");
            if(errors[i] != null){
                throw new AssertionError("Worker failed", errors[i]);
            }
        }
    }

    private static void check(Snapshot s, long count, long sum, long min, long max,
                              long[] buckets, long p50, long p99){
        require(s.count() == count, "count");
        require(s.sum() == sum, "sum");
        require(s.min() == min && s.max() == max, "min/max");
        require(Arrays.equals(s.buckets(), buckets), "buckets");
        require(s.p50() == p50 && s.p99() == p99, "percentiles");
    }

    private static void require(boolean value, String message){
        if(!value){
            throw new AssertionError(message);
        }
    }
}
