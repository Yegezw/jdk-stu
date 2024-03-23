package zzw;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.function.LongBinaryOperator;

/**
 * <p>数据分片、哈希优化、去伪共享、非准确求和
 * <p>LongAdder、LongAccumulator
 * <p>DoubleAdder、DoubleAccumulator
 */
public class Max {

    /**
     * 收集器, 用于得到最大值
     */
    private final LongAccumulator accumulator = new LongAccumulator(
            new LongBinaryOperator() {
                @Override
                public long applyAsLong(long left, long right) {
                    return Math.max(left, right);
                }
            },
            Long.MIN_VALUE
    );

    public void max(long value) {
        accumulator.accumulate(value);
    }

    public long get() {
        return accumulator.get();
    }
}
