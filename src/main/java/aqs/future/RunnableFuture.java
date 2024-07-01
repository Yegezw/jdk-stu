package aqs.future;

public interface RunnableFuture<V> extends Runnable, Future<V>
{

    void run();
}
