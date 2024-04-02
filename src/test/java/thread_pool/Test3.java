package thread_pool;

import zzw.Util;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public class Test3 {

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        int[] arr = {1, 2, 3, 4, 5};

        // 创建任务并执行
        Callable<Integer> callable = new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                int sum = 0;
                for (int i : arr) sum += i;
                return sum;
            }
        };
        FutureTask<Integer> task = new FutureTask<>(callable);
        Thread thread = new Thread(task);
        thread.start(); // 执行的是 task.run()

        // 主线程阻塞 100 ms
        Util.sleep(100);

        // 获取返回值, get() 是阻塞函数, isDone() 是非阻塞函数
        // get() -> LockSupport.park(this)
        // task.run() -> call() -> LockSupport.unpark(t)
        if (task.isDone()) System.out.println("sum(arr) = " + task.get());
    }
}
