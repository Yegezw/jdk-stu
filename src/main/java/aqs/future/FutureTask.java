package aqs.future;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

/**
 * <a href="https://segmentfault.com/a/1190000016542779">预备知识</a>
 * <a href="https://segmentfault.com/a/1190000016572591">深入理解 FutureTask</a>
 */
@SuppressWarnings("all")
public class FutureTask<V> implements RunnableFuture<V>
{

    /**
     * <p>
     * 该任务的 run state 最初为 NEW, 只有在 set、setException、cancel 方法中, run state 才会过渡到 terminal<br>
     * 在完成过程中, state 可能暂时取值为 COMPLETING(正在设置结果) 或 INTERRUPTING(仅在中断运行程序以满足 cancel(true) 时)<br>
     * 从这些中间状态到最终状态的转换使用更便宜的 ordered/lazy 写入, 因为值是唯一的, 不能进一步修改
     * <p>
     * 可能的状态转换<br>
     * 正常执行 NEW -> COMPLETING -> NORMAL<br>
     * 执行异常 NEW -> COMPLETING -> EXCEPTIONAL<br>
     * 任务取消 NEW -> CANCELLED<br>
     * 任务中断 NEW -> INTERRUPTING -> INTERRUPTED
     * <p>
     * 只要 state 不处于 NEW 状态, 就说明任务已经执行完毕<br>
     * 任务的中间状态 COMPLETING、INTERRUPTING 是一个瞬态, 它非常的短暂<br>
     * 并不代表任务正在执行, 而是任务已经执行完了, 正在设置最终的返回结果
     */
    @SuppressWarnings("all")
    private volatile int state;

    private static final int NEW = 0; // 新建

    private static final int COMPLETING  = 1; // 完成中
    private static final int NORMAL      = 2; // 1、正常完成
    private static final int EXCEPTIONAL = 3; // 2、运行异常

    private static final int CANCELLED    = 4; // 3、取消
    private static final int INTERRUPTING = 5; // 中断中
    private static final int INTERRUPTED  = 6; // 4、已中断

    // ------------------------------------------------

    /**
     * 运行后清空
     */
    private          Callable<V> callable;
    /**
     * 从 get() 返回的结果或抛出的异常
     */
    private          Object      outcome; // non-volatile, protected by state reads/writes
    /**
     * 任务执行线程 CASed during run()
     */
    private volatile Thread      runner;
    /**
     * 调用 get() 等待任务执行完毕的线程栈
     */
    private volatile WaitNode    waiters;

    // ------------------------------------------------

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long            stateOffset;
    private static final long            runnerOffset;
    private static final long            waitersOffset;

