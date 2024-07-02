package zzw.problem;

/**
 * 三个线程交替输出 1 ~ 100
 */
public class Test2
{

    /**
     * 线程互斥的关键
     */
    private static volatile int     t      = 0;
    private static          int     i      = 1;
    private static          boolean finish = false;

    /**
     * 当 t == flag 时打印
     */
    private static void print(final int flag)
    {
        while (!finish)
        {
            if (t == flag)       // ------ 加锁 ------
            {
                int num = i++;
                if (num > 100)
                {
                    finish = true;
                    break;
                }

                System.out.println(Thread.currentThread().getName() + num);
                t = (t + 1) % 3; // ------ 解锁 ------
            }
        }
    }

    public static void main(String[] args)
    {
        Thread t1 = new Thread(() -> print(0));
        t1.setName("线程 A ");

        Thread t2 = new Thread(() -> print(1));
        t2.setName("线程 B ");

        Thread t3 = new Thread(() -> print(2));
        t3.setName("线程 C ");

        t1.start();
        t2.start();
        t3.start();
    }
}
