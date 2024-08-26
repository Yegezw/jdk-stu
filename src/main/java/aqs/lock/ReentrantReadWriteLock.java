package aqs.lock;

import aqs.AbstractQueuedSynchronizer;
import aqs.AbstractQueuedSynchronizer.ConditionObject;
import aqs.Condition;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

public class ReentrantReadWriteLock implements ReadWriteLock {

    abstract static class Sync extends AbstractQueuedSynchronizer {

        // 用 state 变量中的低 16 位表示写锁的使用情况
        // 用 state 变量中的高 16 位表示读锁的使用情况
        static final int SHARED_SHIFT   = 16;
        static final int SHARED_UNIT    = (1 << SHARED_SHIFT);     // 共享单元
        static final int MAX_COUNT      = (1 << SHARED_SHIFT) - 1; // 最大计数
        static final int EXCLUSIVE_MASK = (1 << SHARED_SHIFT) - 1; // 独占掩码

        /**
         * 高 16 位共享数量
         */
        static int sharedCount(int c) {
            return c >>> SHARED_SHIFT;
        }

        /**
         * 低 16 位独占数量
         */
        static int exclusiveCount(int c) {
            return c & EXCLUSIVE_MASK;
        }

        // ============================================================================

        /**
         * 读锁计数器
         */
        static final class HoldCounter {
            int count = 0; // 线程自己记录自己获取读锁的次数
            final long tid = getThreadId(Thread.currentThread()); // Thread ID
        }

        static final class ThreadLocalHoldCounter extends ThreadLocal<HoldCounter> {
            public HoldCounter initialValue() {
                return new HoldCounter();
            }
        }

        private transient ThreadLocalHoldCounter readHolds;            // 用于将读次数写入 Thread.ThreadLocalMap
        private transient HoldCounter            cachedHoldCounter;    // 缓存的读锁计数器
        private transient Thread                 firstReader = null;   // 记录第一个拿到读锁的线程
        private transient int                    firstReaderHoldCount; // 记录 firstReader 获取读锁的次数

        Sync() {
            readHolds = new ThreadLocalHoldCounter();
            setState(getState()); // ensures visibility of readHolds
        }

        // ============================================================================

        abstract boolean readerShouldBlock();

        abstract boolean writerShouldBlock();

        /**
         * 独占锁: 尝试释放写锁
         */
        @Override
        protected final boolean tryRelease(int releases) {
            if (!isHeldExclusively()) throw new IllegalMonitorStateException();
            int     nextc = getState() - releases;
            boolean free  = exclusiveCount(nextc) == 0;
            if (free) setExclusiveOwnerThread(null);
            setState(nextc);
            return free;
        }

        /**
         * 独占锁: 尝试获取写锁
         */
        @Override
        protected final boolean tryAcquire(int acquires) {
            /*
             * Walkthrough:
             * 1. If read count nonzero or write count nonzero
             *    and owner is a different thread, fail.
             * 2. If count would saturate, fail. (This can only
             *    happen if count is already nonzero.)
             * 3. Otherwise, this thread is eligible for lock if
             *    it is either a reentrant acquire or
             *    queue policy allows it. If so, update state
             *    and set owner.
             */
            Thread current = Thread.currentThread();
            int    c       = getState();        // c = w + r
            int    w       = exclusiveCount(c); // 写锁数量

            // 已加锁
            if (c != 0) {
                // c != 0 && w == 0 意味着 r != 0(已加读锁), false
                // c != 0 && w != 0 && current != exclusiveOwnerThread 加写锁的线程不是自己, false
                if (w == 0 || current != getExclusiveOwnerThread()) return false;
                if (w + exclusiveCount(acquires) > MAX_COUNT) throw new Error("Maximum lock count exceeded");

                // w != 0 && current == exclusiveOwnerThread 加写锁的线程是自己
                setState(c + acquires); // 写锁可重入
                return true;
            }

            // 未加锁
            if (writerShouldBlock() || !compareAndSetState(c, c + acquires)) {
                return false;
            }
            setExclusiveOwnerThread(current);
            return true;
        }

