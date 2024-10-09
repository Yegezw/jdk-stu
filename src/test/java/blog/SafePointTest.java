package blog;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 该示例对 Java 8 有效
 */
public class SafePointTest
{

    private static final AtomicInteger num = new AtomicInteger();

    private static void start(Runnable r) throws InterruptedException
    {
        Thread thread1 = new Thread(r);
        Thread thread2 = new Thread(r);
        thread1.setDaemon(true);
        thread2.setDaemon(true);

        thread1.start();
        thread2.start();

        Thread.sleep(1000);
        System.out.println("num = " + num);
    }

    // run() 的 for 循环为 "int 可数循环", for 循环的过程中不会有 SafePoint
    // 主线程在 1000 ms 之后, JVM 尝试在 SafePoint 停止, 以便 Java 线程进行定期清理, 但是直到可数循环完成后才能执行此操作
    private static void test1() throws InterruptedException
    {
        Runnable r = () ->
        {
            for (int i = 0; i < 1_000_000_000; i++)
            {
                num.addAndGet(1);
            }
        };
        start(r);
    }

    // 把 int 修改为 long 后, for 为 "不可数循环", for 循环的过程中会有 SafePoint
    private static void test2() throws InterruptedException
    {
        Runnable r = () ->
        {
            for (long i = 0; i < 1_000_000_000; i++)
            {
                num.addAndGet(1);
            }
        };
        start(r);
    }

    // for 循环的过程中不会有 SafePoint, 但可以通过 native Thread.sleep(0) 手动添加 SafePoint
    // 当某个线程在执行 native 函数的时候, 此时该线程在执行 JVM 管理之外的代码, 不能对 JVM 的执行状态做任何修改
    // 因此 JVM 要进入 SafePoint 不需要关心它
    // 也可以把正在执行 native 函数的线程看作 "已经进入了 SafePoint", 或者把这种情况叫做 "在 SafeRegion 里"
    private static void test3() throws InterruptedException
    {
        Runnable r = () ->
        {
            for (int i = 0; i < 1_000_000_000; i++)
            {
                num.addAndGet(1);
                // prevent gc
                if (i % 1000 == 0)
                {
                    try
                    {
                        Thread.sleep(0);
                    }
                    catch (InterruptedException e)
                    {
                        throw new RuntimeException(e);
                    }
                }
            }
        };
        start(r);
    }

    /**
     * 三个 test() 的 for 循环都远远超过了 Client 1_500、Server 10_000<br>
     * 即使回边计数器是分层编译的动态阈值, 也一定会超过这个阈值<br>
     * 导致被判定为热点代码, 触发 JIT 编译整个 test() 方法, 然后选取某些指令打 SafePoint 标记
     */
    public static void main(String[] args) throws InterruptedException
    {
        test1(); // == 2_000_000_000
        test2(); // <  2_000_000_000
        test3(); // <  2_000_000_000
    }
}
