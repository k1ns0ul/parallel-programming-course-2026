import benchmark.CollectorBenchmark;
import benchmark.ZipfGenerator;
import collector.*;

import java.util.Arrays;
import java.util.Locale;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        if(args.length != 2){
            System.out.println("Usage: Main single|empty|synchronized|striped|threadlocal|double <threads>");
            return;
        }
        String name = args[0];
        int threadCount = Integer.parseInt(args[1]);
        if(threadCount < 1 || threadCount > Runtime.getRuntime().availableProcessors()){
            throw new IllegalArgumentException("Invalid thread count");
        }
        if(name.equals("single") && threadCount != 1){
            throw new IllegalArgumentException("SingleThreadCollector requires one thread");
        }
        long[] data = new ZipfGenerator().generate();
        CollectorBenchmark benchmark = new CollectorBenchmark();
        MetricsCollector warmup = createCollector(name);
        benchmark.measureOnce(warmup, data, 5_000_000_000L, threadCount);
        System.out.println("warmup count = " + warmup.snapshot().count());

        double[] results = new double[5];
        for(int i = 0; i < results.length; i++){
            MetricsCollector collector = createCollector(name);
            results[i] = benchmark.measureOnce(collector, data, 5_000_000_000L, threadCount);
            System.out.printf(Locale.ROOT, "RUN,%s,%d,%d,%.6f,%d%n",
                    name, threadCount, i + 1, results[i], collector.snapshot().count());
        }
        Arrays.sort(results);
        System.out.printf(Locale.ROOT, "MEDIAN,%s,%d,%.6f%n", name, threadCount, results[2]);
    }

    private static MetricsCollector createCollector(String name){
        return switch(name){
            case "single" -> new SingleThreadCollector();
            case "empty" -> new EmptyLockCollector();
            case "synchronized" -> new SynchronizedCollector();
            case "striped" -> new StripedCollector();
            case "threadlocal" -> new ThreadLocalCollector();
            case "double" -> new DoubleBufferedCollector();
            default -> throw new IllegalArgumentException("Unknown collector: " + name);
        };
    }
}