        /**
         * 共享锁: 尝试释放读锁
         */
        @Override
        protected final boolean tryReleaseShared(int unused) {
            Thread current = Thread.currentThread();

            // 当前释放读锁的线程 current 是 firstReader
            if (firstReader == current) {
                // assert firstReaderHoldCount > 0;
                if (firstReaderHoldCount == 1) firstReader = null;
                else firstReaderHoldCount--; // 记录 firstReader 获取读锁的次数
            }
            // 其它线程, 即 current != firstReader
            else {
                HoldCounter rh = cachedHoldCounter;
                if (rh == null || rh.tid != getThreadId(current)) {
                    rh = readHolds.get(); // 拿到 current.ThreadLocalMap.rh
                }
                int count = rh.count;
                if (count <= 1) {
                    readHolds.remove();   // current 的读锁次数将为 0
                    if (count <= 0) throw unmatchedUnlockException();
                }
                --rh.count;
                // 注意这里可能会发生这种情况: rh = cachedHoldCounter = 当前线程的 rh
                // 当前线程释放完读锁后, current.ThreadLocalMap.remove(rh)
                // currentThread 已经删除了 rh, 但 cachedHoldCounter 还指向着被删除的 rh
            }

            // 自旋 CAS(c, c - SHARED_UNIT), return next == 0
            for (; ; ) {
                int c     = getState();
                int nextc = c - SHARED_UNIT;
                if (compareAndSetState(c, nextc))
                    // Releasing the read lock has no effect on readers,
                    // but it may allow waiting writers to proceed if
                    // both read and write locks are now free.
                    return nextc == 0;
            }
        }

        private IllegalMonitorStateException unmatchedUnlockException() {
            return new IllegalMonitorStateException("attempt to unlock read lock, not locked by current thread");
        }

        /**
         * 共享锁: 尝试获取读锁
         */
        @SuppressWarnings("all")
        @Override
        protected final int tryAcquireShared(int unused) {
            /*
             * Walkthrough:
             * 1. If write lock held by another thread, fail.
             * 2. Otherwise, this thread is eligible for
             *    lock wrt state, so ask if it should block
             *    because of queue policy. If not, try
             *    to grant by CASing state and updating count.
             *    Note that step does not check for reentrant
             *    acquires, which is postponed to full version
             *    to avoid having to check hold count in
             *    the more typical non-reentrant case.
             * 3. If step 2 fails either because thread
             *    apparently not eligible or CAS fails or count
             *    saturated, chain to version with full retry loop.
             */
            Thread current = Thread.currentThread();
            int    c       = getState();

            // w != 0 已加写锁 && exclusiveOwnerThread != current 加写锁的线程不是自己
            if (exclusiveCount(c) != 0 && getExclusiveOwnerThread() != current) return -1;

            // 未加写锁 || 加写锁的线程是自己(锁降级), 读不被阻塞的话, 就 CAS 尝试加读锁
            // NonFairSync.readerShouldBlock() 当 !head.next.isShared() 时返回 true
            // 即 sync queue head.next.thread 为写阻塞时, 申请加读锁将会被阻塞, 避免请求写锁的线程迟迟获取不到写锁
            // 因此在这里, 即使加写锁的线程是自己, 当 sync queue 头部仍有 EXCLUSIVE 节点时(别的线程处于写阻塞中), 锁降级也不会完成
            int r = sharedCount(c); // 读锁数量
            if (!readerShouldBlock() && r < MAX_COUNT && compareAndSetState(c, c + SHARED_UNIT)) {
                // 读锁第一次被获取, 即 current 是所有线程中第一个拿到读锁的线程
                if (r == 0) {
                    firstReader = current;    // 记录第一个拿到读锁的线程
                    firstReaderHoldCount = 1; // 记录 firstReader 获取读锁的次数
                }
                // 当前来拿读锁的线程 current 是 firstReader
                else if (firstReader == current) {
                    firstReaderHoldCount++;   // 锁可重入, 直接更新 firstReaderHoldCount
                }
                // 其它线程, 即 current != firstReader
                else {
                    HoldCounter rh = cachedHoldCounter; // 缓存的读锁计数器
                    if (rh == null || rh.tid != getThreadId(current)) {
                        // 更新缓存的读锁计数器
                        // rh.tid != current.tid 保证了第一次来获取读锁的线程会进入这个 if 语句
                        // readHolds.get() 会初始化 current.ThreadLocalMap + ThreadLocal.initialValue()
                        cachedHoldCounter = rh = readHolds.get();
                    } else if (rh.count == 0) {
                        // rh != null && rh.tid == current.tid && rh.count = 0
                        // 如果 rh 所在的线程刚刚释放读锁, 那么就会把 rh 删除, 但是此时又来获取读锁了, 又得重新设置 rh
                        // 这里与 tryReleaseShared(int unused) 中 --rh.count; 紧密联系
                        readHolds.set(rh);
                    }
                    rh.count++;
                }
                return 1; // 获取读锁成功
            }

            return fullTryAcquireShared(current); // 读应该被阻塞 || CAS(c, c + SHARED_UNIT) 失败
        }

