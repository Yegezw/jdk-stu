package concurrent_collections;

import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/**
 * 延迟任务
 */
public class Job implements Delayed, Runnable {

    private final String name;
    private final long   endTime; // 终止时间 millisecond

    public Job(String name, long delay) {
        // 延迟时间 delay
        // 终止时间 System.currentTimeMillis() + delay
        this.name = name;
        this.endTime = System.currentTimeMillis() + delay;
    }

    /**
     * 任务
     */
    @Override
    public void run() {
        System.err.println("I am " + name);
    }

    /**
     * 现在距离终止时间还差多少
     */
    @Override
    public long getDelay(TimeUnit unit) {
        long diff = endTime - System.currentTimeMillis();
        return unit.convert(diff, TimeUnit.MILLISECONDS); // 返回时间毫秒值
    }

    /**
     * 最小堆
     */
    @Override
    public int compareTo(Delayed o) {
        return (int) (
            this.getDelay(TimeUnit.NANOSECONDS) - o.getDelay(TimeUnit.NANOSECONDS)
        );
    }
}
