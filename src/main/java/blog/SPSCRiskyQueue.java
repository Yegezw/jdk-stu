package blog;

import java.util.Objects;

/**
 * <a href="https://alexn.org/blog/2023/06/19/java-volatiles/">Java Volatiles<a/>
 * <p>This is a "single-producer, single-consumer" (SPSC) queue.
 */
public class SPSCRiskyQueue<T>
{
    /**
     * producer | consumer
     */
    private volatile String state = "producer";

    private T   value       = null;
    private long pushedCount = 0;

    public long getPushedCount()
    {
        return pushedCount;
    }

    /**
     * producer
     */
    public void push(T value) throws InterruptedException
    {
        while (!Objects.equals(state, "producer"))
        {
            spin();
        }

        this.value = value;
        pushedCount++;

        state = "consumer";
    }

    /**
     * consumer
     */
    public T pop() throws InterruptedException
    {
        while (!Objects.equals(state, "consumer"))
        {
            spin();
        }

        final T value = this.value;

        state = "producer";
        return value;
    }

    /**
     * 自旋
     */
    private void spin() throws InterruptedException
    {
        Thread.onSpinWait();
        if (Thread.interrupted()) throw new InterruptedException();
    }
}
