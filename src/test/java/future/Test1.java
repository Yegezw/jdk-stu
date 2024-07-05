package future;

import aqs.future.Callable;
import aqs.future.FutureTask;
import zzw.Util;

import java.util.concurrent.ExecutionException;

public class Test1
{

    public static void main(String[] args) throws ExecutionException, InterruptedException
    {
        int[] arr = {1, 2, 3, 4, 5};

        // 创建任务并执行
        Callable<Integer> callable = () ->
        {
            int sum = 0;
            for (int i : arr)
            {
                sum += i;
            }
            return sum;
        };
        FutureTask<Integer> task   = new FutureTask<>(callable);
        Thread              thread = new Thread(task);
        thread.start(); // task.run() -> callable.call()

        // 主线程阻塞 100 ms
        Util.sleep(100);

        // 获取返回值, get() 是阻塞函数, isDone() 是非阻塞函数
        // get() -> LockSupport.park(this)
        // task.run() -> call() -> finishCompletion() -> LockSupport.unpark(t)
        if (task.isDone())
        {
            System.out.println("sum(arr) = " + task.get());
        }
    }
}
