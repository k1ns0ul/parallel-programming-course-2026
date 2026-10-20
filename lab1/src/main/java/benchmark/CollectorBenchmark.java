package benchmark;

import collector.MetricsCollector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class CollectorBenchmark {

    private long runOnce(MetricsCollector collector, long[] data, AtomicBoolean stop, int indexArr){
        long counter = 0;
        while(!stop.get()){
            collector.record(data[indexArr]);
            counter++;
            indexArr++;
            if(indexArr == data.length){
                indexArr = 0;
            }
        }
        return counter;
    }

    public double measureOnce(MetricsCollector collector, long[] data, long wait, int threadCount) throws InterruptedException {
        if(wait <= 0 || threadCount <= 0 || data.length == 0){
            throw new IllegalArgumentException();
        }
        long[] array = new long[threadCount];
        Throwable[] errors = new Throwable[threadCount];
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        Thread[] t = new Thread[threadCount];

        for(int i = 0; i < t.length; i++){
            int finalI = i;
            t[i] = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    int position = (int) ((long) finalI * 1000 % data.length);
                    array[finalI] = runOnce(collector, data, stop, position);
                } catch(Throwable e){
                    errors[finalI] = e;
                    stop.set(true);
                    if(e instanceof InterruptedException){
                        Thread.currentThread().interrupt();
                    }
                }
            }, "benchmark-" + i);
            t[i].setDaemon(true);
            t[i].start();
        }

        long startTime = 0;
        long endTime = 0;
        try {
            ready.await();
            startTime = System.nanoTime();
            start.countDown();
            TimeUnit.NANOSECONDS.sleep(wait);
        } finally {
            stop.set(true);
            endTime = System.nanoTime();
            start.countDown();
            for(Thread thread: t){
                thread.join(30_000);
                if(thread.isAlive()){
                    throw new IllegalStateException("Worker did not finish");
                }
            }
        }

        long sum = 0;
        for(int i = 0; i < array.length; i++){
            if(errors[i] != null){
                throw new IllegalStateException("Worker failed", errors[i]);
            }
            sum += array[i];
        }
        double duration = (endTime - startTime) / 1_000_000_000.0;
        return sum / duration;
    }
}
