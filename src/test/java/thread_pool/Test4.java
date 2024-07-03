package thread_pool;

import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveTask;

/**
 * ForkJoinPool 默认守护线程
 */
public class Test4
{

    static int[] nums = new int[1_000_000_000];

    static
    {
        for (int i = 0; i < nums.length; i++)
        {
            nums[i] = (int) ((Math.random()) * 1000);
        }
    }

    public static void main(String[] args)
    {
        System.out.println("开始");
        sum1();
        sum2();
    }

    private static void sum1()
    {
        long start = System.nanoTime();

        int sum = 0;
        for (int num : nums)
        {
            sum += num;
        }

        long end = System.nanoTime();
        System.out.println("单线程运算结果为: " + sum + ", 耗时: " + (end - start) + " ns");
    }

    private static void sum2()
    {
        // 在使用 ForkJoinPool 时不推荐使用 Runnable 和 Callable
        // Runnable -> RecursiveAction
        // Callable -> RecursiveTask
        ForkJoinPool pool = (ForkJoinPool) Executors.newWorkStealingPool();

        long start = System.nanoTime();

        ForkJoinTask<Integer> task = pool.submit(new SumRecursiveTask(0, nums.length - 1));
        Integer               sum  = task.join();

        long end = System.nanoTime();
        System.out.println("多线程运算结果为: " + sum + ", 耗时: " + (end - start) + " ns");
    }

    private static class SumRecursiveTask extends RecursiveTask<Integer>
    {
        private static final int MAX_STRIDE = 125_000_000;

        private final int l;
        private final int r;

        public SumRecursiveTask(int l, int r)
        {
            this.l = l;
            this.r = r;
        }

        @Override
        protected Integer compute()
        {
            // 在这个方法中，需要设置好任务拆分的逻辑以及聚合的逻辑
            int sum    = 0;
            int stride = r - l;

            if (stride <= MAX_STRIDE)
            {
                for (int i = l; i <= r; i++)
                {
                    sum += nums[i];
                }
            }
            else
            {
                int              mid   = l + (r - l) / 2;
                SumRecursiveTask task1 = new SumRecursiveTask(l, mid);
                SumRecursiveTask task2 = new SumRecursiveTask(mid + 1, r);
                task1.fork();
                task2.fork();
                sum = task1.join() + task2.join();
            }

            return sum;
        }
    }
}
