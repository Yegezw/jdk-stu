package aqs;

import aqs.Queue.Node;
import sun.misc.Unsafe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * <p>阻塞: 新节点入队, 将前驱节点 waitStatus = -1, 如果前驱是 head 再给一次抢锁的机会, 最后调用 unpark() 阻塞
 * <p>释放: 设置 head.waitStatus = 0, unpark() 唤醒后继节点的线程
 * <p>醒来: 线程被 unpark() 唤醒后, 尝试获取锁, 获取成功后将自己设置为头节点
 */
public abstract class AbstractQueuedSynchronizer extends AbstractOwnableSynchronizer {

    // 1000 ns = 1 ms
    // 1000 ns 的自旋比 1000 ns 的时间阻塞更快, 粗略的估计, 足以在非常短的超时时间内提高响应速度
    // 也就是说, 当线程被中断唤醒, 如果剩余阻塞时间 <= 1000 ns, 那么当前线程将自旋而不是调用 parkNanos(nanosTimeout)
    static final long spinForTimeoutThreshold = 1000L;

    private final Queue queue = new Queue();

    /**
     * 锁没有被占用 0、锁已经被占用 1、锁的重入次数大于 1
     */
    volatile             int    state;
    private static final long   stateOffset;
    private static final Unsafe unsafe;

    static {
        try {
            unsafe = Queue.unsafe;
            stateOffset = unsafe.objectFieldOffset(AbstractQueuedSynchronizer.class.getDeclaredField("state"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected AbstractQueuedSynchronizer() {
    }

    // Node 单链表: thread、waitStatus、nextWaiter
    public class ConditionObject implements Condition {

        private transient Node firstWaiter;
        private transient Node lastWaiter;

        private static final int REINTERRUPT = 1;
        private static final int THROW_IE    = -1;

        public ConditionObject() {
        }

        // ------------------------------------------------

        final boolean transferForSignal(Node node) {
            // 如果无法更改 waitStatus, 则表示节点已被取消
            if (!Queue.compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
                return false;
            }

            Node p  = queue.enq(node); // 将 node 放入 sync queue 中, 返回值为 node 的前驱节点
            int  ws = p.waitStatus;
            // 只要前驱节点处于 "取消状态" 或者 "无法将前驱节点的状态修改成 Node.SIGNAL", 那就将 node 所代表的线程唤醒
            if (ws > 0 || !Queue.compareAndSetWaitStatus(p, ws, Node.SIGNAL)) {
                LockSupport.unpark(node.thread);
                // node 所代表的线程被唤醒后, 会调用 acquireQueued()
            }
            return true;
        }

        private Node addConditionWaiter() {
            Node t = lastWaiter;

            // If lastWaiter is cancelled, clean out.
            if (t != null && t.waitStatus != Node.CONDITION) {
                unlinkCancelledWaiters();
                t = lastWaiter;
            }

            Node node = new Node(Thread.currentThread(), Node.CONDITION);
            if (t == null) firstWaiter = node;
            else t.nextWaiter = node;
            lastWaiter = node;
            return node;
        }

        // 清除 waitStatus != CONDITION 的节点
        private void unlinkCancelledWaiters() {
            Node trail = null; // [0 ... t] 的最后一个正常节点

            Node t = firstWaiter;
            while (t != null) {
                Node next = t.nextWaiter;

                if (t.waitStatus != Node.CONDITION) {
                    // 清除 t
                    t.nextWaiter = null;

                    if (trail == null) firstWaiter = next; // t 前无正常节点
                    else trail.nextWaiter = next;          // t 前有正常节点

                    if (next == null) lastWaiter = trail;
                } else {
                    // 正常节点
                    trail = t;
                }

                t = next;
            }
        }

        // ------------------------------------------------

        @Override
        public void await() throws InterruptedException {
            if (Thread.interrupted()) throw new InterruptedException();

            Node node          = addConditionWaiter();
            int  savedState    = fullyRelease(node);
            int  interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                LockSupport.park(this); // 线程将在这里苏醒: signal() || 中断
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0) break;
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE) {
                interruptMode = REINTERRUPT;
            }

            // 当中断导致的搬迁时
            // node.ws == 0 && node.nextWaiter != null
            if (node.nextWaiter != null) {
                unlinkCancelledWaiters(); // 将 node 与 node.nextWaiter 断开
            }
            if (interruptMode != 0) {
                reportInterruptAfterWait(interruptMode);
            }
        }

        @Override
        public void awaitUninterruptibly() {
            Node node       = addConditionWaiter();
            int  savedState = fullyRelease(node); // 将 state 修改为 0, 表示释放了锁

            boolean interrupted = false;
            // 调用 park() 函数来阻塞线程, 线程被唤醒有两种情况: 中断、signal()、signalAll()
            while (!isOnSyncQueue(node)) {
                LockSupport.park(this);
                // 能执行到这里说明
                // 1、signal() 使 node 位于 sync queue, 且 node.prev.thread 释放锁后调用 unpark(head.next.thread)
                // 2、线程被中断了, node 还在 condition queue 中
                if (Thread.interrupted()) interrupted = true;
            }

            // 调用独占模式 acquireQueued() 方法排队等待锁
            if (acquireQueued(node, savedState) || interrupted) selfInterrupt();
        }

        @Override
        public long awaitNanos(long nanosTimeout) throws InterruptedException {
            if (Thread.interrupted()) throw new InterruptedException();

            Node       node          = addConditionWaiter();
            int        savedState    = fullyRelease(node);
            final long deadline      = System.nanoTime() + nanosTimeout;
            int        interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    // 超时返回前, 调用取消等待后转移
                    // CAS node.ws = 0, enq(node) 将 node 搬到 sync queue 中
                    // 这里并没有断开 node.nextWaiter
                    transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold) {
                    // 线程将在这里苏醒: signal() || 中断 || 超时
                    LockSupport.parkNanos(this, nanosTimeout);
                }
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0) break;
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE) {
                interruptMode = REINTERRUPT;
            }

            // 当中断 || 超时导致的搬迁时
            // node.ws == 0 && node.nextWaiter != null
            if (node.nextWaiter != null) {
                unlinkCancelledWaiters(); // 将 node 与 node.nextWaiter 断开
            }
            if (interruptMode != 0) {
                reportInterruptAfterWait(interruptMode);
            }

            // 走到这里证明由 signal() || 超时引起转移, 且已经获得了锁(需要时间), 返回剩余等待时间
            // 由 signal() 引起转移虽然没超时, 但加上 "获得锁所花费的时间" 就有可能导致超时, 因而返回值 < 0
            // 返回值 > 0 代表一定由 signal() 引起转移
            return deadline - System.nanoTime();
        }

