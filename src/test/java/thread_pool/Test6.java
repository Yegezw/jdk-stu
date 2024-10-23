package thread_pool;

import com.google.common.util.concurrent.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    public static void main(String[] args) throws Exception
    {
        testGuavaCallBack();
        LATCH.await();
        testCompletable();
    }
}
