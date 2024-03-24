import aqs.synchronization_tool.CyclicBarrier;

import java.util.concurrent.BrokenBarrierException;

/**
 * 使用 CyclicBarrier<br>
 * 让一组线程到达一个屏障(也可以叫同步点)时被阻塞<br>
 * 直到最后一个线程到达屏障时, 屏障才会开门, 所有被屏障拦截的线程才会继续运行
 */
public class Test9 {

    private static final CyclicBarrier barrier = new CyclicBarrier(10);

    private static class MyThread extends Thread {
        @Override
        @SuppressWarnings("all")
        public void run() {
            try {
                barrier.await();
            } catch (InterruptedException e) {
                e.printStackTrace(); // 当前线程被中断
            } catch (BrokenBarrierException e) {
                e.printStackTrace(); // 其它线程调用 await() 期间被中断
            }
            // 执行业务逻辑
            System.out.println(Thread.currentThread().getName());
        }
    }

    public static void main(String[] args) {
        for (int i = 0; i < 10; i++) {
            MyThread thread = new MyThread();
            thread.setName("Thread-" + i);
            thread.start();
        }
    }
}
