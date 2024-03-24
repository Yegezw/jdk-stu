package zzw;

import aqs.lock.Lock;
import aqs.lock.ReentrantLock;
import aqs.synchronization_tool.Semaphore;

import java.util.LinkedList;

/**
 * <p>支持阻塞读和阻塞写的有限队列
 * <p>入队: 队列已满时, 写入操作会被阻塞, 直到队列有空位为止
 * <p>出队: 队列为空时, 读取操作会被阻塞, 直到队列有数据为止
 * <p>基于信号量实现, 信号量的获取必须位于加锁之前, 信号量的释放必须位于解锁之前
 */
@SuppressWarnings("all")
public class BlockingQueueSem<E> {

    private final LinkedList<E> list;
    private final int           capacity;

    private final Lock      lock = new ReentrantLock();
    private final Semaphore emptySemaphore;   // 空位信号量
    private final Semaphore elementSemaphore; // 元素信号量

    public BlockingQueueSem(int capacity) {
        this.list = new LinkedList<>();
        this.capacity = capacity;
        this.emptySemaphore = new Semaphore(capacity);
        this.elementSemaphore = new Semaphore(0);
    }

    /**
     * 入队: 队列已满时, 写入操作会被阻塞, 直到队列有空位为止
     */
    public void enqueue(E e) {
        // 信号量的获取必须在加锁之前
        // 如果在加锁之后获取信号量, 当没有信号量时将会被阻塞, 导致死锁
        // 即 lock() -> 获取信号量失败 -> 阻塞 -> 别的线程因为无法获取锁而不能释放信号量 -> 无法 unlock() -> 死锁
        emptySemaphore.acquireUninterruptibly();

        lock.lock();
        try {
            list.addLast(e);
            System.err.println("写入 " + e);
            Util.sleep(100); // 用于等待 IO 完成

            // 信号量的释放也必须在解锁之前
            // 如果在解锁之后释放信号量, 当解锁之后, 还没来得及释放信号量时
            // 别的线程获取信号量时将会被阻塞, 这将会导致消费信号量的线程不能及时消费
            elementSemaphore.release();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 出队: 队列为空时, 读取操作会被阻塞, 直到队列有数据为止
     */
    public E dequeue() {
        // 信号量的获取必须在加锁之前
        // 如果在加锁之后获取信号量, 当没有信号量时将会被阻塞, 导致死锁
        // 即 lock() -> 获取信号量失败 -> 阻塞 -> 别的线程因为无法获取锁而不能释放信号量 -> 无法 unlock() -> 死锁
        elementSemaphore.acquireUninterruptibly();

        lock.lock();
        try {
            E e = list.removeFirst();
            System.out.println("读取 " + e);
            Util.sleep(100); // 用于等待 IO 完成

            // 信号量的释放也必须在解锁之前
            // 如果在解锁之后释放信号量, 当解锁之后, 还没来得及释放信号量时
            // 别的线程获取信号量时将会被阻塞, 这将会导致消费信号量的线程不能及时消费
            emptySemaphore.release();
            return e;
        } finally {
            lock.unlock();
        }
    }

    public int getSize() {
        lock.lock();
        try {
            int size = list.size();
            return size;
        } finally {
            lock.unlock();
        }
    }

    public int getCapacity() {
        return capacity;
    }
}
