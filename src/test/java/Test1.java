import aqs.lock.Lock;
import aqs.lock.ReentrantLock;

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

    public static void main(String[] args) throws InterruptedException {
        test1();
        test2();
    }
}
