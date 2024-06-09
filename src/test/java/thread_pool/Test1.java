package thread_pool;

import aqs.thread_pool.ThreadPoolExecutor;
import zzw.Util;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Test1 {

    private static final ThreadPoolExecutor pool;

    static {
        // 创建与配置
        // 核心线程数、线程总数、非核心线程存活时间、非核心线程存活时间单位、工作队列、线程工厂、拒绝策略

        // 拒绝策略
        // 1、DiscardPolicy          放弃执行任务
        // 2、AbortPolicy            放弃执行任务, 并抛出 RejectedExecutionException 异常(默认)
        // 3、CallerRunsPolicy       由任务递交者代替线程池来执行这个任务
        // 4、DiscardOldestPolicy    删掉 workQueue 中的一个任务, 再次调用 execute() 执行当前任务
        pool = new ThreadPoolExecutor(
                5, 10, 1000, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(15),
                new ThreadFactory() {
                    private final AtomicInteger idx = new AtomicInteger(1);

                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "pool-" + idx.getAndIncrement());
                    }
                }, new ThreadPoolExecutor.DiscardPolicy()
        );

        // 预先启动 1 个核心线程
        pool.prestartCoreThread();
        // 预先启动所有的核心线程
        pool.prestartAllCoreThreads();

        // 动态调整线程池参数
        pool.setCorePoolSize(10);
        pool.setMaximumPoolSize(20);

        // 允许核心线程超时, 即核心线程也会被回收
        pool.allowCoreThreadTimeOut(true);
    }

    /**
     * 执行
     */
    private static void start() {
        // 核心线程被创建之后
        // 会调用 workQueue.take(), 不停的从 workQueue 中取任务处理
        // take() 是阻塞函数, 当 workQueue 中没有待执行的任务时, take() 会一直阻塞等待

        // 非核心线程被创建之后
        // 会调用 workQueue. poll(), 不停的从 workQueue 中取任务处理
        // poll() 是阻塞函数, 跟 take() 的不同之处在于, poll() 函数可以设置阻塞的超时时间
        // 如果 poll() 的阻塞时间超过 keepAliveTime(在创建线程池时设置的非核心线程空闲销毁时间)
        // 那么 poll() 会从阻塞中返回 null, 因为非核心线程在 keepAliveTime 内, 没有执行任务, 所以会执行线程销毁逻辑

        pool.execute(new Runnable() {
            @Override
            public void run() {
                while (!Thread.interrupted()) {
                    System.out.println("hello world!");
                }
            }
        });
    }

    /**
     * 关闭
     */
    private static void shutdown() throws InterruptedException {
        // 当执行 shutdown() 时, 线程池会拒绝接收新的任务
        // 但是会将正在执行的任务以及等待队列中的任务全部执行完成, 这是一种比较优雅的关闭线程池的方式
        pool.shutdown();

        // 当执行 shutdownNow() 时, 线程池会同样拒绝接收新的任务
        // 但是会清空等待队列, 返回值为等待队列中未被执行的任务, 并向所有的线程发送中断请求
        // 如果这个线程: 在调用 take() 或 poll() 阻塞等待获取任务, 那么这个线程会被中断, 然后结束
        // 如果这个线程: 正在执行任务, 收到中断请求之后, 可以响应中断终止执行、也可以选择不理会(继续执行直到任务执行完成)
        List<Runnable> notRunTaskList = pool.shutdownNow(); // 未执行任务列表
        System.out.println(notRunTaskList);

        // 如果要确保所有的线程都已经结束
        // 需要调用 awaitTermination() 函数阻塞等待
        // 返回值为 false 表示超时, 返回值为 true 表示线程池真正关闭
        boolean terminated = false;
        while (!terminated) {
            terminated = pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        start();

        Util.sleep(3000);

        shutdown();
        System.err.println("pool is shutdown.");
    }
}
