package thread_pool;

import com.google.common.util.concurrent.*;
import zzw.Util;

import java.util.List;
import java.util.concurrent.*;

/**
 * <a href="https://tech.meituan.com/2022/05/12/principles-and-practices-of-completablefuture.html">CompletableFuture 原理与实践 - 外卖商家端 API 的异步化</a>
 */
public class Test6
{

    private static final CountDownLatch LATCH = new CountDownLatch(1);

    private static String info()
    {
        return Thread.currentThread().getName() + " ";
    }

    @SuppressWarnings("all")
    private static void testGuavaCallBack()
    {
        // 创建线程池
        ExecutorService          executor      = Executors.newFixedThreadPool(5);
        ListeningExecutorService guavaExecutor = MoreExecutors.listeningDecorator(executor);

        // 执行 step1 和 step2
        ListenableFuture<String> future1 = guavaExecutor.submit(() ->
        {
            System.out.println(info() + "执行 step 1");
            return "step1 result";
        });
        ListenableFuture<String> future2 = guavaExecutor.submit(() ->
        {
            System.out.println(info() + "执行 step 2");
            return "step2 result";
        });

        // step3 依赖 step1 和 step2
        ListenableFuture<List<String>> future1And2 = Futures.allAsList(future1, future2);
        Futures.addCallback(
                future1And2,
                new FutureCallback<>()
                {
                    @Override
                    public void onSuccess(List<String> result)
                    {
                        System.out.println(info() + result);
                        ListenableFuture<String> future3 = guavaExecutor.submit(() ->
                        {
                            System.out.println(info() + "执行 step 3");
                            return "step3 result";
                        });
                        Futures.addCallback(
                                future3,
                                new FutureCallback<>()
                                {
                                    @Override
                                    public void onSuccess(String result)
                                    {
                                        System.out.println(info() + result + "\n");
                                        guavaExecutor.shutdownNow();
                                        LATCH.countDown();
                                    }

                                    @Override
                                    public void onFailure(Throwable t)
                                    {
                                    }
                                },
                                guavaExecutor
                        );
                    }

                    @Override
                    public void onFailure(Throwable t)
                    {
                    }
                },
                guavaExecutor
        );
    }

    private static void testCompletable()
    {
        // 创建线程池
        ExecutorService executor = Executors.newFixedThreadPool(5);

        // executor                执行 step1
        // ForkJoinPool.commonPool 执行 step2
        CompletableFuture<String> cf1 = CompletableFuture.supplyAsync(
                () ->
                {
                    System.out.println(info() + "执行 step 1");
                    return "step1 result";
                },
                executor
        );
        CompletableFuture<String> cf2 = CompletableFuture.supplyAsync(
                () ->
                {
                    System.out.println(info() + "执行 step 2");
                    return "step2 result";
                }
        );

        // step3 依赖 step1 和 step2
        cf1.thenCombine(cf2,
                (result1, result2) ->
                {
                    System.out.println(info() + result1 + ", " + result2);
                    System.out.println(info() + "执行 step 3");
                    return "step3 result";
                }
        ).thenAccept(
                result3 ->
                {
                    System.out.println(info() + result3);
                    executor.shutdownNow();
                }
        );
    }

