package aqs.future;

public class Executors
{

    /**
     * A callable that runs given task and returns given result
     */
    static final class RunnableAdapter<T> implements Callable<T>
    {
        final Runnable task;
        final T        result;

        RunnableAdapter(Runnable task, T result)
        {
            this.task   = task;
            this.result = result;
        }

        public T call()
        {
            task.run();
            return result;
        }
    }

    /**
     * Returns a {@link java.util.concurrent.Callable} object that, when
     * called, runs the given task and returns the given result.  This
     * can be useful when applying methods requiring a
     * {@code Callable} to an otherwise resultless action.
     *
     * @param task   the task to run
     * @param result the result to return
     * @param <T>    the type of the result
     * @return a callable object
     * @throws NullPointerException if task null
     */
    public static <T> Callable<T> callable(Runnable task, T result)
    {
        if (task == null)
        {
            throw new NullPointerException();
        }
        return new Executors.RunnableAdapter<>(task, result);
    }
}
