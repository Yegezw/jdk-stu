package thread_pool;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Test2 {

    private static class TestRunnable implements Runnable {

        @Override
        public void run() {
            System.out.println("hello world!");
        }
    }

    public static void main(String[] args) {
        // 固定核心线程 + 无界任务队列 LinkedBlockingQueue
        ExecutorService pool1 = Executors.newFixedThreadPool(3);

        // 单个核心线程 + 无界任务队列 LinkedBlockingQueue
        ExecutorService pool2 = Executors.newSingleThreadExecutor();

        // 非核心线程 Integer.MAX_VALUE + 存活时间 60 秒 + 零任务队列 SynchronousQueue
        ExecutorService pool3 = Executors.newCachedThreadPool();

        // ------------------------------------------------------

        // 周期线程: 核心线程 + 无界任务队列 DelayedWorkQueue
        ScheduledExecutorService pool4 = Executors.newScheduledThreadPool(1);

        // 只执行一次
        pool4.schedule(new TestRunnable(), 1, TimeUnit.SECONDS);

        // 固定频率
        pool4.scheduleAtFixedRate(new TestRunnable(), 1, 1, TimeUnit.SECONDS);

        // 固定间隔
        pool4.scheduleWithFixedDelay(new TestRunnable(), 1, 1, TimeUnit.SECONDS);
    }
}
