package thread_pool;

import zzw.Util;

import java.util.Random;
import java.util.concurrent.*;

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

        /*
         * ExecutorCompletionService 有成员变量 BlockingQueue completionQueue
         *
         * QueueingFuture extends FutureTask
         * {
         *     protected void done() { completionQueue.add(task); }
         * }
         *
         * public Future<V> submit(Callable callable)
         * {
         *     task         = new FutureTask(callable);
         *     queueingTask = new QueueingFuture(task);
         *     pool.execute(queueingTask);
         *     return task;
         * }
         *
         * public Future<V> poll() {
         *     return completionQueue.poll();
         * }
         */
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
