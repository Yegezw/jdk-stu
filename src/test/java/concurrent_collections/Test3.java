package concurrent_collections;

import java.util.concurrent.SynchronousQueue;

/**
 * 学习 SynchronousQueue
 */
public class Test3 {

    // LinkedTransferQueue = LinkedBlockingQueue + SynchronousQueue

    // SynchronousQueue 是一个 "特殊的阻塞并发队列", 用于两个线程之间传递数据
    // A 线程执行 put() 操作必须阻塞等待 B 线程执行 take() 操作, 也就是说: SynchronousQueue 队列中不存储任何元素
    private static final SynchronousQueue<String> sq = new SynchronousQueue<>();

    private static class WriteThread extends Thread {
        @Override
        public void run() {
            try {
                sq.put("a"); // 阻塞
                System.out.println("put done!");
            } catch (InterruptedException ignore) {
            }
        }
    }

    private static class ReadThread extends Thread {
        @Override
        public void run() {
            try {
                sq.take();
                System.out.println("take done!");
            } catch (InterruptedException ignore) {
            }
        }
    }

    // 先后输出: sleep done! take done! put done!
    public static void main(String[] args) throws InterruptedException {
        // 写线程必须等待读线程
        Thread wThread = new WriteThread(); // 写线程 put
        Thread rThread = new ReadThread();  // 读线程 take

        wThread.start();

        Thread.sleep(2000);
        System.out.println("sleep done!");

        rThread.start();
    }
}
