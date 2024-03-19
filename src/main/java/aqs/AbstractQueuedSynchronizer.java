package aqs;

import aqs.Queue.Node;

import java.util.concurrent.locks.LockSupport;

public abstract class AbstractQueuedSynchronizer extends AbstractOwnableSynchronizer {

    // 1000 ns = 1 ms
    // 1000 ns 的自旋比 1000 ns 的时间阻塞更快, 粗略的估计, 足以在非常短的超时时间内提高响应速度
    // 也就是说, 当线程被中断唤醒, 如果剩余阻塞时间 <= 1000 ns, 那么当前线程将自旋而不是调用 parkNanos(nanosTimeout)
    static final long spinForTimeoutThreshold = 1000L;

    private final Queue queue = new Queue();

    protected AbstractQueuedSynchronizer() {
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

    // 边缘函数 ==========================================================================================================

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
}
