package zzw.problem;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 计数器
 */
public class Test4
{

    private static final AtomicInteger counter = new AtomicInteger(0);

    public static void main(String[] args) throws Exception
    {
        // 100 个线程
        ExecutorService pool = Executors.newFixedThreadPool(100);

        // 100 个任务
        for (int count = 0; count < 100; count++)
        {
            pool.submit(() ->
            {
                for (int i = 0; i < 100; i++)
                {
                    counter.incrementAndGet();
                }
            });
        }

        // 等待所有线程执行完毕
        pool.shutdown();
        boolean finish = false;
        while (!finish)
        {
            finish = pool.awaitTermination(1, TimeUnit.MINUTES);
        }

        // 输出结果 10000
        System.out.println(counter.get());
    }
}
