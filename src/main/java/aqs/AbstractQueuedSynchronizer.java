package aqs;

import aqs.Queue.Node;
import sun.misc.Unsafe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public abstract class AbstractQueuedSynchronizer extends AbstractOwnableSynchronizer {

    // 1000 ns = 1 ms
    // 1000 ns 的自旋比 1000 ns 的时间阻塞更快, 粗略的估计, 足以在非常短的超时时间内提高响应速度
    // 也就是说, 当线程被中断唤醒, 如果剩余阻塞时间 <= 1000 ns, 那么当前线程将自旋而不是调用 parkNanos(nanosTimeout)
    static final long spinForTimeoutThreshold = 1000L;

    private final Queue queue = new Queue();

    /**
     * 锁没有被占用 0、锁已经被占用 1、锁的重入次数大于 1
     */
    volatile int state;
    private static final long stateOffset;
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

        public ConditionObject() {
        }

        // =============================================================================================================

        @Override
        public void await() throws InterruptedException {

        }

        @Override
        public void awaitUninterruptibly() {
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node); // 将 state 修改为 0, 表示释放了锁

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
            return 0;
        }

        @Override
        public boolean await(long time, TimeUnit unit) throws InterruptedException {
            return false;
        }

        @Override
        public boolean awaitUntil(Date deadline) throws InterruptedException {
            return false;
        }

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

        final boolean transferForSignal(Node node) {
            /*
             * If cannot change waitStatus, the node has been cancelled.
             */
            if (!Queue.compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
                return false;
            }

            /*
             * Splice onto queue and try to set waitStatus of predecessor to
             * indicate that thread is (probably) waiting. If cancelled or
             * attempt to set waitStatus fails, wake up to resync (in which
             * case the waitStatus can be transiently and harmlessly wrong).
             */
            Node p = queue.enq(node); // 将 node 放入 sync queue 中, 返回值为 node 的前驱节点
            int ws = p.waitStatus;
            // 只要前驱节点处于 "取消状态" 或者 "无法将前驱节点的状态修改成 Node.SIGNAL", 那就将 node 所代表的线程唤醒
            if (ws > 0 || !Queue.compareAndSetWaitStatus(p, ws, Node.SIGNAL)) {
                LockSupport.unpark(node.thread);
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

        //  support for instrumentation

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
    }

    // 重要函数一 ========================================================================================================

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
            if (failed) node.waitStatus = Node.CANCELLED;
        }
    }

    final boolean isOnSyncQueue(Node node) {
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

    final boolean acquireQueued(final Node node, int arg) {
        boolean interrupted = false;
        for (; ; ) {
            Node p = node.predecessor();

            // 只有前驱节点是头节点的才能尝试获取锁, "成功获得锁的线程" 只有一个
            if (p == queue.head && tryAcquire(arg)) {
                queue.setHead(node);
                p.next = null; // help GC
                return interrupted;
            }

            // 调用 park() 函数来阻塞线程, 线程被唤醒有两种情况: unpark() OR 中断
            if (shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt()) {
                interrupted = true; // 发生中断时, 不会抛出 InterruptedException 异常
            }
        }
    }

    private void doAcquireInterruptibly(int arg) throws InterruptedException {
        final Node node = queue.addWaiter(Node.EXCLUSIVE); // 尾节点(独占)
        for (; ; ) {
            final Node p = node.predecessor();

            // 只有前驱节点是头节点的才能尝试获取锁, "成功获得锁的线程" 只有一个
            if (p == queue.head && tryAcquire(arg)) {
                queue.setHead(node);
                p.next = null; // help GC
                return;
            }

            // 调用 park() 函数来阻塞线程, 线程被唤醒有两种情况: unpark() OR 中断
            if (shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt()) {
                throw new InterruptedException(); // 发生中断时, 抛出 InterruptedException 异常
            }
        }
    }

    private boolean doAcquireNanos(int arg, long nanosTimeout) throws InterruptedException {
        if (nanosTimeout <= 0L) return false;
        final long deadline = System.nanoTime() + nanosTimeout; // 阻塞终止的绝对时间

        final Node node = queue.addWaiter(Node.EXCLUSIVE); // 尾节点(独占)
        for (; ; ) {
            final Node p = node.predecessor();

            // 只有前驱节点是头节点的才能尝试获取锁, "成功获得锁的线程" 只有一个
            if (p == queue.head && tryAcquire(arg)) {
                queue.setHead(node);
                p.next = null; // help GC
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

    private void doAcquireShared(int arg) {
        final Node node = queue.addWaiter(Node.SHARED); // 尾节点(共享)
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
                    return;
                }
            }

            // 调用 park() 函数来阻塞线程, 线程被唤醒有两种情况: unpark() OR 中断
            if (shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt()) {
                interrupted = true; // 发生中断时, 不会抛出 InterruptedException 异常
            }
        }
    }

    private void doAcquireSharedInterruptibly(int arg) throws InterruptedException {
        final Node node = queue.addWaiter(Node.SHARED); // 尾节点(共享)
        for (; ; ) {
            final Node p = node.predecessor();

            // 只有前驱节点是头节点的才能尝试获取锁, "成功获得锁的线程" 只有一个
            if (p == queue.head) {
                int r = tryAcquireShared(arg);
                if (r >= 0) {
                    setHeadAndPropagate(node, r); // 共享传播
                    p.next = null; // help GC
                    return;
                }
            }

            // 调用 park() 函数来阻塞线程, 线程被唤醒有两种情况: unpark() OR 中断
            if (shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt()) {
                throw new InterruptedException(); // 发生中断时, 抛出 InterruptedException 异常
            }
        }
    }

    private boolean doAcquireSharedNanos(int arg, long nanosTimeout) throws InterruptedException {
        if (nanosTimeout <= 0L) return false;
        final long deadline = System.nanoTime() + nanosTimeout; // 阻塞终止的绝对时间

        final Node node = queue.addWaiter(Node.SHARED);  // 尾节点(共享)
        for (; ; ) {
            final Node p = node.predecessor();

            // 只有前驱节点是头节点的才能尝试获取锁, "成功获得锁的线程" 只有一个
            if (p == queue.head) {
                int r = tryAcquireShared(arg);
                if (r >= 0) {
                    setHeadAndPropagate(node, r); // 共享传播
                    p.next = null; // help GC
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
    }

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
            Node h = queue.head;
            if (h != null && h != queue.tail) {
                int ws = h.waitStatus;
                if (ws == Node.SIGNAL) {
                    if (!Queue.compareAndSetWaitStatus(h, Node.SIGNAL, 0)) {
                        continue;            // loop to recheck cases
                    }
                    unparkSuccessor(h);
                } else if (ws == 0 && !Queue.compareAndSetWaitStatus(h, 0, Node.PROPAGATE)) {
                    continue;                // loop on failed CAS
                }
            }
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
            do {
                node.prev = pred = pred.prev;
            } while (pred.waitStatus > 0);
            pred.next = node;
        } else {
            // ws == 0
            /*
             * waitStatus must be 0 or PROPAGATE.  Indicate that we
             * need a signal, but don't park yet.  Caller will need to
             * retry to make sure it cannot acquire before parking.
             * 此时将 node 前驱节点 waitStatus 设置为 SIGNAL
             * 最终返回 false(再给一次自旋的机会)
             */
            Queue.compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
        }
        return false;
    }

    private void setHeadAndPropagate(Node node, int propagate) {
        // node 为虚拟头节点的下一个节点, 传播 propagate >= 0
        // 初始时 h 为当前队列的头节点, 即 node 的前驱节点
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
        if (propagate > 0 || h == null || h.waitStatus < 0 || (h = queue.head) == null || h.waitStatus < 0) {
            Node s = node.next;
            if (s == null || s.isShared()) doReleaseShared(); // 共享传播(唤醒下一个共享节点)
        }
    }

    // 其它函数 ==========================================================================================================

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
    public final boolean hasQueuedPredecessors() {
        // The correctness of this depends on head being initialized
        // before tail and on head.next being accurate if the current
        // thread is first in queue.
        Node t = queue.tail; // Read fields in reverse initialization order
        Node h = queue.head;
        Node s;
        return h != t && ((s = h.next) == null || s.thread != Thread.currentThread());
    }

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
