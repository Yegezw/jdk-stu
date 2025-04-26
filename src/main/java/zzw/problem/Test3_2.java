package zzw.problem;

import java.util.concurrent.locks.LockSupport;

/**
 * 三个线程分别打印 1 2 3, 顺序执行 10 次
 */
@SuppressWarnings("all")
public class Test3_2
{

    /**
     * 线程互斥的关键
     */
    private static int     point;
    /**
     * 已打印的轮数
     */
    private static int     count  = 0;
    /**
     * 任务是否已完成
     */
    private static boolean finish = false;

    private static Thread[] threads;

    private static class OrderedThread extends Thread
    {
        private final int id;
        private final int num;

        public OrderedThread(int id, int num)
        {
            super("线程 " + id + " ");
            this.id  = id;
            this.num = num;
        }

        @Override
        public void run()
        {
            while (!finish)
            {
                if (point == id)       // ------ 加锁 ------
                {
                    if (count >= 10)
                    {
                        finish = true;
                        for (Thread t : threads) if (t != null) LockSupport.unpark(t);
                        return;
                    }

                    System.out.println(super.getName() + num);
                    if (num == 3) count++;

                    if (point == 3) point = 1;
                    else point++;      // ------ 解锁 ------
                    if (threads[point] != null) LockSupport.unpark(threads[point]);
                }
                else
                {
                    threads[id] = Thread.currentThread();
                    LockSupport.park(this);
                    threads[id] = null;
                }
            }
        }
    }

    public static void main(String[] args)
    {
        threads = new Thread[4];

        point = 1;
        OrderedThread thread1 = new OrderedThread(1, 1);
        OrderedThread thread2 = new OrderedThread(2, 2);
        OrderedThread thread3 = new OrderedThread(3, 3);
        thread1.start();
        thread2.start();
        thread3.start();
    }
}
