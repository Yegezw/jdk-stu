package aqs;

import aqs.synchronization_tool.Semaphore;
import zzw.Util;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

/**
 * 使用 Semaphore 实现接口并发限制功能
 */
@SuppressWarnings("all")
public class Test5 {

    private final Semaphore semaphore = new Semaphore(10);

    public void apiX() {
        semaphore.acquireUninterruptibly();
        try {
            // 执行业务逻辑
        } finally {
            semaphore.release();
        }
    }

    public static void main(String[] args) throws Exception {
        // AQS 共享模式下, 释放一个许可时不仅会唤醒第一个等待节点
        // 还可能因为共享传播机制额外唤醒后继节点, 后继节点醒来后如果抢不到许可, 会再次阻塞
        Semaphore semaphore = new Semaphore(0);
        Method method = Semaphore.class.getDeclaredMethod("getQueuedThreads");
        method.setAccessible(true);

        Runnable r = () -> {
            semaphore.acquireUninterruptibly();
            System.out.println(Thread.currentThread().getName());
        };

        new Thread(r, "t1").start();
        Util.sleep(100L);
        new Thread(r, "t2").start();
        Util.sleep(100L);
        new Thread(r, "t3").start();
        Util.sleep(100L);

        ArrayList<Thread> queuedThreads = new ArrayList<>((Collection) method.invoke(semaphore));
        Collections.reverse(queuedThreads);
        System.out.println(queuedThreads.stream().map(Thread::getName).collect(Collectors.joining(" -> ")));

        Util.sleep(1000L);
        semaphore.release(1); // t2 也会被唤醒, 但是 t2 拿不到信号量继续被阻塞
    }
}
