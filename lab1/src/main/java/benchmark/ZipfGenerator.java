package benchmark;

import java.util.Random;

public class ZipfGenerator {

    public double[] createCumulativeProbabilities(){
        double[] array = new double[1023];
        double weight = 0;
        double sum = 0;
        double ver = 0;
        double cumulative = 0;


        for(int k = 1; k < 1024; k++){
            weight = 1 / Math.pow(k, 1.15);
            sum += weight;
        }

        for(int k = 1; k < 1024; k++){
            ver = (1 / Math.pow(k, 1.15)) / sum;
            cumulative += ver;
            array[k - 1] = cumulative;
        }
        array[array.length - 1] = 1.0;

        return array;
    }

    public int findValue(double[] array, double r){
        for(int i = 0; i < array.length; i++){
            if(array[i] > r){
                return i + 1;
            }
        }
        throw new IllegalArgumentException();
    }

    public long[] generate(){
        double[] array = createCumulativeProbabilities();
        Random random = new Random(42);
        long[] lArr = new long[1 << 20];
        for(int i = 0; i < lArr.length; i++){
            double r = random.nextDouble();
            int val = findValue(array, r);
            lArr[i] = val;
        }
        return lArr;
    }


}
