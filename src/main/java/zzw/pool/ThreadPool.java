package zzw.pool;

public interface ThreadPool {

    /**
     * 执行一个任务 task
     */
    void execute(Runnable task);

    /**
     * 得到正在等待执行的任务数量
     */
    int getWaitingTaskCount();

    /**
     * 关闭线程池
     */
    void shutdown();
}
