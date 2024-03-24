package zzw;

import aqs.synchronization_tool.CountDownLatch;
import aqs.synchronization_tool.CyclicBarrier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;

/**
 * 利用 CountDownLatch 和 CyclicBarrier 进行接口并发性能测试
 */
@SuppressWarnings("all")
public class ApiBenchmark {

    private static int numThread       = 20;   // 并发度为 20
    private static int numReqPerThread = 1000; // 每个线程请求 1000 次接口

    private static CountDownLatch latch   = new CountDownLatch(numThread); // 唤醒主线程
    private static CyclicBarrier  barrier = new CyclicBarrier(numThread);  // 各线程同时开始执行

    /**
     * 测试线程
     */
    public static class TestRunnable implements Runnable {
        public List<Long> respTimes = new ArrayList<>();

        @Override
        public void run() {
            try {
                barrier.await(); // 同时开始执行
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (BrokenBarrierException e) {
                e.printStackTrace();
            }

            for (int i = 0; i < numReqPerThread; i++) {
                long reqStartTime = System.nanoTime();
                // 调用接口 ...
                long reqEndTime = System.nanoTime();
                respTimes.add(reqEndTime - reqStartTime);
            }

            latch.countDown();   // 唤醒主线程
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // 创建线程
        Thread[]       threads   = new Thread[numThread];
        TestRunnable[] runnables = new TestRunnable[numThread];
        for (int i = 0; i < numThread; i++) {
            runnables[i] = new TestRunnable();
            threads[i] = new Thread(runnables[i]);
        }

        // 启动线程
        long startTime = System.nanoTime();
        for (int i = 0; i < numThread; i++) {
            threads[i].start();
        }

        // 等待测试线程结束
        latch.await();
        long endTime = System.nanoTime();

        // 统计接口性能 qps
        long reqCount = numThread * numReqPerThread;        // 请求接口的总次数
        long runTime  = (endTime - startTime) / 1000000000; // 总耗时(s)
        long qps      = reqCount / runTime;                 // qps

        // 统计接口性能 avgRespTime
        float avgRespTime = 0.0f;
        for (int i = 0; i < numThread; i++) {
            for (Long respTime : runnables[i].respTimes) {
                avgRespTime += respTime;
            }
        }
        avgRespTime /= reqCount;
    }
}