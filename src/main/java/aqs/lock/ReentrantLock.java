package aqs.lock;

import aqs.AbstractQueuedSynchronizer;
import aqs.AbstractQueuedSynchronizer.ConditionObject;
import aqs.Condition;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

public class ReentrantLock implements Lock {

    /**
     * <p>
     * tryAcquire() 获取锁: CAS 修改 state, 成功则获取锁 exclusiveOwnerThread = current<br>
     * 加锁失败后, 由 AQS 实现: 当前线程进入同步队列 + 阻塞当前线程 + 当前线程被唤醒后竞争锁
     * </p>
     * <p>
     * tryRelease() 释放锁: CAS 修改 state, state = 0 则释放锁 exclusiveOwnerThread = null<br>
     * 解锁成功后, 由 AQS 实现: 唤醒同步队列队 head.next 节点的线程 + 竞争锁 + 设置自己为 head 节点
     * </p>
     * <p>
     * 公平锁保证了锁的获取按 FIFO 原则, 而代价是进行大量的线程切换<br>
     * 加入等待队列并调用 park() 函数阻塞线程, 涉及到用户态和内核态的切换, 比较耗时<br>
     * 对于非公平锁来说, 新来的线程直接竞争锁, 这样就有可能 "避免加入等待队列并调用费时的 park() 函数"<br>
     * 因此非公平锁虽然可能会造成线程 "饥饿", 但极少的线程切换保证了更大的吞吐量
     * </p>
     */
    abstract static class Sync extends AbstractQueuedSynchronizer {

        abstract void lock();

        final boolean nonfairTryAcquire(int acquires) {
            final Thread current = Thread.currentThread();
            int          c       = getState();

            // 锁没有被占用
            if (c == 0) {
                // 尝试获取锁(CAS 设置 state 值为 acquires)
                if (compareAndSetState(0, acquires)) {
                    setExclusiveOwnerThread(current);
                    return true;
                }
            }
            // 锁被当前线程占用
            else if (current == getExclusiveOwnerThread()) {
                int nextc = c + acquires;
                if (nextc < 0) throw new Error("Maximum lock count exceeded"); // overflow
                setState(nextc); // 锁可重入, 这里不用保证线程安全, 只有获取到锁的线程才会调用它
                return true;
            }

            return false; // 锁被其它线程占用
        }

        /**
         * 不需要保证线程安全
         */
        @Override
        protected final boolean tryRelease(int releases) {
            int c = getState() - releases; // c = state - releases
            if (Thread.currentThread() != getExclusiveOwnerThread()) throw new IllegalMonitorStateException();

            boolean free = false;
            if (c == 0) {
                free = true;
                setExclusiveOwnerThread(null);
            }

            setState(c);
            return free;
        }

        // ------------------------------------------------

        /**
         * 独占所有者线程 == 当前线程 ?
         */
        @Override
        protected final boolean isHeldExclusively() {
            // While we must in general read state before owner,
            // we don't need to do so to check if current thread is owner
            return getExclusiveOwnerThread() == Thread.currentThread();
        }

        /**
         * 创建条件变量 condition queue
         */
        final ConditionObject newCondition() {
            return new ConditionObject();
        }

        // Methods relayed from outer class ------------------------------------------------

        /**
         * 获取持有锁的线程
         */
        final Thread getOwner() {
            return getState() == 0 ? null : getExclusiveOwnerThread();
        }

        /**
         * 获取锁的重入次数
         */
        final int getHoldCount() {
            return isHeldExclusively() ? getState() : 0;
        }

        /**
         * 已锁定 ?
         */
        final boolean isLocked() {
            return getState() != 0;
        }
    }

    /**
     * 非公平锁
     */
    static final class NonfairSync extends Sync {

        @Override
        final void lock() {
            if (compareAndSetState(0, 1)) {
                setExclusiveOwnerThread(Thread.currentThread());
            } else {
                acquire(1); // AQS 的模板方法 acquire() 会回调 tryAcquire()
            }
        }

        @Override
        protected final boolean tryAcquire(int acquires) {
            return nonfairTryAcquire(acquires);
        }
    }

    /**
     * 公平锁
     */
    static final class FairSync extends Sync {