        final int fullTryAcquireShared(Thread current) {
            HoldCounter rh = null; // 缓存的读锁计数器
            for (; ; ) {
                int c = getState();

                // w != 0 已加写锁, 看看加写锁的线程不是自己
                if (exclusiveCount(c) != 0) {
                    if (getExclusiveOwnerThread() != current) return -1;
                    // else we hold the exclusive lock; 
                    // blocking here would cause deadlock.
                }

                // 未加写锁, 看看读是否应该被阻塞
                // NonFairSync.readerShouldBlock() 当 !head.next.isShared() 时返回 true
                // 即 sync queue head.next.thread 为写阻塞时, 申请加读锁将会被阻塞, 避免请求写锁的线程迟迟获取不到写锁
                // 下面的代码用于保证: 可重入获取读锁的线程, 即使读应该被阻塞, 也不会被阻塞(因为是可重入获取读锁, 而不是第一次获取读锁)
                else if (readerShouldBlock()) {
                    // 读应该被阻塞
                    if (firstReader == current) {
                        // assert firstReaderHoldCount > 0;
                        // 如果是第一个读线程再次尝试获取读锁, 则直接通过, 读锁可重入
                    } else {
                        // 如果是其它线程尝试获取读锁, 则需要进一步检查当前线程的读锁计数, 以确定是否可以获取读锁
                        if (rh == null) {
                            rh = cachedHoldCounter;
                            if (rh == null || rh.tid != getThreadId(current)) {
                                rh = readHolds.get(); // 拿到当前正准备获取读锁的线程的 "读锁计数器"
                                if (rh.count == 0) {
                                    // 其它线程第一次获取读锁, 阻塞
                                    readHolds.remove();
                                } else {
                                    // 其它线程持有读锁, 再次获取读锁, 读锁可重入
                                }
                            }
                        }
                        if (rh.count == 0) return -1; // 其它线程第一次获取读锁, 阻塞
                    }
                }

                // 未加写锁且读未被阻塞 || 加写锁的线程是自己(锁降级在这里完成), CAS 尝试加读锁
                if (sharedCount(c) == MAX_COUNT) throw new Error("Maximum lock count exceeded");
                // 成功进入这个 if 则说明: CAS 成功 -> 成功获取读锁, 让调用该方法的线程通过 ThreadLocal 记录自己获取读锁的次数
                if (compareAndSetState(c, c + SHARED_UNIT)) {
                    // 读锁第一次被获取, 即 current 是所有线程中第一个拿到读锁的线程
                    if (sharedCount(c) == 0) {
                        firstReader = current;    // 记录第一个拿到读锁的线程
                        firstReaderHoldCount = 1; // 记录 firstReader 获取读锁的次数
                    }
                    // 当前来拿读锁的线程 current 是 firstReader
                    else if (firstReader == current) {
                        firstReaderHoldCount++;   // 锁可重入, 直接更新 firstReaderHoldCount
                    }
                    // 其它线程, 即 current != firstReader
                    else {
                        if (rh == null) rh = cachedHoldCounter; // 缓存的读锁计数器
                        // readHolds.get() 更新缓存的读锁计数器
                        // rh.tid != current.tid 保证了第一次来获取读锁的线程会进入这个 if 语句
                        // readHolds.get() 会初始化 current.ThreadLocalMap + initialValue()
                        if (rh == null || rh.tid != getThreadId(current)) rh = readHolds.get();
                        // rh != null && rh.tid == current.tid && rh.count = θ
                        // 如果 rh 所在的线程刚刚释放读锁, 那么就会把 rh 删除, 但是此时又来获取读锁了, 又得重新设置 rh
                        // 这里与 tryReleaseShared(int unused) 中 --rh.count; 紧密联系
                        else if (rh.count == 0) readHolds.set(rh);
                        rh.count++;
                        cachedHoldCounter = rh; // cache for release
                    }
                    return 1; // 获取读锁成功
                }
            }
        }

        /**
         * 不走 AQS
         */
        final boolean tryWriteLock() {
            Thread current = Thread.currentThread();
            int    c       = getState();
            if (c != 0) {
                int w = exclusiveCount(c);
                if (w == 0 || current != getExclusiveOwnerThread()) return false;
                if (w == MAX_COUNT) throw new Error("Maximum lock count exceeded");
            }
            if (!compareAndSetState(c, c + 1)) return false;
            setExclusiveOwnerThread(current);
            return true;
        }

