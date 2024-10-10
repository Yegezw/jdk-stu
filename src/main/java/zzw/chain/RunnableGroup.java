package zzw.chain;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

/**
 * 执行组
 */
public class RunnableGroup
{

    private static class RunnableGroupException extends RuntimeException
    {
        public RunnableGroupException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    // =================================================================================================================

    private class CountDownRunnable implements Runnable
    {
        private final CountDownLatch latch;
        private final Runnable       runnable;

        public CountDownRunnable(CountDownLatch latch, Runnable runnable)
        {
            this.latch    = latch;
            this.runnable = runnable;
        }

        @Override
        public void run()
        {
            try
            {
                runnable.run();
            }
            catch (Throwable x)
            {
                throwableQueue.add(new RunnableGroupException(chainName + "-" + groupName + "-" + "异常", x));
            }
            finally
            {
                latch.countDown();
            }
        }
    }

    // =================================================================================================================

    private final String                           chainName;
    private final String                           groupName;
    private final CountDownLatch                   latch;
    private final ExecutorService                  executorService;
    private final CountDownRunnable[]              countDownRunnableArr;
    private final ConcurrentLinkedQueue<Throwable> throwableQueue;

    private RunnableGroup(final String chainName, final String groupName,
                          final ExecutorService executorService,
                          final ConcurrentLinkedQueue<Throwable> throwableQueue,
                          final Runnable... runnable)
    {
        final int size = runnable.length;

        this.chainName            = chainName;
        this.groupName            = groupName;
        this.latch                = new CountDownLatch(size);
        this.executorService      = executorService;
        this.countDownRunnableArr = new CountDownRunnable[size];
        for (int i = 0; i < size; i++)
        {
            countDownRunnableArr[i] = new CountDownRunnable(latch, runnable[i]);
        }
        this.throwableQueue = throwableQueue;
    }

    public static RunnableGroup create(final String chainName, final String groupName,
                                       final ExecutorService executorService,
                                       final ConcurrentLinkedQueue<Throwable> throwableQueue,
                                       final Runnable... runnable)
    {
        return new RunnableGroup(chainName, groupName, executorService, throwableQueue, runnable);
    }

    public static RunnableGroup create(final String groupName,
                                       final ExecutorService executorService, final Runnable... runnable)
    {
        return new RunnableGroup("", groupName, executorService, new ConcurrentLinkedQueue<>(), runnable);
    }

    private void await()
    {
        try
        {
            latch.await();
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException(e);
        }
    }

    public String info()
    {
        return chainName + "_" + groupName;
    }

    // =================================================================================================================

    public boolean startAndAwait()
    {
        start();
        return success();
    }

    public RunnableGroup start()
    {
        for (final CountDownRunnable runnable : countDownRunnableArr)
        {
            executorService.execute(runnable);
        }
        return this;
    }

    public boolean success()
    {
        await();
        return getThrowableQueue().isEmpty();
    }

    @SuppressWarnings("all")
    public ConcurrentLinkedQueue<Throwable> getThrowableQueue()
    {
        return throwableQueue;
    }
}
