package aqs;

import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * <p>用于实现 AQS 的 sync queue 和 condition queue<br>
 * <p>注意: 在多线程情况下, 可以把 AQS 想象为一个共享变量, 通过这个变量可以得到 queue 和 node
 */
class Queue {

    // 独占模式下的 sync queue
    // 所有 node 都满足 nextWaiter = EXCLUSIVE

    // 共享模式下的 sync queue
    // 写节点的 nextWaiter = EXCLUSIVE
    // 读节点的 nextWaiter = SHARED
    // 写锁是排它锁、读锁是共享锁、读锁和写锁之间也是排它的、已获取写锁的线程可以获取读锁(支持锁降级)
    // 当写锁被获取: 其它线程获取写锁 OR 读锁, 都会被阻塞, sync queue 同时存在其它线程的写节点和读节点
    // 当读锁被获取: 所有请求读锁的线程大概率会成功, 但公平锁请求读锁可能失败; 获取写锁的所有线程会失败, sync queue 存在所有线程的写节点和其它线程的读节点
    // 总结: sync queue 会存在读节点和写节点, 一般情况下读节点个数 > 写节点个数

    // 当一个节点的 waitStatus = SIGNAL, 就说明它的后继节点已经被挂起了(或者马上就要被挂起了)
    // 因此在当前节点释放锁 OR 放弃获取锁时, 如果它的 waitStatus = SIGNAL, 它还要完成一个额外的操作: 唤醒它的后继节点
    // SIGNAL 这个状态的设置常常不是节点自己给自己设的, 而是后继节点设置的
    static final class Node {
        static final Node SHARED    = new Node(); // 共享锁
        static final Node EXCLUSIVE = null;       // 排它锁

        // 新加入节点的 waitStatus = 0
        // 正常情况下, 它前面节点的 waitStatus = -1
        static final int CANCELLED = 1;  // indicate thread has cancelled
        static final int SIGNAL    = -1; // indicate successor's thread needs unparking
        static final int CONDITION = -2; // indicate thread is waiting on condition
        static final int PROPAGATE = -3; // indicate the next acquireShared should unconditionally propagate

        // =======================================================================

        volatile Node   prev;
        volatile Node   next;
        volatile Thread thread;
        volatile int    waitStatus; // 默认为 0
        Node nextWaiter; // SHARED OR EXCLUSIVE(线程等待的是共享锁 OR 排它锁)

        // =======================================================================

        // Used to establish initial head or SHARED marker
        Node() {
        }

        // Used by addWaiter
        Node(Thread thread, Node mode) {
            this.nextWaiter = mode;
            this.thread = thread;
        }

        // Used by Condition
        Node(Thread thread, int waitStatus) {
            this.waitStatus = waitStatus;
            this.thread = thread;
        }

        // =======================================================================

        // Returns true if node is waiting in shared mode
        final boolean isShared() {
            return nextWaiter == SHARED;
        }

        Node predecessor() throws NullPointerException {
            Node p = prev;
            if (p == null) throw new NullPointerException();
            else return p;
        }

        /**
         * 用于调试打印, 不保证线程安全
         */
        @Override
        public String toString() {
            return String.format(
                    "Node{thread = %s, waitStatus = %d, nextWaiter = %s}",
                    thread != null ? thread.getName() : null,
                    waitStatus,
                    nextWaiter == SHARED ? "SHARED" : "EXCLUSIVE"
            );
        }
    }

    /**
     * 头节点既是虚拟头节点, 又是成功获取到锁的节点
     */
    volatile Node head;
    volatile Node tail;

    public static final  Unsafe unsafe;
    private static final long   headOffset;
    private static final long   tailOffset;
    private static final long   nextOffset;
    private static final long   waitStatusOffset;

    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = (Unsafe) field.get(null);

            headOffset = unsafe.objectFieldOffset(Queue.class.getDeclaredField("head"));
            tailOffset = unsafe.objectFieldOffset(Queue.class.getDeclaredField("tail"));

            nextOffset = unsafe.objectFieldOffset(Node.class.getDeclaredField("next"));
            waitStatusOffset = unsafe.objectFieldOffset(Node.class.getDeclaredField("waitStatus"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    Queue() {
    }

    // ====================================================================================

    void setHead(Node node) {
        head = node;
        head.thread = null;
        head.prev = null;
    }

    // ====================================================================================

    Node addWaiter(Node mode) {
        Node node = new Node(Thread.currentThread(), mode);
        Node prev = tail;

        // 链表不为空
        if (prev != null) {
            node.prev = prev;
            if (compareAndSetTail(prev, node)) {
                prev.next = node;
                return node;
            }
        }

        // 链表为空、或者链表不为空但添加节点失败
        enq(node);
        return node;
    }

    /**
     * 需要注意 step1 和 step2 的顺序
     * <pre> {@code
     * // 当 step1 执行完成, step2 和 step3 还没来得及执行时, 链表将无法遍历到 node
     * Node t = tail;
     * if (compareAndSetTail(t, node)) { // step1
     *     node.prev = t                 // step2
     *     t.next = node;                // step3
     * }
     * }</pre>
     */
    Node enq(Node node) {
        // 自旋 CAS 直到成功为止
        for (; ; ) {
            Node t = tail;
            // 链表为空
            if (t == null) {
                if (compareAndSetHead(new Node())) tail = head; // 设置虚拟头节点
            }
            // 链表不为空
            else {
                node.prev = t;                     // step1 设置 node 节点的上一个节点是 tail
                if (compareAndSetTail(t, node)) {  // step2 设置 tail = node
                    t.next = node;
                    return t;
                }
            }
        }
    }

    // ====================================================================================

    private boolean compareAndSetHead(Node update) {
        return unsafe.compareAndSwapObject(this, headOffset, null, update);
    }

    boolean compareAndSetTail(Node expect, Node update) {
        return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
    }

    static final boolean compareAndSetWaitStatus(Node node, int expect, int update) {
        return unsafe.compareAndSwapInt(node, waitStatusOffset, expect, update);
    }

    static final boolean compareAndSetNext(Node node, Node expect, Node update) {
        return unsafe.compareAndSwapObject(node, nextOffset, expect, update);
    }
}