        /**
         * 不走 AQS
         */
        final boolean tryReadLock() {
            Thread current = Thread.currentThread();
            for (; ; ) {
                int c = getState();
                if (exclusiveCount(c) != 0 && getExclusiveOwnerThread() != current)
                    return false;
                int r = sharedCount(c);
                if (r == MAX_COUNT) throw new Error("Maximum lock count exceeded");
                if (compareAndSetState(c, c + SHARED_UNIT)) {
                    if (r == 0) {
                        firstReader = current;
                        firstReaderHoldCount = 1;
                    } else if (firstReader == current) {
                        firstReaderHoldCount++;
                    } else {
                        HoldCounter rh = cachedHoldCounter;
                        if (rh == null || rh.tid != getThreadId(current)) cachedHoldCounter = rh = readHolds.get();
                        else if (rh.count == 0) readHolds.set(rh);
                        rh.count++;
                    }
                    return true;
                }
            }
        }

        /**
         * 独占所有者线程(写线程) == 当前线程 ?
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

        // ============================================================================

        /**
         * 获取持有写锁的线程
         */
        final Thread getOwner() {
            // Must read state before owner to ensure memory consistency
            return ((exclusiveCount(getState()) == 0) ? null : getExclusiveOwnerThread());
        }

        /**
         * 获取读锁总次数
         */
        final int getReadLockCount() {
            return sharedCount(getState());
        }

        /**
         * 已上写锁 ?
         */
        final boolean isWriteLocked() {
            return exclusiveCount(getState()) != 0;
        }

        /**
         * 获取写锁次数
         * <br>注意: 持有写锁的线程才能调用该方法
         */
        final int getWriteHoldCount() {
            return isHeldExclusively() ? exclusiveCount(getState()) : 0;
        }

        /**
         * 返回当前线程获取读锁的次数
         */
        final int getReadHoldCount() {
            if (getReadLockCount() == 0) return 0;

            Thread current = Thread.currentThread();
            if (firstReader == current) return firstReaderHoldCount;

            HoldCounter rh = cachedHoldCounter;
            if (rh != null && rh.tid == getThreadId(current)) return rh.count;

            int count = readHolds.get().count;
            if (count == 0) readHolds.remove();
            return count;
        }

        /**
         * 获取 state
         */
        final int getCount() {
            return getState();
        }
    }

    static final class NonfairSync extends Sync {

        @Override
        boolean writerShouldBlock() {
            return false; // writers can always barge
        }

        @Override
        boolean readerShouldBlock() {
            /* As a heuristic to avoid indefinite writer starvation,
             * block if the thread that momentarily appears to be head
             * of queue, if one exists, is a waiting writer.  This is
             * only a probabilistic effect since a new reader will not
             * block if there is a waiting writer behind other enabled
             * readers that have not yet drained from the queue.
             */
            return apparentlyFirstQueuedIsExclusive();
        }
    }

    static final class FairSync extends Sync {

        @Override
        boolean writerShouldBlock() {
            return hasQueuedPredecessors();
        }

        @Override
        boolean readerShouldBlock() {
            return hasQueuedPredecessors();
        }
    }

    // =================================================================================================================

    /**
     * 读锁是共享锁, 它会调用 AQS 共享模式的 4 个模板方法
     */
    public static class ReadLock implements Lock {

        private final Sync sync;

        protected ReadLock(ReentrantReadWriteLock lock) {
            sync = lock.sync;
        }

        @Override
        public void lock() {
            sync.acquireShared(1);
        }

        @Override
        public void lockInterruptibly() throws InterruptedException {
            sync.acquireSharedInterruptibly(1);
        }

        @Override
        public boolean tryLock() {
            return sync.tryReadLock(); // 不执行 AQS 流程, 执行共享模式的获取锁流程
        }

        @Override
        public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
            return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
        }

        @Override
        public void unlock() {
            sync.releaseShared(1);
        }

        /**
         * 持有读锁的线程不能创建 condition queue
         */
        @Override
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * 写锁是排它锁, 它会调用 AQS 独占模式的 4 个模板方法
     */
    public static class WriteLock implements Lock {

        private final Sync sync;

        protected WriteLock(ReentrantReadWriteLock lock) {
            sync = lock.sync;
        }

