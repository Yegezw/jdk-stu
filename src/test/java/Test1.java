import aqs.lock.Lock;
import aqs.lock.ReentrantLock;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.concurrent.locks.LockSupport;

public class Test1 {

    // 测试 Out.onws(In in) 函数
    private static void test1() {
        Out    out1 = new Out();
        Out.In in1  = out1.getIn();
        System.out.println(out1.onws(in1)); // true

        Out    out2 = new Out();
        Out.In in2  = out2.getIn();
        System.out.println(out2.onws(in2)); // true

        System.out.println(out1.onws(in2)); // false
        System.out.println(out2.onws(in1)); // false
    }

    // ===========================================================

    private static int num = 0;

    // 测试 ReentrantLock
    private static void test2() throws InterruptedException {
        Lock lock = new ReentrantLock();

        Runnable r = () -> {
            for (int i = 0; i < 1000000; i++) {
                lock.lock();
                num++;
                lock.unlock();
            }
        };
        Thread t1 = new Thread(r);
        Thread t2 = new Thread(r);
        t1.start();
        t2.start();

        // 主线程
        t1.join();
        t2.join();
        System.out.println(num); // 2000000
    }

    // ===========================================================

    private static final Unsafe unsafe;

    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = (Unsafe) field.get(null);
        } catch (Exception e) {
            throw new RuntimeException();
        }
    }

    /**
     * <p>
     * 步骤一: thread 正在执行<br>
     * 步骤二: main 调用 LockSupport.unpark(thread);<br>
     * 步骤三: thread 调用LockSupport.park();
     * </p>
     * <p>
     * LockSupport.park() 会立即返回, 因为 unpark() 操作已经提前为 thread 提供了一个许可<br>
     * 所以即便 thread 调用了 park() 也不会挂起<br>
     * 在 LockSupport 中, 许可并非累计的, 也就是说一个线程无论被 unpark 多少次, 最多只有一个许可<br>
     * 这种预先 unpark(有时称为 "信号量前置")的行为允许线程安全地管理它们的唤醒状态, 而不会丢失任何信号
     * </p>
     */
    private static void test3() {
        Runnable r = () -> {
            for (int i = 0; i < 100000; i++) {
                System.out.println(1);
            }
            System.out.println("thread 打印 1 完成, 准备 park");
            LockSupport.park();
            System.out.println("thread park 完成");
        };
        Thread thread = new Thread(r);
        thread.start();

        // 主线程 main
        LockSupport.unpark(thread); // 建议阅读 LockSupport.unpark(Thread thread) 注释
        System.out.println("主线程 main 调用 unpark 完成");
    }

    public static void main(String[] args) throws InterruptedException {
        // test1();
        // test2();
        test3();
    }
}