        @Override
        public boolean await(long time, TimeUnit unit) throws InterruptedException {
            long nanosTimeout = unit.toNanos(time);
            if (Thread.interrupted()) throw new InterruptedException();

            Node       node          = addConditionWaiter();
            int        savedState    = fullyRelease(node);
            final long deadline      = System.nanoTime() + nanosTimeout;
            boolean    timedout      = false;
            int        interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    // 超时返回前, 调用取消等待后转移
                    // CAS node.ws = 0, enq(node) 将 node 搬到 sync queue 中, 返回 true 表示超时返回
                    // 这里并没有断开 node.nextWaiter
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold) {
                    // 线程将在这里苏醒: signal() || 中断 || 超时
                    LockSupport.parkNanos(this, nanosTimeout);
                }
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0) break;
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE) {
                interruptMode = REINTERRUPT;
            }

            // 当中断 || 超时导致的搬迁时
            // node.ws == 0 && node.nextWaiter != null
            if (node.nextWaiter != null) {
                unlinkCancelledWaiters(); // 将 node 与 node.nextWaiter 断开
            }
            if (interruptMode != 0) {
                reportInterruptAfterWait(interruptMode);
            }

            // 走到这里证明由 signal() || 超时引起转移
            // 如果是 signal() 则返回 true, 如果是超时则返回 false
            return !timedout;
        }

        @Override
        public boolean awaitUntil(Date deadline) throws InterruptedException {
            long abstime = deadline.getTime();
            if (Thread.interrupted()) throw new InterruptedException();

            Node    node          = addConditionWaiter();
            int     savedState    = fullyRelease(node);
            boolean timedout      = false;
            int     interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (System.currentTimeMillis() > abstime) {
                    // 超时返回前, 调用取消等待后转移
                    // CAS node.ws = 0, enq(node) 将 node 搬到 sync queue 中, 返回 true 表示超时返回
                    // 这里并没有断开 node.nextWaiter
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                // 线程将在这里苏醒: signal() || 中断 || 超时
                LockSupport.parkUntil(this, abstime);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0) break;
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE) {
                interruptMode = REINTERRUPT;
            }

            // 当中断 || 超时导致的搬迁时
            // node.ws == 0 && node.nextWaiter != null
            if (node.nextWaiter != null) {
                unlinkCancelledWaiters(); // 将 node 与 node.nextWaiter 断开
            }
            if (interruptMode != 0) {
                reportInterruptAfterWait(interruptMode);
            }

            // 走到这里证明由 signal() || 超时引起转移
            // 如果是 signal() 则返回 true, 如果是超时则返回 false
            return !timedout;
        }

        // ------------------------------------------------

        private int checkInterruptWhileWaiting(Node node) {
            // interruptMode
            // 0: 整个过程中一直没有中断发生
            // THROW_IE: 中断发生在 signal() 之前, await() 返回前需要抛出 InterruptedException
            // REINTERRUPT: 中断发生在 signal() 之后, 中断晚了, await() 返回前需要需要再自我中断一下
            return Thread.interrupted() ?
                    (transferAfterCancelledWait(node) ? THROW_IE : REINTERRUPT) :
                    0;
        }

        /**
         * 取消等待后转移
         * <p>返回值的语义为: 该函数负责将 node 取消等待 + 转移 node 到 sync queue 了吗
         * <p>中断发生前 node 线程没被 signal(), 则需要该方法 enq(node) 转移, 最终返回 true
         * <p>中断发生前 node 线程被 signal() 过, 则不需要该方法 enq(node) 转移, 最终返回 false
         */
        @SuppressWarnings("all")
        final boolean transferAfterCancelledWait(Node node) {
            // node 线程没被 signal(), 那么 node 为 CONDITION
            // 这里 CAS 置 0, enq(node) 将 node 搬到 sync queue 中，返回 true
            // 注意: 这里并没有断开 node.nextWaiter(因为中断导致的搬迁)
            if (queue.compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
                queue.enq(node);
                return true;
            }

            // node 线程有被 signal() 过, 那么 node 不为 CONDITION
            // 去 sync queue 查看 node 是否存在 
            // 不存在就 yield(), 用于等待 signal() 线程 enq(node) 完成
            while (!isOnSyncQueue(node)) Thread.yield();

            return false; // 最后返回 false
        }

        private void reportInterruptAfterWait(int interruptMode) throws InterruptedException {
            // interruptMode
            // 0: 整个过程中一直没有中断发生
            // THROW_IE: 中断发生在 signal() 之前, await() 返回前需要抛出 InterruptedException
            // REINTERRUPT: 中断发生在 signal() 之后, 中断晚了, await() 返回前需要需要再自我中断一下
            if (interruptMode == THROW_IE) throw new InterruptedException();
            else if (interruptMode == REINTERRUPT) selfInterrupt();
        }

        // ------------------------------------------------

        @Override
        public void signal() {
            if (!isHeldExclusively()) throw new IllegalMonitorStateException();
            Node first = firstWaiter;
            if (first != null) doSignal(first);
        }

        @Override
        public void signalAll() {
            if (!isHeldExclusively()) throw new IllegalMonitorStateException();
            Node first = firstWaiter;
            if (first != null) doSignalAll(first);
        }

        private void doSignal(Node first) {
            do {
                // 将 firstWaiter 指向条件队列队头的下一个节点
                if ((firstWaiter = first.nextWaiter) == null) {
                    lastWaiter = null;
                }
                // 将 first 从条件队列中断开, 则此时 first 成为一个孤立的节点
                first.nextWaiter = null;
            } while (!transferForSignal(first) && (first = firstWaiter) != null);
        }

        private void doSignalAll(Node first) {
            // 将整个条件队列清空
            lastWaiter = firstWaiter = null;

            // 将原先的 condition queue 里面的节点一个一个拿出来
            // 再通过 transferForSignal() 一个一个添加到 sync queue 的末尾
            do {
                Node next = first.nextWaiter;
                first.nextWaiter = null;
                transferForSignal(first);
                first = next;
            } while (first != null);
        }

        //  support for instrumentation ------------------------------------------------

        final boolean isOwnedBy(AbstractQueuedSynchronizer sync) {
            return sync == AbstractQueuedSynchronizer.this;
        }

        protected final boolean hasWaiters() {
            if (!isHeldExclusively()) throw new IllegalMonitorStateException();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION) return true;
            }
            return false;
        }

        protected final int getWaitQueueLength() {
            if (!isHeldExclusively()) throw new IllegalMonitorStateException();
            int n = 0;
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION) ++n;
            }
            return n;
        }

        protected final Collection<Thread> getWaitingThreads() {
            if (!isHeldExclusively()) throw new IllegalMonitorStateException();
            ArrayList<Thread> list = new ArrayList<>();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION) {
                    Thread t = w.thread;
                    if (t != null) list.add(t);
                }
            }
            return list;
        }

        /**
         * 用于调试打印, 不保证线程安全
         */
        @Override
        public void printInfo() {
            int    r      = state >>> 16;
            int    w      = state & ((1 << 16) - 1);
            Thread thread = getExclusiveOwnerThread();

            System.err.println("state = " + state);
            System.err.println("读锁数: " + r);
            System.err.println("写锁数: " + w);
            System.err.println("exclusiveOwnerThread = " + (thread != null ? thread.getName() : null));

            Node cur = firstWaiter;
            while (cur != null) {
                System.err.println(cur.toString(false));
                cur = cur.nextWaiter;
            }
        }
    }

    // 重要函数一 ========================================================================================================

    /**
     * 用于调试打印, 不保证线程安全
     */
    public void printInfo() {
        int    r      = state >>> 16;
        int    w      = state & ((1 << 16) - 1);
        Thread thread = getExclusiveOwnerThread();

        System.err.println("state = " + state);
        System.err.println("读锁数: " + r);
        System.err.println("写锁数: " + w);
        System.err.println("exclusiveOwnerThread = " + (thread != null ? thread.getName() : null));

        Node cur = queue.head;
        while (cur != null) {
            System.err.println(cur.toString(true));
            cur = cur.next;
        }
    }

    final int fullyRelease(Node node) {
        boolean failed = true;
        try {
            int savedState = getState();
            // 调用独占模式 release() 模板方法
            if (release(savedState)) {
                failed = false;
                return savedState;
            } else {
                throw new IllegalMonitorStateException();
            }
        } finally {
            if (failed) node.waitStatus = Node.CANCELLED; // 为响应中断式的抢锁而服务
        }
    }

    @SuppressWarnings("all")
    final boolean isOnSyncQueue(Node node) {
        // condition queue 不会使用 prev、next 属性, 而是用 nextWaiter
        if (node.waitStatus == Node.CONDITION || node.prev == null) {
            return false;
        }
        if (node.next != null) { // If has successor, it must be on queue
            return true;
        }
        /*
         * node.prev can be non-null, but not yet on queue because
         * the CAS to place it on queue can fail. So we have to
         * traverse from tail to make sure it actually made it.  It
         * will always be near the tail in calls to this method, and
         * unless the CAS failed (which is unlikely), it will be
         * there, so we hardly ever traverse much.
         */
        return findNodeFromTail(node);
    }

    private boolean findNodeFromTail(Node node) {
        // 从后向前遍历
        // 查看 sync queue 中是否存在 node
        Node t = queue.tail;
        for (; ; ) {
            if (t == node) return true;
            if (t == null) return false;
            t = t.prev;
        }
    }

    // 独占模式模板方法 + 抽象方法 ==========================================================================================

    protected boolean tryAcquire(int arg) {
        throw new UnsupportedOperationException();
    }

    protected boolean tryRelease(int arg) {
        throw new UnsupportedOperationException();
    }

    public final void acquire(int arg) {
        if (!tryAcquire(arg) && acquireQueued(queue.addWaiter(Node.EXCLUSIVE), arg)) {
            selfInterrupt();
        }
    }

    public final void acquireInterruptibly(int arg) throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException(); // 有中断, 抛异常
        if (!tryAcquire(arg)) doAcquireInterruptibly(arg);
    }

    public final boolean tryAcquireNanos(int arg, long nanosTimeout) throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException(); // 有中断, 抛异常
        return tryAcquire(arg) || doAcquireNanos(arg, nanosTimeout);
    }

    public final boolean release(int arg) {
        if (tryRelease(arg)) {
            Node h = queue.head;
            if (h != null && h.waitStatus != 0) unparkSuccessor(h);
            return true;
        }
        return false;
    }

    // 独占模式模板方法核心实现 =============================================================================================

    // 独占模式下
    // (1) shouldParkAfterFailedAcquire()
    // (1) 末尾节点的 waitStatus = 0, 它前面节点的 waitStatus = -1
    // (2) unparkSuccessor()
    // (2) 头节点的 waitStatus = -1, 当头节点线程唤醒下一个节点的线程前, 会先设置自己的 waitStatus = 0 然后唤醒下一个节点 
    // (3) setHead()
    // (3) 当下一个节点被唤醒后, 它正在执行 acquireQueued(), 它的前驱节点是头节点所以可以尝试获取锁, 获取锁成功后会把自己设置为头节点, 并返回是否被中断过

    final boolean acquireQueued(final Node node, int arg) {
        boolean failed = true;
        try {
            boolean interrupted = false;
            for (; ; ) {
                Node p = node.predecessor();

                // 只有前驱节点是头节点的才能尝试获取锁, "成功获得锁的线程" 只有一个
                if (p == queue.head && tryAcquire(arg)) {
                    queue.setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return interrupted;
                }

                // 调用 park() 函数来阻塞线程, 线程被唤醒有两种情况: unpark() OR 中断
                if (shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt()) {
                    interrupted = true; // 发生中断时, 不会抛出 InterruptedException 异常
                }

                // 假设线程 A 已经获取到锁, 线程 B 是第一个进入 sync queue 的 Node
                // 线程 B shouldParkAfterFailedAcquire() 执行完成, 已经将前驱节点的 waitStatus 设置为 SIGNAL
                // 线程 B 还没来得及执行 parkAndCheckInterrupt() -> LockSupport.park(B), CPU 时间片就耗尽了
                // 恰好此刻已经获取到锁的线程 A 调用 release() 释放锁, 最终会调用 LockSupport.unpark(B)
                // 对于处于 Runnable 的线程 B 来说: 线程 A 调用 unpark(B), 自己获得 CPU 时间片后又调用 park(B), 那么 park(B) 将不会阻塞
                // 建议阅读 LockSupport.unpark(Thread thread) 注释, 并运行 Test1.test3() 测试代码
            }
        } finally {
            if (failed) cancelAcquire(node); // 为响应中断式的抢锁而服务
        }
    }

    private void doAcquireInterruptibly(int arg) throws InterruptedException {
        final Node node   = queue.addWaiter(Node.EXCLUSIVE); // 尾节点(独占)
        boolean    failed = true;
        try {
            for (; ; ) {
                final Node p = node.predecessor();

                // 只有前驱节点是头节点的才能尝试获取锁, "成功获得锁的线程" 只有一个
                if (p == queue.head && tryAcquire(arg)) {
                    queue.setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return;
                }

                // 调用 park() 函数来阻塞线程, 线程被唤醒有两种情况: unpark() OR 中断
                if (shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt()) {
                    throw new InterruptedException(); // 发生中断时, 抛出 InterruptedException 异常
                }
            }
        } finally {
            if (failed) cancelAcquire(node); // 为响应中断式的抢锁而服务
        }
    }

    private boolean doAcquireNanos(int arg, long nanosTimeout) throws InterruptedException {
        if (nanosTimeout <= 0L) return false;
        final long deadline = System.nanoTime() + nanosTimeout; // 阻塞终止的绝对时间

        final Node node   = queue.addWaiter(Node.EXCLUSIVE); // 尾节点(独占)
        boolean    failed = true;
        try {
            for (; ; ) {
                final Node p = node.predecessor();

                // 只有前驱节点是头节点的才能尝试获取锁, "成功获得锁的线程" 只有一个
                if (p == queue.head && tryAcquire(arg)) {
                    queue.setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return true;
                }

                // nanosTimeout = 被唤醒后, 还需要阻塞的相对时间
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L) return false; // nanosTimeout 超时返回

                // 调用 parkNanos() 函数来阻塞线程, 线程被唤醒有三种情况: unpark() OR 中断 OR nanosTimeout 超时返回
                if (shouldParkAfterFailedAcquire(p, node) && nanosTimeout > spinForTimeoutThreshold) {
                    LockSupport.parkNanos(this, nanosTimeout);
                }

                if (Thread.interrupted()) throw new InterruptedException(); // 发生中断时, 抛出 InterruptedException 异常
            }
        } finally {
            if (failed) cancelAcquire(node); // 为响应中断式的抢锁而服务
        }
    }

    // 末尾节点的 waitStatus = 0, 它前面节点的 waitStatus = -1
    // 头节点的 waitStatus = -1, 当头节点线程唤醒下一个节点的线程前, 会将设置自己的 waitStatus = 0 然后唤醒下一个节点
    private void unparkSuccessor(Node node) {
        /*
         * If status is negative (i.e., possibly needing signal) try
         * to clear in anticipation of signalling.  It is OK if this
         * fails or if status is changed by waiting thread.
         */
        // 头节点既是虚拟头节点, 又是成功获取到锁的节点
        int ws = node.waitStatus;
        // 独占模式下, 获得锁的线程只有一个, 获得锁的线程去释放锁, 不存在竞争
        if (ws < 0) Queue.compareAndSetWaitStatus(node, ws, 0);

        /*
         * Thread to unpark is held in successor, which is normally
         * just the next node.  But if cancelled or apparently null,
         * traverse backwards from tail to find the actual
         * non-cancelled successor.
         * 唤醒后继节点的线程
         * 后继是 null 或者取消状态, 从 tail 向前遍历, 找一个距离 head 最近的正常的节点
         */
        Node s = node.next;
        if (s == null || s.waitStatus > 0) {
            s = null;
            // 逆序向前找 waitStatus <= 0 的节点
            for (Node t = queue.tail; t != null && t != node; t = t.prev) {
                if (t.waitStatus <= 0) s = t;
            }
        }
        if (s != null) LockSupport.unpark(s.thread); // 唤醒它
    }

    // 共享模式模板方法 + 抽象方法 ==========================================================================================

    // 返回值 < 0 代表: 当前线程获取共享锁失败
    // 返回值 > 0 代表: 当前线程获取共享锁成功, 且下一个节点为共享节点时, 一定会共享传播
    // 返回值 = 0 代表: 当前线程获取共享锁成功, 且下一个节点为共享节点时, 需要 h.waitStatus < 0 才会共享传播
    protected int tryAcquireShared(int arg) {
        throw new UnsupportedOperationException();
    }

    protected boolean tryReleaseShared(int arg) {
        throw new UnsupportedOperationException();
    }

    protected boolean isHeldExclusively() {
        throw new UnsupportedOperationException();
    }

    public final void acquireShared(int arg) {
        if (tryAcquireShared(arg) < 0) doAcquireShared(arg);
    }

    public final void acquireSharedInterruptibly(int arg) throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException(); // 有中断, 抛异常
        if (tryAcquireShared(arg) < 0) doAcquireSharedInterruptibly(arg);
    }

    public final boolean tryAcquireSharedNanos(int arg, long nanosTimeout) throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException(); // 有中断, 抛异常
        return tryAcquireShared(arg) >= 0 || doAcquireSharedNanos(arg, nanosTimeout);
    }

    public final boolean releaseShared(int arg) {
        if (tryReleaseShared(arg)) {
            doReleaseShared();
            return true;
        }
        return false;
    }

    // 共享模式模板方法核心实现 =============================================================================================

    // 共享模式下
    // (1) shouldParkAfterFailedAcquire()
    // (1) 末尾节点的 waitStatus = 0, 它前面节点的 waitStatus = -1
    // (2) doReleaseShared() -> unparkSuccessor()
    // (2) 头节点的 waitStatus = -1, 当头节点线程唤醒下一个节点的线程前, 会先设置自己的 waitStatus = 0 然后唤醒下一个节点 
    // (3) setHeadAndPropagate() -> setHead() -> doReleaseShared()
    // (3) 当下一个节点被唤醒后, 它正在执行 doAcquireShared(), 它的前驱节点是头节点所以可以尝试获取锁, 获取锁成功后会把自己设置为头节点, 并可能唤醒下一个节点

    private void doAcquireShared(int arg) {
        final Node node   = queue.addWaiter(Node.SHARED); // 尾节点(共享)
        boolean    failed = true;
        try {
            boolean interrupted = false;
            for (; ; ) {
                final Node p = node.predecessor();

                // 只有前驱节点是头节点的才能尝试获取锁, "成功获得锁的线程" 只有一个
                if (p == queue.head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r); // 共享传播
                        p.next = null; // help GC
                        if (interrupted) selfInterrupt();
                        failed = false;
                        return;
                    }
                }

                // 调用 park() 函数来阻塞线程, 线程被唤醒有两种情况: unpark() OR 中断
                if (shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt()) {
                    interrupted = true; // 发生中断时, 不会抛出 InterruptedException 异常
                }
            }
        } finally {
            if (failed) cancelAcquire(node); // 为响应中断式的抢锁而服务
        }
    }

    private void doAcquireSharedInterruptibly(int arg) throws InterruptedException {
        final Node node   = queue.addWaiter(Node.SHARED); // 尾节点(共享)
        boolean    failed = true;
        try {
            for (; ; ) {
                final Node p = node.predecessor();

                // 只有前驱节点是头节点的才能尝试获取锁, "成功获得锁的线程" 只有一个
                if (p == queue.head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r); // 共享传播
                        p.next = null; // help GC
                        failed = false;
                        return;
                    }
                }

                // 调用 park() 函数来阻塞线程, 线程被唤醒有两种情况: unpark() OR 中断
                if (shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt()) {
                    throw new InterruptedException(); // 发生中断时, 抛出 InterruptedException 异常
                }
            }
        } finally {
            if (failed) cancelAcquire(node); // 为响应中断式的抢锁而服务
        }
    }

    private boolean doAcquireSharedNanos(int arg, long nanosTimeout) throws InterruptedException {
        if (nanosTimeout <= 0L) return false;
        final long deadline = System.nanoTime() + nanosTimeout; // 阻塞终止的绝对时间

        final Node node   = queue.addWaiter(Node.SHARED);  // 尾节点(共享)
        boolean    failed = true;
        try {
            for (; ; ) {
                final Node p = node.predecessor();

                // 只有前驱节点是头节点的才能尝试获取锁, "成功获得锁的线程" 只有一个
                if (p == queue.head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r); // 共享传播
                        p.next = null; // help GC
                        failed = false;
                        return true;
                    }
                }

                // nanosTimeout = 被唤醒后, 还需要阻塞的相对时间
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L) return false; // nanosTimeout 超时返回

                // 调用 parkNanos() 函数来阻塞线程, 线程被唤醒有三种情况: unpark() OR 中断 OR nanosTimeout 超时返回
                if (shouldParkAfterFailedAcquire(p, node) && nanosTimeout > spinForTimeoutThreshold) {
                    LockSupport.parkNanos(this, nanosTimeout);
                }

                if (Thread.interrupted()) throw new InterruptedException(); // 发生中断时, 抛出 InterruptedException 异常
            }
        } finally {
            if (failed) cancelAcquire(node); // 为响应中断式的抢锁而服务
        }
    }

    // 该方法有两处调用
    // 1、setHeadAndPropagate(Node node, int propagate) 当线程成功获取到共享锁, 设置自己为头节点后调用(调用此方法的 node 一定是头节点)
    // 2、releaseShared() 当线程成功释放共享锁的时候调用(调用此方法的 node 之前肯定是头节点, 但现在头节点可能不是它了)
    // 该方法会被同一个节点调用两次: 线程 A 获取到共享锁, 设置自己为头节点后调用; 线程 A 释放共享锁时调用
    // 当线程 A 第二次调用时, 当前的头节点很可能已经易主了(也就是说, 当前 sync queue 的头节点已经不是线程 A 所在的节点了)
    /**
     * @see #doAcquireShared(int)
     * @see #setHeadAndPropagate(Node, int)
     */
    private void doReleaseShared() {
        /*
         * Ensure that a release propagates, even if there are other
         * in-progress acquires/releases.  This proceeds in the usual
         * way of trying to unparkSuccessor of head if it needs
         * signal. But if it does not, status is set to PROPAGATE to
         * ensure that upon release, propagation continues.
         * Additionally, we must loop in case a new node is added
         * while we are doing this. Also, unlike other uses of
         * unparkSuccessor, we need to know if CAS to reset status
         * fails, if so rechecking.
         */
        for (; ; ) {
            // 头节点既是虚拟头节点, 又是成功获取到锁的节点
            // 假设 A 已经拿到了共享锁 head(A) -> B -> C, doReleaseShared[A] 过程中 h = A, 唤醒后继节点 B
            // 节点 B 被唤醒后也获得了共享锁 head(B) -> C, doReleaseShared[B] 过程中 h = B, 唤醒后继节点 C
            // 此时 doReleaseShared[A] 过程中 h = B, 也就是说多个线程拿到的 h 可能是一样的, 即 A B 两个线程同时唤醒一个节点 C
            Node h = queue.head;

            // 能进入这个 if 说明队列至少有两个节点
            if (h != null && h != queue.tail) {
                int ws = h.waitStatus;

                // ws = -1
                if (ws == Node.SIGNAL) {
                    // CAS(h.waitStatus, SIGNAL, 0)
                    if (!Queue.compareAndSetWaitStatus(h, Node.SIGNAL, 0)) {
                        continue;            // loop to recheck cases
                    }
                    unparkSuccessor(h);
                }

                // ws = 0
                // 假如 sync queue 为 head(-1) -> node1(t1 & -1) -> node2(t2 & 0)
                // head 所在的线程释放信号量, 进而唤醒线程 t1
                // 线程 t1 被唤醒后尝试获取信号量得到 0, 还没来得及 setHeadAndPropagate(node, 0)
                // 此时 sync queue 为 head(0) -> node1(t1 & -1) -> node2(t2 & 0)
                // 如果此时有线程释放信号量而调用 doReleaseShared(), ws 就会读到 0, 所以不能唤醒任何节点
                // 这里必须要 CAS 设置 h.waitStatus = PROPAGATE(-3) 来告诉 node1, 在 node1.setHeadAndPropagate() 时, 需要共享传播
                // 完成 "别的调用了 doReleaseShared() 的线程" 因为 "node1 没有及时设置自己为头" 而导致 "不能唤醒任何节点" 的任务
                else if (ws == 0 && !Queue.compareAndSetWaitStatus(h, 0, Node.PROPAGATE)) {
                    continue;                // loop on failed CAS
                }
            }

            // 只有在当前 head 没有易主时才会退出, 否则继续循环

            // 约定: X 调用 doReleaseShared() 写做 doReleaseShared[X]
            // 假设 A 已经拿到了共享锁 head(A) -> B -> C -> D, doReleaseShared[A] 唤醒后继节点 B
            // 节点 B 被唤醒后也获得了共享锁 head(B) -> C -> D, doReleaseShared[B] 唤醒后继节点 C
            // 但是别忘了: 在 doReleaseShared[B] 的时候 doReleaseShared[A] 还没运行结束呢
            // 当 doReleaseShared[A] 运行到 if(h == head) 时, 发现头节点现在已经变了, 它将继续回到 for 循环中
            // 与此同时, doReleaseShared[B] 也没闲着, 它在执行过程中也进入到了 for 循环中 ...

            // 由此可见, 我们这里形成了一个 doReleaseShared[X] 的调用风暴
            // 大量的线程在同时执行 doReleaseShared(), 这极大地加速了唤醒后继节点的速度, 提升了效率
            // 同时该方法内部的 CAS 操作又保证了多个线程同时唤醒一个节点时, 只有一个线程能操作成功

            // 那如果这里 doReleaseShared[A] 唤醒节点 B, 但节点 B 还没来得及将自己设置为头节点时, doReleaseShared[A] 方法不就退出了吗
            // 是的, 但即使这样也没有关系, 因为它已经成功唤醒了线程 B
            // 即使 doReleaseShared[A] 退出了, 当 B 线程成为新的头节点时, doReleaseShared[B] 就开始执行了, 它也会负责唤醒后继节点的
            // 即使变成这种每个节点只唤醒自己后继节点的模式, 从功能上讲, 最终也可以实现 "唤醒所有等待共享锁的节点" 的目的, 只是效率上没有之前的调用风暴快
            if (h == queue.head) {           // loop if head changed
                break;
            }
        }
    }

    // 重要函数二 ========================================================================================================

    static void selfInterrupt() {
        Thread.currentThread().interrupt(); // 设置中断标志位
    }

    private final boolean parkAndCheckInterrupt() {
        LockSupport.park(this); // unpark() OR 中断
        return Thread.interrupted();   // 会清除中断状态
    }

    private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
        // pred 为前驱节点, ws 为前驱节点的状态
        int ws = pred.waitStatus;
        if (ws == Node.SIGNAL)
            /*
             * This node has already set status asking a release
             * to signal it, so it can safely park.
             * node 拿锁失败, 前驱节点的状态是 SIGNAL, node 节点可以放心的阻塞, 因为下次会被唤醒
             */
            return true;
        if (ws > 0) {
            /*
             * Predecessor was cancelled. Skip over predecessors and
             * indicate retry.
             * pred 节点被取消了, 跳过 pred, 给 node 链接一个正常的前驱(状态 <= 0)
             * 最终返回 false(再给一次自旋的机会)
             */
            // 假设 a 节点入队, 往前找的途中, b 节点入队, 也往前找, a b 之间不存在竞争关系
            // 因为此时 a.waitStatus = 0, b 最多是往前排到 a 的后面
            do {
                node.prev = pred = pred.prev;
            } while (pred.waitStatus > 0);
            pred.next = node;
        } else {
            /*
             * waitStatus must be 0 or PROPAGATE.  Indicate that we
             * need a signal, but don't park yet.  Caller will need to
             * retry to make sure it cannot acquire before parking.
             * 此时将 node 前驱节点的 waitStatus 设置为 SIGNAL
             * 最终返回 false(再给一次自旋的机会)
             */
            // ws == 0 || ws == PROPAGATE
            // CAS 设置, 因为前驱节点的 waitStatus 有可能变成 CANCELLED
            Queue.compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
        }
        return false;
    }

    /**
     * <a href="https://www.cnblogs.com/micrari/p/6937995.html">PROPAGATE 状态存在的意义</a>
     * <pre> {@code
     * public class TestSemaphore {
     *
     *    private static Semaphore sem = new Semaphore(0);
     *
     *    private static class Thread1 extends Thread {
     *        @Override
     *        public void run() {
     *            sem.acquireUninterruptibly(); // 获取信号量
     *        }
     *    }
     *
     *    private static class Thread2 extends Thread {
     *        @Override
     *        public void run() {
     *            sem.release();                // 释放信号量
     *        }
     *    }
     *
     *    public static void main(String[] args) throws InterruptedException {
     *        for (int i = 0; i < 10000000; i++) {
     *            Thread t1 = new Thread1();    // 获取信号量
     *            Thread t2 = new Thread1();    // 获取信号量
     *            Thread t3 = new Thread2();    // 释放信号量
     *            Thread t4 = new Thread2();    // 释放信号量
     *            t1.start();
     *            t2.start();
     *            t3.start();
     *            t4.start();
     *            t1.join();
     *            t2.join();
     *            t3.join();
     *            t4.join();
     *            System.out.println(i);
     *        }
     *    }
     * }
     * }</pre>
     *
     * <p>
     * 共享模式下, 一个被 park() 的线程, 不考虑中断和前驱节点取消的情况, 有两种情况可以被 unpark()<br>
     * 第一种: 其它线程释放信号量, 调用 unparkSuccessor();<br>
     * 第二种: 其它线程获取共享锁时, 通过共享传播来唤醒后继节点<br>
     * </p>
     *
     * <p>
     * 假设某次 sync queue 为 head(-1) -> node1(t1 & -1) -> node2(t2 & 0)<br>
     * 信号量释放的顺序为 t3 先释放, t4 后释放<br>
     * 时刻一: t3 调用 {@link #releaseShared(int)} -> {@link #doReleaseShared()} -> {@link #unparkSuccessor(Node)},
     * 唤醒下一个节点 node1(t1 & -1) 前, 会先将当前头节点的 waitStatus 置 0, 即 head(-1 -> 0)<br>
     * 时刻二: t1 由于 t3 释放信号量而被唤醒, t1 在 {@link #doAcquireShared(int)} 中调用 Semaphore.NonfairSync 的 tryAcquireShared() 返回值为 0,
     * 此时 sync queue 为 head(0) -> node1(t1 & -1) -> node2(t2 & 0)<br>
     * 时刻三: t4 调用 {@link #releaseShared(int)} -> {@link #doReleaseShared()},
     * 此时 h.waitStatus = 0 不满足条件, 因此不会调用 {@link #unparkSuccessor(Node)}, 但必须 CAS 设置 h.waitStatus = PROPAGATE(-3)<br>
     * 时刻四: t1 在 {@link #doAcquireShared(int)} 获取信号量成功, 调用 setHeadAndPropagate() 时, 因为时刻三的设置,
     * 会读到 h.waitStatus < 0, 从而可以接下来调用 {@link #doReleaseShared()} 唤醒 t2<br>
     * </p>
     *
     * <p>
     * 假如时刻三读到 h.waitStatus = 0, 不能唤醒下一个节点<br>
     * 且不进行 CAS 设置 h.waitStatus = PROPAGATE(-3)<br>
     * 那么在时刻四 t1 在 {@link #doAcquireShared(int)} 获取信号量 0 成功, 调用 setHeadAndPropagate() 时<br>
     * 会因为 propagate = 0 而不能共享传播, 导致线程 t2 无法被唤醒
     * </p>
     *
     * @see #doAcquireShared(int)
     * @see #doReleaseShared()
     */
    private void setHeadAndPropagate(Node node, int propagate) {
        // node 为虚拟头节点的下一个节点, 传播 propagate >= 0
        // 初始时 h 为当前队列的头节点, 即 node 的前驱节点
        // 因为 node 由 h 唤醒, 所以 h.waitStatus = 0, h(0) -> node(-1)

        // 假设当前节点为 node1, 此时 sync queue 为 head(-3 | 0) -> node1(t1 & -1) -> node2(t2 & 0)
        // 如果 propagate > 0 就会共享传播
        // 如果 propagate = 0, head.waitStatus = -3 代表 node1 被唤醒但还没来得及 setHeadAndPropagate() 期间
        // 有别的线程调用 doReleaseShared(), 而由于此时 h.waitStatus = 0 所以不能唤醒任何节点
        // 因此通过将 h.waitStatus = -3 来告诉 node1, 在 node1.setHeadAndPropagate() 时, 需要共享传播
        // 完成 "别的调用了 doReleaseShared() 的线程" 因为 "node1 没有及时设置自己为头" 而导致 "不能唤醒任何节点" 的任务
        Node h = queue.head; // Record old head for check below
        queue.setHead(node);
        /*
         * Try to signal next queued node if:
         *   Propagation was indicated by caller,
         *     or was recorded (as h.waitStatus either before
         *     or after setHead) by a previous operation
         *     (note: this uses sign-check of waitStatus because
         *      PROPAGATE status may transition to SIGNAL.)
         * and
         *   The next node is waiting in shared mode,
         *     or we don't know, because it appears null
         *
         * The conservatism in both of these checks may cause
         * unnecessary wake-ups, but only when there are multiple
         * racing acquires/releases, so most need signals now or soon
         * anyway.
         */
        // 注意: 这里不能只根据 propagate= tryAcquireShared() 的返回值来判断是否应该共享传播
        // 注意: propagate > 0 且下一个节点为共享节点时, 一定会共享传播
        // 注意: propagate = 0 且下一个节点为共享节点时, 需要 h.waitStatus < 0 才会共享传播
        if (propagate > 0 || h == null || h.waitStatus < 0 || (h = queue.head) == null || h.waitStatus < 0) {
            Node s = node.next;
            if (s == null || s.isShared()) doReleaseShared(); // 共享传播(唤醒下一个共享节点)
        }
    }

    private void cancelAcquire(Node node) {
        // Ignore if node doesn't exist
        if (node == null) return;

        node.thread = null;

        // Skip cancelled predecessors
        Node pred = node.prev;
        while (pred.waitStatus > 0) {
            node.prev = pred = pred.prev;
        }

        // predNext is the apparent node to unsplice. CASes below will
        // fail if not, in which case, we lost race vs another cancel
        // or signal, so no further action is necessary.
        Node predNext = pred.next;

        // Can use unconditional write instead of CAS here.
        // After this atomic step, other Nodes can skip past us.
        // Before, we are free of interference from other threads.
        node.waitStatus = Node.CANCELLED;

        // If we are the tail, remove ourselves.
        if (node == queue.tail && queue.compareAndSetTail(node, pred)) {
            Queue.compareAndSetNext(pred, predNext, null);
        } else {
            // If successor needs signal, try to set pred's next-link
            // so it will get one. Otherwise wake it up to propagate.
            int ws;
            if (pred != queue.head &&
                    ((ws = pred.waitStatus) == Node.SIGNAL ||
                            (ws <= 0 && Queue.compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) &&
                    pred.thread != null) {
                Node next = node.next;
                if (next != null && next.waitStatus <= 0) Queue.compareAndSetNext(pred, predNext, next);
            } else {
                // pred == head
                unparkSuccessor(node);
            }

            node.next = node; // help GC
        }
    }

    // 重要函数三 ========================================================================================================

    protected final int getState() {
        return state;
    }

    protected final void setState(int newState) {
        state = newState;
    }

    protected final boolean compareAndSetState(int expect, int update) {
        return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
    }

    // 如果在当前线程之前有一个排队线程, 则为 true
    // 如果当前线程位于队列的头部或队列为空, 则为 false
    /**
     * 有排队的前置任务
     */
    public final boolean hasQueuedPredecessors() {
        // The correctness of this depends on head being initialized
        // before tail and on head.next being accurate if the current
        // thread is first in queue.
        Node t = queue.tail; // Read fields in reverse initialization order
        Node h = queue.head;
        Node s;
        return h != t && ((s = h.next) == null || s.thread != Thread.currentThread());
    }

    // 在 ReentrantReadWriteLock 中 NonfairSync 非公平锁中使用
    // 如果等待队列中 !head.next.isShared(), 即接下来要被唤醒的是写线程, 返回 true
    // 那么当前正要获取读锁的线程就要去排队, 这样做是为了避免请求写锁的线程迟迟获取不到写锁
    /**
     * 显然第一个排队是独占的
     */
    protected final boolean apparentlyFirstQueuedIsExclusive() {
        Node h, s;
        return (h = queue.head) != null &&
                (s = h.next) != null &&
                !s.isShared() &&
                s.thread != null;
    }

    // 其它函数 ==========================================================================================================

    public final boolean hasQueuedThreads() {
        return queue.head != queue.tail;
    }

    public final boolean isQueued(Thread thread) {
        if (thread == null) throw new NullPointerException();
        for (Node p = queue.tail; p != null; p = p.prev) {
            if (p.thread == thread) return true;
        }
        return false;
    }

    public final int getQueueLength() {
        int n = 0;
        for (Node p = queue.tail; p != null; p = p.prev) {
            if (p.thread != null) ++n;
        }
        return n;
    }

    public final Collection<Thread> getQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<>();
        for (Node p = queue.tail; p != null; p = p.prev) {
            Thread t = p.thread;
            if (t != null) list.add(t);
        }
        return list;
    }

    public final Collection<Thread> getExclusiveQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<>();
        for (Node p = queue.tail; p != null; p = p.prev) {
            if (!p.isShared()) {
                Thread t = p.thread;
                if (t != null) list.add(t);
            }
        }
        return list;
    }

    public final Collection<Thread> getSharedQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = queue.tail; p != null; p = p.prev) {
            if (p.isShared()) {
                Thread t = p.thread;
                if (t != null) list.add(t);
            }
        }
        return list;
    }

    public final boolean hasWaiters(ConditionObject condition) {
        if (!owns(condition)) throw new IllegalArgumentException("Not owner");
        return condition.hasWaiters();
    }

    public final int getWaitQueueLength(ConditionObject condition) {
        if (!owns(condition)) throw new IllegalArgumentException("Not owner");
        return condition.getWaitQueueLength();
    }

    public final Collection<Thread> getWaitingThreads(ConditionObject condition) {
        if (!owns(condition)) throw new IllegalArgumentException("Not owner");
        return condition.getWaitingThreads();
    }

    public final boolean owns(ConditionObject condition) {
        return condition.isOwnedBy(this);
    }
}
