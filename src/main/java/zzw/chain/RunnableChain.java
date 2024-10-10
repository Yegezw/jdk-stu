package zzw.chain;

import com.google.common.base.Stopwatch;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 执行链
 */
@Slf4j
public class RunnableChain
{

    private final String                           chainName;
    private final ExecutorService                  executorService;
    private final LinkedList<RunnableGroup>        groups;
    private final ConcurrentLinkedQueue<Throwable> throwableQueue;

    private final Stopwatch stopwatch = Stopwatch.createUnstarted();

    private RunnableChain(final String chainName, final ExecutorService executorService)
    {
        this.chainName       = chainName;
        this.executorService = executorService;
        this.groups          = new LinkedList<>();
        this.throwableQueue  = new ConcurrentLinkedQueue<>();
    }

    public static RunnableChain create(final String chainName, final ExecutorService executorService)
    {
        return new RunnableChain(chainName, executorService);
    }

    // =================================================================================================================

    public RunnableChain with(final String groupName, final Runnable... runnable)
    {
        groups.add(RunnableGroup.create(chainName, groupName, executorService, throwableQueue, runnable));
        return this;
    }

    /**
     * 阻塞方法
     */
    public boolean start()
    {
        for (RunnableGroup group : groups)
        {
            stopwatch.reset().start();
            boolean success = group.startAndAwait();
            long    times   = stopwatch.stop().elapsed(TimeUnit.MILLISECONDS);
            log.info("{}_{}_耗时 {} ms", group.info(), success ? "成功" : "失败", times);
            if (!success) return false;
        }
        return true;
    }

    @SuppressWarnings("all")
    public ConcurrentLinkedQueue<Throwable> getThrowableQueue()
    {
        return throwableQueue;
    }

    public void printErrorInfo()
    {
        for (Throwable throwable : throwableQueue)
        {
            log.error(throwable.getMessage(), throwable);
        }
    }
}
