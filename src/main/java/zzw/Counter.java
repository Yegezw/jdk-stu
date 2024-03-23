package zzw;

import java.util.concurrent.atomic.LongAdder;

/**
 * <p>累加器 LongAdder
 * <p>数据分片、哈希优化、去伪共享、非准确求和
 */
public class Counter {

    private final LongAdder count = new LongAdder();

    public void add(long value) {
        count.add(value);
    }

    public long get() {
        return count.sum();
    }
}