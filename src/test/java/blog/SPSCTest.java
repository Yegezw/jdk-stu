package blog;

import aqs.synchronization_tool.CountDownLatch;
import aqs.synchronization_tool.CyclicBarrier;

/**
 * 测试 SPSCRiskyQueue
 */
public class SPSCTest
{
    private static final int           COUNT   = 10000;
    private static final long          RESULT  = (COUNT - 1) * COUNT / 2;
    private static final CyclicBarrier BARRIER = new CyclicBarrier(2);

    private static void runTest() throws InterruptedException
    {
        final SPSCRiskyQueue<Integer> queue = new SPSCRiskyQueue<>();
        final long[]                  sum   = {0L};

        final CountDownLatch end = new CountDownLatch(2);
        // 生产者
        new Thread(
                () ->
                {
                    try
                    {
                        BARRIER.await();
                        for (int i = 0; i < COUNT; i++)
                        {
                            queue.push(i);
                        }
                        end.countDown();
                    }
                    catch (Exception ignored)
                    {
                    }
                }
        ).start();
        // 消费者
        new Thread(
                () ->
                {
                    try
                    {
                        BARRIER.await();
                        for (int i = 0; i < COUNT; i++)
                        {
                            sum[0] += queue.pop();
                        }
                        end.countDown();
                    }
                    catch (Exception ignored)
                    {
                    }
                }
        ).start();
        // 主线程等待
        end.await();

        final long pushed = queue.getPushedCount();
        System.out.printf("sum: %s, pushed: %s\n", sum[0], pushed);
        if (sum[0] != RESULT || pushed != COUNT)
        {
            throw new IllegalStateException("Concurrency test failed");
        }

        BARRIER.reset();
    }

    public static void main(String[] args) throws InterruptedException
    {
        for (int i = 0; i < 10000; i++) runTest();
    }
}
