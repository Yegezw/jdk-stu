package aqs.thread_pool;

public interface RejectedExecutionHandler
{

    void rejectedExecution(Runnable r, ThreadPoolExecutor executor);
}
