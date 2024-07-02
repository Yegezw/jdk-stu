package zzw.problem;

/**
 * 三个线程分别打印 1 2 3, 顺序执行 10 次
 */
public class Test3
{

    /**
     * 线程互斥的关键
     */
    private static volatile int     t      = 0;
    private static          int     count  = 1;
    private static          boolean finish = false;

    /**
     * 当 t == flag 时打印 i
     */
    private static void print(final int flag, final int i)
    {
        while (!finish)
        {
            if (t == flag)       // ------ 加锁 ------
            {
                if (count > 10)
                {
                    finish = true;
                    break;
                }

                System.out.println(Thread.currentThread().getName() + i);
                if (i == 3)
                {
                    count++;
                }
                t = (t + 1) % 3; // ------ 解锁 ------
            }
        }
    }

    public static void main(String[] args)
    {
        Thread t1 = new Thread(() -> print(0, 1));
        t1.setName("线程 A ");

        Thread t2 = new Thread(() -> print(1, 2));
        t2.setName("线程 B ");

        Thread t3 = new Thread(() -> print(2, 3));
        t3.setName("线程 C ");

        t1.start();
        t2.start();
        t3.start();
    }
}
