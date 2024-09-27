package thread_pool;

import zzw.Util;

import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import aqs.thread_pool.ExecutorCompletionService;

/**
 * 完成服务 CompletionService
 */
public class Test3
{

    private static final Random RANDOM = new Random();

    public static void main(String[] args) throws Exception
    {
        // 线程池
        ExecutorService pool = Executors.newFixedThreadPool(5);
        // 完成服务
        CompletionService<Integer> service = new ExecutorCompletionService<>(pool);

        for (int i = 0; i < 5; i++)
        {
            final int res = i;
            service.submit(
                    () ->
                    {
                        Util.sleep(RANDOM.nextInt(5) * 1000);
                        return res;
                    }
            );
        }

        while (true)
        {
            Future<Integer> task = service.poll(5, TimeUnit.SECONDS);
            if (task == null)
            {
                pool.shutdownNow();
                break;
            }
            System.out.println(task.get());
        }
    }
}
