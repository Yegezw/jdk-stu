import aqs.lock.ReentrantReadWriteLock;

/**
 * 观察 ReentrantReadWriteLock 锁降级过程
 */
@SuppressWarnings("all")
public class Test2 {

    private static int                              num       = 0;
    private static ReentrantReadWriteLock           lock      = new ReentrantReadWriteLock();
    private static ReentrantReadWriteLock.ReadLock  readLock  = lock.readLock();
    private static ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();

    private static class WriteThread extends Thread {
        @Override
        public void run() {
            writeLock.lock();   // 获取写锁
            num++;
            writeLock.unlock(); // 释放写锁
        }
    }

    private static void lockDegradation() throws InterruptedException {
        writeLock.lock();   // 获取写锁

        WriteThread t1 = new WriteThread();
        WriteThread t2 = new WriteThread();
        t1.setName("Thread-1");
        t2.setName("Thread-2");
        t1.start();
        t2.start();
        Thread.sleep(1000);
        lock.printInfo();   // 打印 AQS 信息

        num++;
        readLock.lock();    // 断点打在这里 To 观察锁降级的过程
        System.out.println(num);

        writeLock.unlock(); // 释放写锁
        readLock.unlock();
    }

    public static void main(String[] args) throws Exception {
        Thread.currentThread().setName("Thread-Main");
        lockDegradation();

        Thread.sleep(1000);
        System.out.println(num); // 3
    }
}
