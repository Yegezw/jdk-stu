package aqs.thread_pool;

import java.util.concurrent.*;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;

/**
 * A {@link CompletionService} that uses a supplied {@link Executor}
 * to execute tasks.  This class arranges that submitted tasks are,
 * upon completion, placed on a queue accessible using {@code take}.
 * The class is lightweight enough to be suitable for transient use
 * when processing groups of tasks.
 *
 * <p>
 *
 * <b>Usage Examples.</b>
 * <p>
 * Suppose you have a set of solvers for a certain problem, each
 * returning a value of some type {@code Result}, and would like to
 * run them concurrently, processing the results of each of them that
 * return a non-null value, in some method {@code use(Result r)}. You
 * could write this as:
 *
 * <pre> {@code
 * void solve(Executor e,
 *            Collection<Callable<Result>> solvers)
 *     throws InterruptedException, ExecutionException {
 *   CompletionService<Result> cs
 *       = new ExecutorCompletionService<>(e);
 *   solvers.forEach(cs::submit);
 *   for (int i = solvers.size(); i > 0; i--) {
 *     Result r = cs.take().get();
 *     if (r != null)
 *       use(r);
 *   }
 * }}</pre>
 * <p>
 * Suppose instead that you would like to use the first non-null result
 * of the set of tasks, ignoring any that encounter exceptions,
 * and cancelling all other tasks when the first one is ready:
 *
 * <pre> {@code
 * void solve(Executor e,
 *            Collection<Callable<Result>> solvers)
 *     throws InterruptedException {
 *   CompletionService<Result> cs
 *       = new ExecutorCompletionService<>(e);
 *   int n = solvers.size();
 *   List<Future<Result>> futures = new ArrayList<>(n);
 *   Result result = null;
 *   try {
 *     solvers.forEach(solver -> futures.add(cs.submit(solver)));
 *     for (int i = n; i > 0; i--) {
 *       try {
 *         Result r = cs.take().get();
 *         if (r != null) {
 *           result = r;
 *           break;
 *         }
 *       } catch (ExecutionException ignore) {}
 *     }
 *   } finally {
 *     futures.forEach(future -> future.cancel(true));
 *   }
 *
 *   if (result != null)
 *     use(result);
 * }}</pre>
 *
 * @since 1.5
 */
public class ExecutorCompletionService<V> implements CompletionService<V>
{

    /**
     * FutureTask extension to enqueue upon completion.
     */
    private static class QueueingFuture<V> extends FutureTask<Void>
    {
        private final Future<V>                task;
        private final BlockingQueue<Future<V>> completionQueue;

        /*
         * 将 RunnableFuture<V> 包装成 FutureTask<Void>
         *
         * FutureTask<Void>.run()
         * {
         *     result = callable.call()
         *     {
         *         RunnableFuture<V>.run()
         *         {
         *             result  = Callable<V>.call(); 入参
         *             outcome = result;
         *         };
         *         return null;
         *     };
         *
         *     outcome = result = null;
         *
         *     done()
         *     {
         *         completionQueue.add(RunnableFuture<V>);
         *     };
         * }
         */
        QueueingFuture(RunnableFuture<V> task, BlockingQueue<Future<V>> completionQueue)
        {
            super(task, null);
            this.task            = task;
            this.completionQueue = completionQueue;
        }

        protected void done()
        {
            completionQueue.add(task);
        }
    }

    // =================================================================================================================

    private final Executor                 executor;
    private final BlockingQueue<Future<V>> completionQueue;

    /**
     * Creates an ExecutorCompletionService using the supplied
     * executor for base task execution and a
     * {@link LinkedBlockingQueue} as a completion queue.
     *
     * @param executor the executor to use
     * @throws NullPointerException if executor is {@code null}
     */
    public ExecutorCompletionService(Executor executor)
    {
        if (executor == null) throw new NullPointerException();
        this.executor        = executor;
        this.completionQueue = new LinkedBlockingQueue<>();
    }

    /**
     * Creates an ExecutorCompletionService using the supplied
     * executor for base task execution and the supplied queue as its
     * completion queue.
     *
     * @param executor        the executor to use
     * @param completionQueue the queue to use as the completion queue
     *                        normally one dedicated for use by this service. This
     *                        queue is treated as unbounded -- failed attempted
     *                        {@code Queue.add} operations for completed tasks cause
     *                        them not to be retrievable.
     * @throws NullPointerException if executor or completionQueue are {@code null}
     */
    public ExecutorCompletionService(Executor executor, BlockingQueue<Future<V>> completionQueue)
    {
        if (executor == null || completionQueue == null) throw new NullPointerException();
        this.executor        = executor;
        this.completionQueue = completionQueue;
    }

    // =================================================================================================================

    private RunnableFuture<V> newTaskFor(Callable<V> task)
    {
        return new FutureTask<>(task);
    }

    private RunnableFuture<V> newTaskFor(Runnable task, V result)
    {
        return new FutureTask<>(task, result);
    }

    // ------------------------------------------------

    /*
     * submit
     * 会把 Callable  <V> 包装成 FutureTask<V>
     * 再把 FutureTask<V> 包装成 FutureTask<Void>
     *
     * FutureTask<Void>.run()
     * {
     *     result = callable.call()
     *     {
     *         FutureTask<V>.run()
     *         {
     *             result  = Callable<V>.call(); 入参
     *             outcome = result;
     *         };
     *         return null;
     *     };
     *
     *     outcome = result = null;
     *
     *     done()
     *     {
     *         completionQueue.add(FutureTask<V>);
     *     };
     * }
     */

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public Future<V> submit(Callable<V> task)
    {
        if (task == null) throw new NullPointerException();
        RunnableFuture<V> f = newTaskFor(task);
        executor.execute(new QueueingFuture<>(f, completionQueue));
        return f;
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public Future<V> submit(Runnable task, V result)
    {
        if (task == null) throw new NullPointerException();
        RunnableFuture<V> f = newTaskFor(task, result);
        executor.execute(new QueueingFuture<>(f, completionQueue));
        return f;
    }

    // ------------------------------------------------

    /**
     * 阻塞
     */
    public Future<V> take() throws InterruptedException
    {
        return completionQueue.take();
    }

    /**
     * 可返回空
     */
    public Future<V> poll()
    {
        return completionQueue.poll();
    }

    /**
     * 可超时阻塞 + 可返回空
     */
    public Future<V> poll(long timeout, TimeUnit unit) throws InterruptedException
    {
        return completionQueue.poll(timeout, unit);
    }
}
