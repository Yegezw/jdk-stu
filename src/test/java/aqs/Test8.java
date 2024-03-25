package aqs;

import zzw.Util;

/**
 * <p>
 * test1() 子线程抛出异常并不影响主线程继续运行
 * </p>
 * <p>
 * test2() 测试异常兜底<br>
 * 异常兜底: 捕获 try {} 和 catch {} 中未被捕获的异常, 在执行完 finally {} 之后, 原封不动的将未被捕获的异常抛出
 * </p>
 */
public class Test8 {

    private static class MyThread extends Thread {
        @Override
        public void run() {
            System.out.println(Thread.currentThread().getName());
            throw new RuntimeException();
        }
    }

    /**
     * 子线程抛出异常并不影响主线程继续运行
     */
    private static void test1() {
        new MyThread().start(); // 抛出异常

        Util.sleep(3000);

        new MyThread().start(); // 抛出异常
    }

    // ===========================================================

    private static void help() {
        throw new RuntimeException();
    }

    /**
     * <p>
     * 测试异常兜底
     * </p>
     * <p>
     * 异常兜底: 捕获 try {} 和 catch {} 中未被捕获的异常<br>
     * 在执行完 finally {} 之后, 原封不动的将未被捕获的异常抛出
     * </p>
     */
    private static int test2() {
        try {
            System.out.println("begin");
            help();
            System.out.println("end");
            return 10;
        } finally {
            System.out.println("finally");
        }
    }

    public static void main(String[] args) {
        // test1();

        int res = test2();
        System.out.println(res);
    }
}
