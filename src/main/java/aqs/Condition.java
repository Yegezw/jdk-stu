package aqs;

import java.util.Date;
import java.util.concurrent.TimeUnit;

public interface Condition {

    void await() throws InterruptedException;

    void awaitUninterruptibly();

    long awaitNanos(long nanosTimeout) throws InterruptedException;

    boolean await(long time, TimeUnit unit) throws InterruptedException;

    boolean awaitUntil(Date deadline) throws InterruptedException;

    void signal();

    void signalAll();

    /**
     * 用于调试打印, 不保证线程安全
     */
    void printInfo();
}
