package aqs;

import aqs.synchronization_tool.CountDownLatch;

/**
 * 使用 CountDownLatch 完成一个线程等待多个线程事件的发生
 */
public class Test7 {

    private static final CountDownLatch latch = new CountDownLatch(2);

    public static class MyThread extends Thread {
        @Override
        public void run() {
            // ... do something ...
            latch.countDown();
            // ... do other thing ...
        }
    }

    public static void main(String[] args) throws InterruptedException {
        MyThread t1 = new MyThread();
        MyThread t2 = new MyThread();
        t1.start();
        t2.start();

        latch.await(); // 等待 something 执行完成而非等待线程结束, 并且不需要知道在等谁
        // ... 执行后续逻辑 ...
    }
}
