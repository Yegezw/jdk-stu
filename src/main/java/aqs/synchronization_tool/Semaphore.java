package aqs.synchronization_tool;

import aqs.AbstractQueuedSynchronizer;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

public class Semaphore {

    abstract static class Sync extends AbstractQueuedSynchronizer {

        Sync(int permits) {
            setState(permits);
        }

        final int getPermits() {
            return getState();
        }

        final int nonfairTryAcquireShared(int acquires) {
            for (; ; ) {
                int available = getState();
                int remaining = available - acquires;
                if (remaining < 0 || compareAndSetState(available, remaining)) {
                    // remaining < 0 直接返回, AQS 负责阻塞
                    // remaining >= 0, CAS 更新 state, CAS 失败会进行自旋
                    return remaining;
                }
            }
        }

        @Override
        protected final boolean tryReleaseShared(int releases) {
            for (; ; ) {
                int current = getState();
                int next    = current + releases;

                // overflow
                if (next < current) throw new Error("Maximum permit count exceeded");

                // 注意这里, 永远返回 true
                if (compareAndSetState(current, next)) return true;
            }
        }

        /**
         * 减少许可
         */
        final void reducePermits(int reductions) {
            // 类似 nonfairTryAcquireShared()
            for (; ; ) {
                int current = getState();
                int next    = current - reductions;

                // underflow
                if (next > current) throw new Error("Permit count underflow");

                // next <= current, CAS 更新 state, CAS 失败会进行自旋
                if (compareAndSetState(current, next)) return;
            }
        }

        /**
         * 重置许可
         */
        final int drainPermits() {
            for (; ; ) {
                int current = getState();
                if (current == 0 || compareAndSetState(current, 0)) {
                    // current == 0 则 return 0
                    // current != 0 则 CAS 更新 state = 0, CAS 失败会进行自旋, 最终返回 current
                    return current;
                }
            }
        }
    }

    static final class NonfairSync extends Sync {

        NonfairSync(int permits) {
            super(permits);
        }

        @Override
        protected int tryAcquireShared(int acquires) {
            return nonfairTryAcquireShared(acquires);
        }
    }


    static final class FairSync extends Sync {

        FairSync(int permits) {
            super(permits);
        }

        @Override
        protected int tryAcquireShared(int acquires) {
            for (; ; ) {
                // 有排队的前置任务 return -1,  AQS 负责阻塞
                if (hasQueuedPredecessors()) return -1;

                int available = getState();
                int remaining = available - acquires;
                if (remaining < 0 || compareAndSetState(available, remaining)) {
                    // remaining < 0 直接返回, AQS 负责阻塞
                    // remaining >= 0, CAS 更新 state, CAS 失败会进行自旋
                    return remaining;
                }
            }
        }
    }

    // =================================================================================================================

    private final Sync sync;

    public Semaphore(int permits) {
        sync = new NonfairSync(permits); // 默认非公平锁
    }

    public Semaphore(int permits, boolean fair) {
        sync = fair ? new FairSync(permits) : new NonfairSync(permits);
    }

    // =================================================================================================================

    public void acquire() throws InterruptedException {
        sync.acquireSharedInterruptibly(1);
    }

    public void acquireUninterruptibly() {
        sync.acquireShared(1);
    }

    public boolean tryAcquire() {
        return sync.nonfairTryAcquireShared(1) >= 0; // 不执行 AQS 流程, 执行非公平锁的获取锁流程
    }

    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
    }

    public void release() {
        sync.releaseShared(1);
    }

    // ----------------------------------

    public void acquire(int permits) throws InterruptedException {
        if (permits < 0) throw new IllegalArgumentException();
        sync.acquireSharedInterruptibly(permits);
    }

    public void acquireUninterruptibly(int permits) {
        if (permits < 0) throw new IllegalArgumentException();
        sync.acquireShared(permits);
    }

    public boolean tryAcquire(int permits) {
        if (permits < 0) throw new IllegalArgumentException();
        return sync.nonfairTryAcquireShared(permits) >= 0; // 不执行 AQS 流程, 执行非公平锁的获取锁流程
    }

    public boolean tryAcquire(int permits, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (permits < 0) throw new IllegalArgumentException();
        return sync.tryAcquireSharedNanos(permits, unit.toNanos(timeout));
    }

    public void release(int permits) {
        if (permits < 0) throw new IllegalArgumentException();
        sync.releaseShared(permits);
    }

    // =================================================================================================================


    public int availablePermits() {
        return sync.getPermits();
    }


    public int drainPermits() {
        return sync.drainPermits();
    }

    protected void reducePermits(int reduction) {
        if (reduction < 0) throw new IllegalArgumentException();
        sync.reducePermits(reduction);
    }

    public boolean isFair() {
        return sync instanceof FairSync;
    }

    public final boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    public final int getQueueLength() {
        return sync.getQueueLength();
    }

    protected Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }

    public String toString() {
        return super.toString() + "[Permits = " + sync.getPermits() + "]";
    }
}