        @Override
        final void lock() {
            acquire(1); // AQS 的模板方法 acquire() 会回调 tryAcquire()
        }

        @Override
        protected final boolean tryAcquire(int acquires) {
            final Thread current = Thread.currentThread();
            int          c       = getState();

            // 锁没有被占用
            if (c == 0) {
                // 当前线程位于队列的头部或队列为空时, 才尝试获取锁(CAS 设置 state 值为 acquires)
                if (!hasQueuedPredecessors() && compareAndSetState(0, acquires)) {
                    setExclusiveOwnerThread(current);
                    return true;
                }
            }
            // 锁被当前线程占用
            else if (current == getExclusiveOwnerThread()) {
                int nextc = c + acquires;
                if (nextc < 0) throw new Error("Maximum lock count exceeded"); // overflow
                setState(nextc); // 锁可重入, 这里不用保证线程安全, 只有获取到锁的线程才会调用它
                return true;
            }

            return false; // 锁被其它线程占用
        }
    }

    // =================================================================================================================

    private final Sync sync;

    public ReentrantLock() {
        sync = new NonfairSync(); // 默认非公平锁
    }

    public ReentrantLock(boolean fair) {
        sync = fair ? new FairSync() : new NonfairSync();
    }

    // =================================================================================================================

    @Override
    public void lock() {
        sync.lock(); // 它会调用 AQS 的模板方法 acquire()
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        sync.acquireInterruptibly(1);
    }

    @Override
    public boolean tryLock() {
        return sync.nonfairTryAcquire(1); // 不执行 AQS 流程, 执行非公平锁的获取锁流程
    }

    @Override
    public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
        return sync.tryAcquireNanos(1, unit.toNanos(timeout));
    }

    @Override
    public void unlock() {
        sync.release(1);
    }

    @Override
    public Condition newCondition() {
        return sync.newCondition();
    }

    // =================================================================================================================

    /**
     * 获取锁的重入次数
     */
    public int getHoldCount() {
        return sync.getHoldCount();
    }

    /**
     * 独占所有者线程 == 当前线程 ?
     */
    public boolean isHeldByCurrentThread() {
        return sync.isHeldExclusively();
    }

    /**
     * 已锁定 ?
     */
    public boolean isLocked() {
        return sync.isLocked();
    }

    /**
     * 公平锁 ?
     */
    public final boolean isFair() {
        return sync instanceof FairSync;
    }

    /**
     * 获取持有锁的线程
     */
    protected Thread getOwner() {
        return sync.getOwner();
    }

    // ------------------------------------------------

    /**
     * sync queue 不为空 ?
     */
    public final boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    /**
     * thread in sync queue ?
     */
    public final boolean hasQueuedThread(Thread thread) {
        return sync.isQueued(thread);
    }

    /**
     * sync queue length
     */
    public final int getQueueLength() {
        return sync.getQueueLength();
    }

    /**
     * sync queue Node.thread
     */
    protected Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }

    // ------------------------------------------------

    /**
     * Lock 创建的 condition queue 不为空 ?
     * <br>注意: 持有锁的线程才能调用该方法
     */
    public boolean hasWaiters(Condition condition) {
        if (condition == null) throw new NullPointerException();
        if (!(condition instanceof ConditionObject)) throw new IllegalArgumentException("not owner");
        return sync.hasWaiters((ConditionObject) condition);
    }

    /**
     * Lock 创建的 condition queue length
     * <br>注意: 持有锁的线程才能调用该方法
     */
    public int getWaitQueueLength(Condition condition) {
        if (condition == null) throw new NullPointerException();
        if (!(condition instanceof ConditionObject)) throw new IllegalArgumentException("not owner");
        return sync.getWaitQueueLength((ConditionObject) condition);
    }

    /**
     * Lock 创建的 condition queue Node.thread
     * <br>注意: 持有锁的线程才能调用该方法
     */
    protected Collection<Thread> getWaitingThreads(Condition condition) {
        if (condition == null) throw new NullPointerException();
        if (!(condition instanceof ConditionObject)) throw new IllegalArgumentException("not owner");
        return sync.getWaitingThreads((ConditionObject) condition);
    }
}
