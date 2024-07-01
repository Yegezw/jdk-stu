package aqs.future;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public interface Future<V>
{

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
    boolean cancel(boolean mayInterruptIfRunning);

    /**
     * 判断任务是否被取消了
     * <br>
     * 如果一个任务在正常执行完成之前被 cancel 掉了, 则返回 true
     */
    boolean isCancelled();

    /**
     * 如果一个任务已经结束, 则返回 true<br>
     * 1、任务已经被取消<br>
     * 2、任务抛出了异常<br>
     * 3、任务正常执行完毕
     */
    boolean isDone();

    /**
     * 获取执行结果, 如果任务还在执行中, 就阻塞等待
     */
    V get() throws InterruptedException, ExecutionException;

    V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException;
}
