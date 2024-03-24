package zzw;

import aqs.Condition;
import aqs.lock.Lock;
import aqs.lock.ReentrantLock;

import java.util.LinkedList;

/**
 * <p>支持阻塞读和阻塞写的有限队列
 * <p>入队: 队列已满时, 写入操作会被阻塞, 直到队列有空位为止
 * <p>出队: 队列为空时, 读取操作会被阻塞, 直到队列有数据为止
 * <p>基于条件变量实现, await() 和 signal() 之前必须先加锁, while(...) await() 避免假唤醒
 */
@SuppressWarnings("all")
public class BlockingQueueCond<E> {

    private final LinkedList<E> list;
    private final int           capacity;

    private final Lock      lock     = new ReentrantLock();
    private final Condition notFull  = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    public BlockingQueueCond(int capacity) {
        this.list = new LinkedList<>();
        this.capacity = capacity;
    }

    /**
     * 入队: 队列已满时, 写入操作会被阻塞, 直到队列有空位为止
     */
    public void enqueue(E e) {
        lock.lock();
        try {
            while (list.size() == capacity) notFull.awaitUninterruptibly();

            list.addLast(e);
            System.err.println("写入 " + e);
            Util.sleep(100); // 用于等待 IO 完成

            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 出队: 队列为空时, 读取操作会被阻塞, 直到队列有数据为止
     */
    public E dequeue() {
        lock.lock();
        try {
            while (list.isEmpty()) notEmpty.awaitUninterruptibly();

            E e = list.removeFirst();
            System.out.println("读取 " + e);
            Util.sleep(100); // 用于等待 IO 完成

            notFull.signal();
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
