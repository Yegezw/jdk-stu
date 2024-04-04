package aqs.synchronization_tool;

import aqs.Condition;
import aqs.lock.ReentrantLock;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class CyclicBarrier {

    /**
     * 用于标记本轮屏障是否损坏
     */
    private static class Generation {

        /**
         * 损坏
         */
        boolean broken = false;
    }

    // =================================================================================================================

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition     trip = lock.newCondition();

    /**
     * 需要阻塞的线程数
     */
    private final int parties;

    /**
     * 剩余需要阻塞的线程数
     */
    private int count;

    /**
     * 最后一个到达屏障点的线程<br>
     * 先调用 barrierCommand.run(), 后调用 nextGeneration() 唤醒所有线程<br>
     * 如果 barrierCommand.run() 抛出异常, 则调用 breakBarrier() 以标记本轮屏障已损坏并唤醒所有线程
     */
    private final Runnable barrierCommand;

    /**
     * 用于标记本轮屏障是否损坏
     */
    private Generation generation = new Generation();

    public CyclicBarrier(int parties, Runnable barrierAction) {
        if (parties <= 0) throw new IllegalArgumentException();
        this.parties = parties;
        this.count = parties;
        this.barrierCommand = barrierAction;
    }

    public CyclicBarrier(int parties) {
        this(parties, null);
    }

    // =================================================================================================================

    /**
     * 打破屏障, 唤醒所有线程
     */
    private void breakBarrier() {
        generation.broken = true;
        count = parties;
        trip.signalAll();
    }

    /**
     * 唤醒所有线程, 进入下一轮屏障
     */
    private void nextGeneration() {
        // signal completion of last generation
        trip.signalAll();
        // set up next generation
        count = parties;
        generation = new Generation();
    }

    /**
     * <p>InterruptedException 当前线程被中断
     * <p>BrokenBarrierException 其它线程调用 await() 期间被中断
     * <p>返回值: 当前线程到达 dowait() 的 index, getParties() - 1 为第一个到达, 0 为最后一个到达
     */
    private int dowait(boolean timed, long nanos) throws InterruptedException, BrokenBarrierException, TimeoutException {
        final ReentrantLock lock = this.lock;
        lock.lock(); // 加锁 -------------------------------------------------------------------
        try {
            final Generation g = generation;

            if (g.broken) throw new BrokenBarrierException();

            if (Thread.interrupted()) {
                // generation.broken = true, 唤醒已阻塞线程
                // "其它还没被阻塞的线程" 到达屏障点调用 dowait() 将抛出 BrokenBarrierException 异常
                breakBarrier();
                throw new InterruptedException(); // 当前线程被中断会抛出 InterruptedException 异常
            }

            // 最后一个到达屏障点的线程 -> command.run() -> nextGeneration() 唤醒所有线程 -> return 0
            // 如果 command.run() 抛出异常, 则调用 breakBarrier() 以标记本轮屏障已损坏并唤醒所有线程
            int index = --count;
            if (index == 0) {  // tripped
                // trip.printInfo(); // 用于调试打印 -----------------
                boolean ranAction = false;
                try {
                    final Runnable command = barrierCommand;
                    // 如果 command.run() 抛出异常, 将进入 finally
                    // command.run() 下面的 3 行代码将不会被执行
                    // 建议运行 Test9.test2() 测试代码
                    if (command != null) command.run();
                    ranAction = true;
                    nextGeneration();
                    return 0;
                } finally {
                    if (!ranAction) breakBarrier();
                }
            }

            // count != 0, 则需要被阻塞
            // loop until tripped, broken, interrupted, or timed out
            for (; ; ) {
                try {
                    // 阻塞中的线程将在这里被唤醒
                    if (!timed) trip.await();                            // 阻塞
                    else if (nanos > 0L) nanos = trip.awaitNanos(nanos); // 超时阻塞
                } catch (InterruptedException ie) {
                    if (g == generation && !g.broken) {
                        breakBarrier();
                        throw ie;
                    } else {
                        // We're about to finish waiting even if we had not
                        // been interrupted, so this interrupt is deemed to
                        // "belong" to subsequent execution.
                        Thread.currentThread().interrupt();
                    }
                }

                if (g.broken) throw new BrokenBarrierException();

                // 最后一个到达屏障点的线程会调用 nextGeneration() 更新 generation
                // 被唤醒的线程只有通过这里才能退出自旋
                // 返回值为 index in [1 ... parties - 1]
                if (g != generation) return index;

                if (timed && nanos <= 0L) {
                    breakBarrier();
                    throw new TimeoutException();
                }
            }
        } finally {
            lock.unlock(); // 释放锁 -----------------------------------------------------------
        }
    }

    // =================================================================================================================

    /**
     * <p>InterruptedException 当前线程被中断
     * <p>BrokenBarrierException 其它线程调用 await() 期间被中断
     */
    @SuppressWarnings("all")
    public int await() throws InterruptedException, BrokenBarrierException {
        try {
            return dowait(false, 0L);
        } catch (TimeoutException toe) {
            throw new Error(toe); // cannot happen
        }
    }

    /**
     * <p>InterruptedException 当前线程被中断
     * <p>BrokenBarrierException 其它线程调用 await() 期间被中断
     */
    public int await(long timeout, TimeUnit unit) throws InterruptedException, BrokenBarrierException, TimeoutException {
        return dowait(true, unit.toNanos(timeout));
    }

    // =================================================================================================================

    /**
     * 获取屏障需要阻塞的线程数
     */
    public int getParties() {
        return parties;
    }

    /**
     * 获取屏障已经阻塞的线程数
     */
    public int getNumberWaiting() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return parties - count;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 本轮屏障是否损坏
     */
    public boolean isBroken() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return generation.broken;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 重置屏障
     */
    public void reset() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            breakBarrier();   // break the current generation
            nextGeneration(); // start a new generation
        } finally {
            lock.unlock();
        }
    }
}
