package aqs.queue;

import aqs.Condition;
import aqs.lock.ReentrantLock;

import java.util.PriorityQueue;
import java.util.concurrent.Delayed;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * 延迟队列
 */
public class DelayQueue<E extends Delayed>
{

    private final ReentrantLock lock      = new ReentrantLock();
    private final Condition     available = lock.newCondition();

    private       Thread           leader;
    private final PriorityQueue<E> q = new PriorityQueue<>();

    public DelayQueue()
    {
    }

    public void put(E e)
    {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try
        {
            q.offer(e);
            if (q.peek() == e)
            {
                leader = null;
                available.signal(); // 唤醒
            }
        }
        finally
        {
            lock.unlock();
        }
    }

    public E take() throws InterruptedException
    {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try
        {
            // 自旋避免假唤醒
            for (; ; )
            {
                E first = q.peek();

                // q 为空
                if (first == null)
                {
                    available.await(); // 释放锁并等待
                }
                // q 不为空
                else
                {
                    long delay = first.getDelay(NANOSECONDS);
                    if (delay <= 0L)
                    {
                        return q.poll();                 // 4、元素到期被读取(非 leader 可能会插队)
                    }

                    first = null; // don't retain ref while waiting

                    // 非 leader 线程
                    if (leader != null)
                    {
                        available.await(); // 释放锁并等待
                    }
                    // leader 线程
                    else
                    {
                        Thread thisThread = Thread.currentThread();
                        leader = thisThread;
                        try
                        {
                            available.awaitNanos(delay); // 1、释放锁并等待
                        }
                        finally
                        {
                            if (leader == thisThread)
                            {
                                leader = null;           // 2、leader 置空
                            }
                        }
                    }
                }
            }
        }
        finally
        {
            if (leader == null && q.peek() != null)
            {
                available.signal();                      // 3、唤醒
            }
            lock.unlock();
        }
    }
}
