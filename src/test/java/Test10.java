/**
 * 学习 Thread 中的 join() 方法
 */
public class Test10 {

    private static class MyThread extends Thread {
        @Override
        public void run() {
            for (int i = 0; i < 10000; i++) {
                System.out.println(Thread.currentThread().getName());
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Thread thread = new MyThread();
        thread.start();

        // 主线程
        thread.join();

        // Thread 中的 join() 方法采用 Object 上的 wait() 函数实现
        // 1、main 线程调用 thread.join() -> synchronized join(0)
        // 2、main 线程获得 thread 对象的锁
        // 3、main 线程执行 thread.wait(0) -> main 线程加入 thread 对象的等待队列并释放锁
        // 4、thread 执行完成后, 由 JVM 负责调用 thread.notifyAll(), 进而唤醒 main 线程
    }
}
