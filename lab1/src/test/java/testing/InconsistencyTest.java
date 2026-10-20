package testing;

import benchmark.ZipfGenerator;
import collector.MetricsCollector;
import collector.Snapshot;
import collector.StripedCollector;
import collector.SynchronizedCollector;
import collector.ThreadLocalCollector;
import collector.DoubleBufferedCollector;
import collector.BrokenDoubleBufferedCollector;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class InconsistencyTest {

    public static void main(String[] args) throws InterruptedException {
        long[] data = new ZipfGenerator().generate();
        if(args.length == 1 && args[0].equals("broken")){
            runTest(new BrokenDoubleBufferedCollector(), data, 4, 10_000, true);
            return;
        }
        runTest(new SynchronizedCollector(), data, 4, 10_000, true);
        runTest(new StripedCollector(), data, 4, 10_000, false);
        runTest(new ThreadLocalCollector(), data, 4, 10_000, false);
        runTest(new DoubleBufferedCollector(), data, 4, 10_000, true);
    }

    public static void runTest(MetricsCollector collector, long[] data,
                               int threadCount, int snapshotCount,
                               boolean expectConsistent) throws InterruptedException {
        if(threadCount <= 0 || snapshotCount <= 0 || data.length == 0){
            throw new IllegalArgumentException();
        }
        if(collector.snapshot().count() != 0){
            throw new IllegalArgumentException("Use a new collector");
        }

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(threadCount);
        AtomicBoolean stop = new AtomicBoolean(false);
        long[] operations = new long[threadCount];
        Throwable[] errors = new Throwable[threadCount];
        Thread[] threads = new Thread[threadCount];

        for(int i = 0; i < threads.length; i++){
            int index = i;
            threads[i] = new Thread(() -> {
                long localCount = 0;
                int position = (int) ((long) index * 1000 % data.length);
                try {
                    start.await();
                    while(!stop.get()){
                        collector.record(data[position]);
                        localCount++;
                        if(localCount == 1){
                            started.countDown();
                        }
                        position++;
                        if(position == data.length){
                            position = 0;
                        }
                    }
                } catch(Throwable e){
                    errors[index] = e;
                    stop.set(true);
                    if(e instanceof InterruptedException){
                        Thread.currentThread().interrupt();
                    }
                } finally {
                    operations[index] = localCount;
                }
            }, "writer-" + i);
            threads[i].setDaemon(true);
            threads[i].start();
        }

        int less = 0;
        int greater = 0;
        start.countDown();
        try {
            if(!started.await(10, TimeUnit.SECONDS)){
                throw new AssertionError("Writers did not start");
            }
            for(int i = 0; i < snapshotCount; i++){
                Snapshot snapshot = collector.snapshot();
                long bucketCount = sumBuckets(snapshot);
                if(bucketCount < snapshot.count()){
                    less++;
                } else if(bucketCount > snapshot.count()){
                    greater++;
                }
            }
        } finally {
            stop.set(true);
            for(Thread thread: threads){
                thread.join(10_000);
                if(thread.isAlive()){
                    throw new AssertionError("Writer did not finish: " + thread.getName());
                }
            }
        }

        long total = 0;
        for(int i = 0; i < operations.length; i++){
            if(errors[i] != null){
                throw new AssertionError("Writer failed", errors[i]);
            }
            total += operations[i];
        }
        Snapshot snapshot = collector.snapshot();
        long finalBuckets = sumBuckets(snapshot);
        System.out.println(collector.getClass().getSimpleName());
        System.out.println("writers = " + threadCount);
        System.out.println("snapshots = " + snapshotCount);
        System.out.println("sum(buckets) < count = " + less);
        System.out.println("sum(buckets) > count = " + greater);
        System.out.printf(Locale.ROOT, "inconsistent = %.2f%%%n",
                (less + greater) * 100.0 / snapshotCount);
        System.out.println("completed record calls = " + total);
        System.out.println("final count = " + snapshot.count());
        System.out.println("final sum(buckets) = " + finalBuckets);
        System.out.println("final count - calls = " + (snapshot.count() - total));

        if(snapshot.count() != total || finalBuckets != total){
            throw new AssertionError("Final statistics lost or duplicated records");
        }
        if(expectConsistent && less + greater != 0){
            throw new AssertionError("Expected consistent snapshots");
        }
        System.out.println("Final accounting: PASS");
        System.out.println();
    }

    private static long sumBuckets(Snapshot snapshot){
        long sum = 0;
        for(long value: snapshot.buckets()){
            sum += value;
        }
        return sum;
    }
}