    /**
     * 根据 CompletableFuture 依赖数量<br>
     * 可以分为以下几类: 零依赖、一元依赖、二元依赖、多元依赖
     */
    @SuppressWarnings("all")
    private static void studyCompletable()
    {
        ExecutorService executor = Executors.newFixedThreadPool(3);

        /*
         * 获取结果
         * isDone
         * get
         * getNow
         * join
         * complete
         * completeExceptionally
         */

        // -------------------------------------------------------------------------

        /*
         * 不依赖其它 CompletableFuture 来创建新的 CompletableFuture
         * runAsync       无参数 + 无返回值
         * supplyAsync    无参数 + 有返回值
         * CompletableFuture.completedFuture(值)
         * cf.complete(正常)
         * cf.completeExceptionally(异常)
         */

        // 1、使用 runAsync 或 supplyAsync 发起异步调用
        CompletableFuture<String> cf1 = CompletableFuture.supplyAsync(
                () ->
                {
                    return "result1";
                },
                executor
        );

        // 2、CompletableFuture.completedFuture() 直接创建一个已完成状态的 CompletableFuture
        CompletableFuture<String> cf2 = CompletableFuture.completedFuture("result2");

        // 3、先初始化一个未完成的 CompletableFuture
        // 然后通过 complete(正常)、completeExceptionally(异常), 完成该 CompletableFuture
        // 典型使用场景: 将回调方法转为 CompletableFuture, 然后再依赖 CompletableFuture 的能力进行调用编排
        CompletableFuture<String> cf = new CompletableFuture<>();
        cf.complete("success");

        // -------------------------------------------------------------------------

        /*
         * 一元依赖
         * thenApply      thenApplyAsync     一个参数 + 有返回值
         * thenAccept     thenAcceptAsync    一个参数 + 无返回值
         * thenRun        thenRunAsync       没有参数 + 无返回值
         * thenCompose    thenComposeAsync   一个参数 + 有返回值
         */

        CompletableFuture<String> cf3 = cf1.thenApply(
                result1 ->
                {
                    // result1 为 CF1 的结果
                    // ...
                    return "result3";
                }
        );
        CompletableFuture<String> cf5 = cf2.thenApply(
                result2 ->
                {
                    // result2 为 CF2 的结果
                    // ...
                    return "result5";
                }
        );

        // -------------------------------------------------------------------------

        /*
         * 二元依赖
         *
         * thenCombine       thenCombineAsync       & 两个参数 + 有返回值
         * thenAcceptBoth    thenAcceptBothAsync    & 两个参数 + 无返回值
         * runAfterBoth      runAfterBothAsync      & 没有参数 + 无返回值
         *
         * applyToEither     applyToEitherAsync     | 一个参数 + 有返回值
         * acceptEither      acceptEitherAsync      | 一个参数 + 无返回值
         * runAfterEither    runAfterEitherAsync    | 没有参数 + 无返回值
         */

        CompletableFuture<String> cf4 = cf1.thenCombine(cf2,
                (result1, result2) ->
                {
                    // result1 和 result2 分别为 cf1 和 cf2 的结果
                    return "result4";
                }
        );

        // -------------------------------------------------------------------------

        /*
         * 多元依赖
         * allOf 全部完成
         * anyOf 任一完成
         */

        CompletableFuture<Void> cf6 = CompletableFuture.allOf(cf3, cf4, cf5);
        CompletableFuture<String> result = cf6.thenApply(
                v ->
                {
                    // 这里的 join 并不会阻塞
                    // 因为传给 thenApply 的函数是在 CF3、CF4、CF5 全部完成时才会执行
                    String result3 = cf3.join();
                    String result4 = cf4.join();
                    String result5 = cf5.join();
                    return "result";
                }
        );

        // -------------------------------------------------------------------------

        /*
         * 异常处理
         * whenComplete            whenCompleteAsync            两个参数 + 无返回值 + 没有异常也会执行
         * handle                  handleAsync                  两个参数 + 有返回值 + 没有异常也会执行
         * exceptionally           exceptionallyAsync           一个参数 + 有返回值 + 发生异常才会执行
         * exceptionallyCompose    exceptionallyComposeAsync    一个参数 + 有返回值 + 发生异常才会执行
         */
    }

    /**
     * 代码执行在哪个线程上
     */
    private static void caller()
    {
        ExecutorService threadPool = new ThreadPoolExecutor(
                10, 10,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(100)
        );
        CompletableFuture<String> future = CompletableFuture.supplyAsync(
                () ->
                {
                    System.out.println("supplyAsync 执行线程 " + info());
                    return "";
                },
                threadPool
        );

        // 如果 future 中的业务操作已经执行完毕并返回
        // 则该 thenApply 直接由当前 main 线程执行
        // 否则, 将会由执行以上业务操作的 threadPool 中的线程执行
        Util.sleep(1000);
        future.thenApply(
                value ->
                {
                    System.out.println("thenApply 执行线程 " + info());
                    return value + "1";
                }
        );
        // 使用 ForkJoinPool 中的共用线程池 CommonPool
        future.thenApplyAsync(
                value ->
                {
                    System.out.println("thenApplyAsync1 执行线程 " + info());
                    return value + "1";
                }
        );
        // 使用指定线程池
        future.thenApplyAsync(
                value ->
                {
                    System.out.println("thenApplyAsync2 执行线程 " + info());
                    return value + "1";
                },
                threadPool
        );
    }

    /**
     * 死锁
     */
    @SuppressWarnings("all")
    private static Object deadlock()
    {
        // 单核心线程池
        ExecutorService threadPool = Executors.newSingleThreadExecutor();
        CompletableFuture<String> father = CompletableFuture.supplyAsync(
                () ->
                {
                    // do sth
                    return CompletableFuture.supplyAsync(
                            () ->
                            {
                                System.out.println("child");
                                return "child";
                            }
                            , threadPool
                    ).join(); // 父任务等待子任务
                },
                threadPool
        );
        return father.join(); // 主线程等待父任务
    }

    /**
     * <p>提取真实异常
     * <p>CompletableFuture 在回调方法中对异常进行了包装
     * <br>大部分异常会封装成 CompletionException 后抛出, 真正的异常存储在 cause 属性中
     * <br>因此如果调用链中经过了回调方法处理, 那么就需要用 Throwable.getCause() 方法提取真正的异常
     * <br>但是有些情况下会直接返回真正的异常, 最好使用工具类提取异常
     */
    public static Throwable extractRealException(Throwable throwable)
    {
        // 这里判断异常类型是否为 CompletionException、ExecutionException
        // 如果是则进行提取, 否则直接返回, 注意不判断 CancellationException, 它代表任务被取消
        if (throwable instanceof CompletionException || throwable instanceof ExecutionException)
        {
            if (throwable.getCause() != null) return throwable.getCause();
        }
        return throwable;
    }

    public static void main(String[] args) throws Exception
    {
        testGuavaCallBack();
        LATCH.await();
        testCompletable();

        caller();
        deadlock();
    }
}
