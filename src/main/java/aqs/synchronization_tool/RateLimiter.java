package aqs.synchronization_tool;

import com.google.common.base.Stopwatch;

import java.time.LocalTime;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

public class RateLimiter {

    private final Stopwatch stopwatch = Stopwatch.createStarted();

    private long readMicros() {
        return stopwatch.elapsed(MICROSECONDS);
    }

    // ------------------------------------------------

    /**
     * 存储许可的最大数量
     */
    private final double maxPermits;

    /**
     * 许可产生间隔
     */
    private final double stableIntervalMicros;

    // ------------------------------------------------

    /**
     * 当前存储的许可
     */
    private double storedPermits;

    /**
     * 下一次可以获取许可的时间, 相对时间(构造)
     */
    private long nextFreeTicketMicros;

    public RateLimiter(long maxPermits) {
        this.maxPermits = maxPermits;
        this.stableIntervalMicros = (double) TimeUnit.SECONDS.toMicros(1) / maxPermits;
        this.storedPermits = 0;
        this.nextFreeTicketMicros = readMicros();
    }

    // ------------------------------------------------

    public long acquire(int permits) {
        long ret;
        synchronized (this) {
            long now = readMicros();
            if (now > nextFreeTicketMicros) {
                storedPermits = Math.min(maxPermits, storedPermits + (now - nextFreeTicketMicros) / stableIntervalMicros);
                nextFreeTicketMicros = now;
            }

            if (storedPermits >= permits) {
                storedPermits -= permits;
                return 0;
            }

            double need = permits - storedPermits;
            long needWait = (long) (need * stableIntervalMicros);
            storedPermits = 0;

            long old = nextFreeTicketMicros;
            nextFreeTicketMicros += needWait;
            ret = old - now;
        }

        sleep(ret);
        return ret;
    }

    private void sleep(long micros) {
        try {
            MICROSECONDS.sleep(micros);
        } catch (InterruptedException ignore) {
        }
    }

    // ------------------------------------------------

    private static void test1() {
        RateLimiter limiter = new RateLimiter(5);
        System.out.println(LocalTime.now());
        Runnable runnable = () -> {
            while (true) {
                limiter.acquire(5);
                System.out.println(LocalTime.now());
            }
        };
        new Thread(runnable).start();
        while (true) {
            limiter.acquire(5);
            System.err.println(LocalTime.now());
        }
    }

    private static void test2() {
        com.google.common.util.concurrent.RateLimiter limiter = com.google.common.util.concurrent.RateLimiter.create(5);
        System.out.println(LocalTime.now());
        Runnable runnable = () -> {
            while (true) {
                limiter.acquire(5);
                System.out.println(LocalTime.now());
            }
        };
        new Thread(runnable).start();
        while (true) {
            limiter.acquire(5);
            System.err.println(LocalTime.now());
        }
    }

    public static void main(String[] args) {
        test1();
        test2();
    }
}
