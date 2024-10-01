package zzw;

import java.util.Random;

public class Util
{

    private Util()
    {
    }

    public static void sleep(long millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException ignore)
        {
        }
    }

    private static final Random RANDOM = new Random();

    /**
     * 生成一个长度为 length 的随机数组, 每个数字的范围为 [0, bound]
     */
    public static int[] generateRandomArray(int length, int bound)
    {
        int[] arr = new int[length];
        for (int i = 0; i < length; i++)
        {
            arr[i] = RANDOM.nextInt(bound + 1);
        }
        return arr;
    }

    public static void isSorted(int[] arr)
    {
        for (int i = 0; i + 1 < arr.length; i++)
        {
            if (arr[i] > arr[i + 1]) throw new RuntimeException();
        }
    }
}