    static
    {
        try
        {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);

            Class<?> k = FutureTask.class;
            stateOffset   = UNSAFE.objectFieldOffset(k.getDeclaredField("state"));
            runnerOffset  = UNSAFE.objectFieldOffset(k.getDeclaredField("runner"));
            waitersOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("waiters"));
        }
        catch (Exception e)
        {
            throw new Error(e);
        }
    }

    // =================================================================================================================

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Callable}.
     *
     * @param callable the callable task
     * @throws NullPointerException if the callable is null
     */
    public FutureTask(Callable<V> callable)
    {
        if (callable == null)
        {
            throw new NullPointerException();
        }
        this.callable = callable;
        this.state    = NEW;       // ensure visibility of callable
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Runnable}, and arrange that {@code get} will return the
     * given result on successful completion.
     *
     * @param runnable the runnable task
     * @param result   the result to return on successful completion. If
     *                 you don't need a particular result, consider using
     *                 constructions of the form:
     *                 {@code Future<?> f = new FutureTask<Void>(runnable, null)}
     * @throws NullPointerException if the runnable is null
     */
    public FutureTask(Runnable runnable, V result)
    {
        this.callable = Executors.callable(runnable, result);
        this.state    = NEW;       // ensure visibility of callable
    }

    // ------------------------------------------------

    /**
     * 判断任务是否被取消了
     * <br>
     * 如果一个任务在正常执行完成之前被 cancel 掉了, 则返回 true
     */
    public boolean isCancelled()
    {
        return state >= CANCELLED;
    }

    /**
     * 如果一个任务已经结束, 则返回 true<br>
     * 1、任务已经被取消<br>
     * 2、任务抛出了异常<br>
     * 3、任务正常执行完毕
     */
    public boolean isDone()
    {
        return state != NEW;
    }

    // ------------------------------------------------

    /**
     * Sets the result of this future to the given value unless
     * this future has already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon successful completion of the computation.
     *
     * @param v the value
     */
    protected void set(V v)
    {
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING))
        {
            outcome = v;
            UNSAFE.putOrderedInt(this, stateOffset, NORMAL); // final state
            finishCompletion(); // waiters -> done() -> callable
        }
    }

    /**
     * Causes this future to report an {@link ExecutionException}
     * with the given throwable as its cause, unless this future has
     * already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon failure of the computation.
     *
     * @param t the cause of failure
     */
    protected void setException(Throwable t)
    {
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING))
        {
            outcome = t;
            UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL); // final state
            finishCompletion(); // waiters -> done() -> callable
        }
    }

    /**
     * <p>
     * 尝试取消一个任务的执行, 返回取消操作是否成功
     * <p>
     * 有以下情况时 cancel 一定是失败的<br>
     * 1、任务已经执行完成了<br>
     * 2、任务已经被取消过了
     * <p>
     * 其它情况下 cancel 将返回 true<br>
     * cancel 返回 true 并不代表任务真的就是被取消了, 这取决于 cancel 时任务所处的状态<br>
     * 1、如果 cancel 时任务还未开始运行, 则随后任务就不会被执行<br>
     * 2、如果 cancel 时任务已经在运行了, 则这时就需要看 mayInterruptIfRunning 参数了
     * <p>
     * 1、mayInterruptIfRunning = true<br>
     * 则当前在执行的任务会被中断, NEW -> INTERRUPTING -> INTERRUPTED<br>
     * 2、mayInterruptIfRunning = false<br>
     * 则可以允许正在执行的任务继续运行, 直到它执行完成, NEW -> CANCELLED
     */
    public boolean cancel(boolean mayInterruptIfRunning)
    {
        if (!(state == NEW &&
                UNSAFE.compareAndSwapInt(this, stateOffset, NEW,
                        mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
        {
            return false;
        }
        try
        {
            // in case call to interrupt throws exception
            if (mayInterruptIfRunning)
            {
                try
                {
                    Thread t = runner;
                    if (t != null)
                    {
                        t.interrupt(); // 中断线程
                    }
                }
                finally
                {
                    // final state
                    UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);
                }
            }
        }
        finally
        {
            finishCompletion(); // waiters -> done() -> callable
        }
        return true;
    }

    // ------------------------------------------------

    /**
     * Returns result or throws exception for completed task.
     *
     * @param s completed state value
     */
    @SuppressWarnings("unchecked")
    private V report(int s) throws ExecutionException
    {
        Object x = outcome;

        // NORMAL
        if (s == NORMAL)
        {
            return (V) x;
        }

        // CANCELLED、INTERRUPTING、INTERRUPTED
        if (s >= CANCELLED)
        {
            throw new CancellationException();
        }

        // EXCEPTIONAL
        throw new ExecutionException((Throwable) x);
    }

    /**
     * 获取执行结果, 如果任务还在执行中, 就阻塞等待
     */
    public V get() throws InterruptedException, ExecutionException
    {
        int s = state;
        if (s <= COMPLETING)
        {
            s = awaitDone(false, 0L);
        }
        return report(s);
    }

    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
    {
        if (unit == null)
        {
            throw new NullPointerException();
        }
        int s = state;
        if (s <= COMPLETING &&
                (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING)
        {
            throw new TimeoutException();
        }
        return report(s);
    }

    /**
     * Protected method invoked when this task transitions to state
     * {@code isDone} (whether normally or via cancellation). The
     * default implementation does nothing.  Subclasses may override
     * this method to invoke completion callbacks or perform
     * bookkeeping. Note that you can query status inside the
     * implementation of this method to determine whether this task
     * has been cancelled.
     */
    protected void done()
    {
    }

    // =================================================================================================================

    public void run()
    {
        // 检查 state = NEW, CAS runner = Thread.currentThread()
        if (state != NEW ||
                !UNSAFE.compareAndSwapObject(this, runnerOffset,
                        null, Thread.currentThread()))
        {
            return;
        }
        try
        {
            Callable<V> c = callable;
            if (c != null && state == NEW)
            {
                V       result;
                boolean ran;
                try
                {
                    result = c.call();
                    ran    = true;
                }
                catch (Throwable ex)
                {
                    result = null;
                    ran    = false;
                    // NEW -> COMPLETING -> EXCEPTIONAL
                    setException(ex);
                }
                if (ran)
                {
                    // NEW -> COMPLETING -> NORMAL
                    set(result);
                }
            }
            // set OR setException 只有 CAS(state, NEW, COMPLETING) 成功后
            // 才会调用 finishCompletion 函数: waiters -> done() -> callable
        }
        finally
        {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            int s = state;
            if (s >= INTERRUPTING)
            {
                // INTERRUPTING、INTERRUPTED
                handlePossibleCancellationInterrupt(s);
            }
        }
    }

    /**
     * Executes the computation without setting its result, and then
     * resets this future to initial state, failing to do so if the
     * computation encounters an exception or is cancelled.  This is
     * designed for use with tasks that intrinsically execute more
     * than once.
     *
     * @return {@code true} if successfully run and reset
     */
    protected boolean runAndReset()
    {
        if (state != NEW ||
                !UNSAFE.compareAndSwapObject(this, runnerOffset,
                        null, Thread.currentThread()))
        {
            return false;
        }
        boolean ran = false;
        int     s   = state;
        try
        {
            Callable<V> c = callable;
            if (c != null && s == NEW)
            {
                try
                {
                    c.call(); // don't set result
                    ran = true;
                }
                catch (Throwable ex)
                {
                    setException(ex);
                }
            }
        }
        finally
        {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            s = state;
            if (s >= INTERRUPTING)
            {
                handlePossibleCancellationInterrupt(s);
            }
        }
        return ran && s == NEW;
    }

    /**
     * Ensures that any interrupt from a possible cancel(true) is only
     * delivered to a task while in run or runAndReset.<br>
     * 确保只有在 run 或 runAndReset 时, 来自可能的 cancel(true) 的中断才会发送给任务
     */
    private void handlePossibleCancellationInterrupt(int s)
    {
        // It is possible for our interrupter to stall before getting a
        // chance to interrupt us.  Let's spin-wait patiently.
        if (s == INTERRUPTING)
        {
            while (state == INTERRUPTING)
            {
                Thread.yield(); // wait out pending interrupt
            }
        }

        // assert state == INTERRUPTED;

        // We want to clear any interrupt we may have received from
        // cancel(true).  However, it is permissible to use interrupts
        // as an independent mechanism for a task to communicate with
        // its caller, and there is no way to clear only the
        // cancellation interrupt.
        //
        // Thread.interrupted();
    }

    // =================================================================================================================

    /**
     * Simple linked list nodes to record waiting threads in a Treiber
     * stack.  See other classes such as Phaser and SynchronousQueue
     * for more detailed explanation.
     */
    static final class WaitNode
    {
        volatile Thread   thread;
        volatile WaitNode next;

        WaitNode()
        {
            thread = Thread.currentThread();
        }
    }

    /**
     * Removes and signals all waiting threads, invokes done(), and
     * nulls out callable.
     */
    private void finishCompletion()
    {
        // 调用此函数时可能的状态: NORMAL、EXCEPTIONAL、CANCELLED、INTERRUPTED
        // assert state > COMPLETING;
        for (WaitNode q; (q = waiters) != null; )
        {
            if (UNSAFE.compareAndSwapObject(this, waitersOffset, q, null))
            {
                for (; ; )
                {
                    Thread t = q.thread;
                    if (t != null)
                    {
                        q.thread = null;
                        LockSupport.unpark(t);
                    }
                    WaitNode next = q.next;
                    if (next == null)
                    {
                        break;
                    }
                    q.next = null; // unlink to help gc
                    q      = next;
                }
                break;
            }
        }

        done();

        callable = null;        // to reduce footprint
    }

    /**
     * Awaits completion or aborts on interrupt or timeout.
     *
     * @param timed true if use timed waits
     * @param nanos time to wait, if timed
     * @return state upon completion
     */
    private int awaitDone(boolean timed, long nanos)
            throws InterruptedException
    {
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        WaitNode   q        = null;
        boolean    queued   = false;
        for (; ; )
        {
            if (Thread.interrupted())
            {
                removeWaiter(q);
                throw new InterruptedException();
            }

            int s = state;
            if (s > COMPLETING)
            {
                if (q != null)
                {
                    q.thread = null;
                }
                return s;
            }
            else if (s == COMPLETING) // cannot time out yet
            {
                Thread.yield();
            }
            else if (q == null)
            {
                q = new WaitNode();
            }
            else if (!queued)
            {
                queued = UNSAFE.compareAndSwapObject(this, waitersOffset,
                        q.next = waiters, q);
            }
            else if (timed)
            {
                nanos = deadline - System.nanoTime();
                if (nanos <= 0L)
                {
                    removeWaiter(q);
                    return state;
                }
                LockSupport.parkNanos(this, nanos);
            }
            else
            {
                LockSupport.park(this);
            }
        }
    }

    /**
     * Tries to unlink a timed-out or interrupted wait node to avoid
     * accumulating garbage.  Internal nodes are simply unspliced
     * without CAS since it is harmless if they are traversed anyway
     * by releasers.  To avoid effects of unsplicing from already
     * removed nodes, the list is retraversed in case of an apparent
     * race.  This is slow when there are a lot of nodes, but we don't
     * expect lists to be long enough to outweigh higher-overhead
     * schemes.
     */
    private void removeWaiter(WaitNode node)
    {
        if (node != null)
        {
            node.thread = null;
            retry:
            for (; ; )
            {
                // restart on removeWaiter race
                for (WaitNode pred = null, q = waiters, s; q != null; q = s)
                {
                    s = q.next;
                    if (q.thread != null)
                    {
                        pred = q;
                    }
                    else if (pred != null)
                    {
                        pred.next = s;
                        if (pred.thread == null) // check for race
                        {
                            continue retry;
                        }
                    }
                    else if (!UNSAFE.compareAndSwapObject(this, waitersOffset,
                            q, s))
                    {
                        continue retry;
                    }
                }
                break;
            }
        }
    }
}
