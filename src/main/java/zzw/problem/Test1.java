package zzw.problem;

/**
 * 两个线程交替输出 1 ~ 100
 */
public class Test1
{

    /**
     * 线程互斥的关键
     */
    private static volatile boolean odd    = true;
    private static          int     i      = 1;
    private static          boolean finish = false;

    /**
     * 当 odd == flag 时打印
     */
    private static void print(boolean flag)
    {
        while (!finish)
        {
            if (odd == flag)
            {
                int num = i++;
                if (num > 100)
                {
                    finish = true;
                    break;
                }

                System.out.println(Thread.currentThread().getName() + num);
                odd = !odd;
            }
        }
    }

    public static void main(String[] args)
    {
        // 奇数线程
        Thread t1 = new Thread(() -> print(true));
        t1.setName("线程 A ");

        // 偶数线程
        Thread t2 = new Thread(() -> print(false));
        t2.setName("线程 B ");

        t1.start();
        t2.start();
    }
}