        @Override
        public void lock() {
            sync.acquire(1);
        }

        @Override
        public void lockInterruptibly() throws InterruptedException {
            sync.acquireInterruptibly(1);
        }

        @Override
        public boolean tryLock() {
            return sync.tryWriteLock(); // 不执行 AQS 流程, 执行独占模式的获取锁流程
        }

        @Override
        public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
            return sync.tryAcquireNanos(1, unit.toNanos(timeout));
        }

        @Override
        public void unlock() {
            sync.release(1);
        }

        /**
         * 持有写锁的线程可以创建 condition queue
         */
        @Override
        public Condition newCondition() {
            return sync.newCondition();
        }

        /**
         * 独占所有者线程(写线程) == 当前线程 ?
         */
        public boolean isHeldByCurrentThread() {
            return sync.isHeldExclusively();
        }

        /**
         * 获取写锁次数
         * <br>注意: 持有写锁的线程才能调用该方法
         */
        public int getHoldCount() {
            return sync.getWriteHoldCount();
        }
    }

    // =================================================================================================================

    private final ReadLock  readerLock;
    private final WriteLock writerLock;
    final         Sync      sync;

    public ReentrantReadWriteLock() {
        this(false); // 默认非公平锁
    }

    public ReentrantReadWriteLock(boolean fair) {
        sync = fair ? new FairSync() : new NonfairSync();
        readerLock = new ReadLock(this);  // 读锁最终由 Sync 类实现
        writerLock = new WriteLock(this); // 写锁最终由 Sync 类实现
    }

    // =================================================================================================================

    private static final Unsafe unsafe;
    private static final long   TID_OFFSET;

    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = (Unsafe) field.get(null);
            TID_OFFSET = unsafe.objectFieldOffset(Thread.class.getDeclaredField("tid"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static final long getThreadId(Thread thread) {
        return unsafe.getLongVolatile(thread, TID_OFFSET);
    }

    // =================================================================================================================

    @Override
    public ReadLock readLock() {
        return readerLock;
    }

    @Override
    public WriteLock writeLock() {
        return writerLock;
    }

    public void printInfo() {
        sync.printInfo();
    }

    // =================================================================================================================

    /**
     * 公平锁 ?
     */
    public final boolean isFair() {
        return sync instanceof FairSync;
    }

    /**
     * 获取持有写锁的线程
     */
    protected Thread getOwner() {
        return sync.getOwner();
    }

    /**
     * 获取读锁总次数
     */
    public int getReadLockCount() {
        return sync.getReadLockCount();
    }

    /**
     * 已上写锁 ?
     */
    public boolean isWriteLocked() {
        return sync.isWriteLocked();
    }

    /**
     * 独占所有者线程(写线程) == 当前线程 ?
     */
    public boolean isWriteLockedByCurrentThread() {
        return sync.isHeldExclusively();
    }

    /**
     * 获取写锁次数
     * <br>注意: 持有写锁的线程才能调用该方法
     */
    public int getWriteHoldCount() {
        return sync.getWriteHoldCount();
    }

    /**
     * 返回当前线程获取读锁的次数
     */
    public int getReadHoldCount() {
        return sync.getReadHoldCount();
    }

    // ------------------------------------------------

    /**
     * sync queue ExclusiveNode.thread
     */
    protected Collection<Thread> getQueuedWriterThreads() {
        return sync.getExclusiveQueuedThreads();
    }

    /**
     * sync queue SharedNode.thread
     */
    protected Collection<Thread> getQueuedReaderThreads() {
        return sync.getSharedQueuedThreads();
    }

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
     * WriteLock 创建的 condition queue 不为空 ?
     * <br>注意: 持有锁的线程才能调用该方法
     */
    public boolean hasWaiters(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.hasWaiters((ConditionObject) condition);
    }

    /**
     * WriteLock 创建的 condition queue length
     * <br>注意: 持有锁的线程才能调用该方法
     */
    public int getWaitQueueLength(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitQueueLength((ConditionObject) condition);
    }

    /**
     * WriteLock 创建的 condition queue Node.thread
     * <br>注意: 持有锁的线程才能调用该方法
     */
    protected Collection<Thread> getWaitingThreads(java.util.concurrent.locks.Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitingThreads((ConditionObject) condition);
    }

    public String toString() {
        int c = sync.getCount();
        int w = Sync.exclusiveCount(c);
        int r = Sync.sharedCount(c);

        return super.toString() + "[Write locks = " + w + ", Read locks = " + r + "]";
    }
}
