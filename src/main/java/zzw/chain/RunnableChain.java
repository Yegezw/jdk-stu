package zzw.chain;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;

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
            if (!group.startAndAwait())
            {
                return false;
            }
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
