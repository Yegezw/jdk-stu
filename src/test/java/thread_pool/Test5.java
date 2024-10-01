package thread_pool;

import com.google.common.base.Stopwatch;
import zzw.Util;

import java.util.Random;
import java.util.concurrent.*;

/**
 * ForkJoinPool 归并排序 VS 快速排序
 */
public class Test5
{

    private static final Random RANDOM = new Random();

    /**
     * 秒表
     */
    private static final Stopwatch STOPWATCH = Stopwatch.createUnstarted();

    private enum SortEnum
    {
        MergeSort,
        QuickSort;
    }

    private static void insertionSort(int[] arr, int l, int r)
    {
        for (int i = l + 1; i <= r; i++)
        {
            int k = arr[i];
            int j;
            for (j = i; j - 1 >= l && arr[j - 1] > k; j--)
            {
                arr[j] = arr[j - 1];
            }
            arr[j] = k;
        }
    }

    // =================================================================================================================

    private static void mergeSort(int[] arr, int l, int r, int[] temp)
    {
        if (r - l <= 63)
        {
            insertionSort(arr, l, r);
            return;
        }

        int mid = l + (r - l) / 2;
        mergeSort(arr, l, mid, temp);
        mergeSort(arr, mid + 1, r, temp);

        if (arr[mid] > arr[mid + 1]) merge(arr, l, mid, r, temp);
    }

    private static void merge(int[] arr, int l, int mid, int r, int[] temp)
    {
        System.arraycopy(arr, l, temp, l, r - l + 1);

        int p1 = l;
        int p2 = mid + 1;
        int i  = l;

        while (p1 <= mid && p2 <= r) arr[i++] = temp[p1] <= temp[p2] ? temp[p1++] : temp[p2++];
        while (p1 <= mid) arr[i++] = temp[p1++];
        while (p2 <= r) arr[i++] = temp[p2++];
    }

    private static class MergerSort extends RecursiveAction
    {
        int[] arr;
        int   l;
        int   r;
        int[] temp;

        public MergerSort(int[] arr, int l, int r, int[] temp)
        {
            this.arr  = arr;
            this.l    = l;
            this.r    = r;
            this.temp = temp;
        }

        @Override
        protected void compute()
        {
            if (r - l + 1 <= 1_000_000)
            {
                mergeSort(arr, l, r, temp);
                return;
            }

            int        mid   = l + (r - l) / 2;
            MergerSort task1 = new MergerSort(arr, l, mid, temp);
            MergerSort task2 = new MergerSort(arr, mid + 1, r, temp);

            invokeAll(task1, task2); // 阻塞
            merge(arr, l, mid, r, temp);
        }
    }

    // =================================================================================================================

    private static void quickSort(int[] arr, int l, int r)
    {
        if (r - l <= 63)
        {
            insertionSort(arr, l, r);
            return;
        }

        int p = partition(arr, l, r);

        quickSort(arr, l, p - 1);
        quickSort(arr, p + 1, r);
    }

    private static int partition(int[] arr, int l, int r)
    {
        int p = RANDOM.nextInt(r - l + 1) + l;
        swap(arr, l, p);

        int v  = arr[l];
        int p1 = l + 1;
        int p2 = r;

        while (true)
        {
            while (p1 <= p2 && arr[p1] < v) p1++;
            while (p1 <= p2 && arr[p2] > v) p2--;

            if (p1 >= p2) break;

            swap(arr, p1++, p2--);
        }

        swap(arr, l, p2);
        return p2;
    }

    private static void swap(int[] arr, int a, int b)
    {
        int temp = arr[a];
        arr[a] = arr[b];
        arr[b] = temp;
    }

    private static class QuickSort extends RecursiveAction
    {

        int[] arr;
        int   l;
        int   r;

        public QuickSort(int[] arr, int l, int r)
        {
            this.arr = arr;
            this.l   = l;
            this.r   = r;
        }

        @Override
        protected void compute()
        {
            if (r - l + 1 <= 1_000_000)
            {
                quickSort(arr, l, r);
                return;
            }

            int p = partition(arr, l, r);

            QuickSort task1 = new QuickSort(arr, l, p - 1);
            QuickSort task2 = new QuickSort(arr, p + 1, r);

            invokeAll(task1, task2); // 阻塞
        }
    }

    // =================================================================================================================

    private static void testSingleThread(SortEnum sortName)
    {
        int   n   = 1_000_000_000;
        int[] arr = Util.generateRandomArray(n, n);

        STOPWATCH.reset().start();

        switch (sortName)
        {
            case MergeSort ->
            {
                int[] temp = new int[n];
                mergeSort(arr, 0, n - 1, temp);
            }
            case QuickSort -> quickSort(arr, 0, n - 1);
        }

        long time = STOPWATCH.stop().elapsed(TimeUnit.NANOSECONDS);

        Util.isSorted(arr);
        System.out.println(time / 1_000_000_000.0);
    }

    private static void testMultiThread(SortEnum sortName)
    {
        int          n    = 1_000_000_000;
        int[]        arr  = Util.generateRandomArray(n, n);
        ForkJoinPool pool = (ForkJoinPool) Executors.newWorkStealingPool();

        STOPWATCH.reset().start();

        switch (sortName)
        {
            case MergeSort ->
            {
                int[]      temp   = new int[n];
                MergerSort action = new MergerSort(arr, 0, n - 1, temp);
                pool.invoke(action); // 阻塞
            }
            case QuickSort ->
            {
                QuickSort action = new QuickSort(arr, 0, n - 1);
                pool.invoke(action); // 阻塞
            }
        }

        long time = STOPWATCH.stop().elapsed(TimeUnit.NANOSECONDS);

        pool.shutdown();
        Util.isSorted(arr);
        System.out.println(time / 1_000_000_000.0);
    }

    // =================================================================================================================

    public static void main(String[] args) throws InterruptedException
    {
        System.out.println(Runtime.getRuntime().availableProcessors()); // 20

        testSingleThread(SortEnum.MergeSort); // 112
        testSingleThread(SortEnum.QuickSort); // 99

        testMultiThread(SortEnum.MergeSort);  // 21
        testMultiThread(SortEnum.QuickSort);  // 14
    }
}
