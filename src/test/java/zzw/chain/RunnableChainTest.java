package zzw.chain;

import lombok.extern.slf4j.Slf4j;
import zzw.Util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class RunnableChainTest
{

    public static void main(String[] args)
    {
        ExecutorService pool = Executors.newCachedThreadPool();

        // ------------------------------------------------

        Runnable r1 = () ->
        {
            Util.sleep(500);
            log.info("r1 执行完成");
        };
        Runnable r2 = () ->
        {
            Util.sleep(1000);
            log.info("r2 执行完成");
        };
        Runnable r3 = () ->
        {
            Util.sleep(1500);
            log.info("r3 执行完成");
        };
        Runnable r4 = () ->
        {
            Util.sleep(2000);
            log.info("r4 执行完成");
        };

        // ------------------------------------------------

        Runnable r5 = () ->
        {
            Util.sleep(500);
            throw new RuntimeException("r5 执行失败");
        };
        Runnable r6 = () ->
        {
            Util.sleep(1000);
            log.info("r6 执行完成");
        };
        Runnable r7 = () ->
        {
            Util.sleep(1500);
            log.info("r7 执行完成");
        };
        Runnable r8 = () ->
        {
            Util.sleep(2000);
            throw new RuntimeException("r8 执行失败");
        };

        // ------------------------------------------------

        RunnableChain chain = RunnableChain.create("执行链", pool)
                .with("组一", r1, r2, r3, r4)
                .with("组二", r5, r6, r7, r8);
        boolean success = chain.start();
        if (!success)
        {
            chain.printErrorInfo();
        }
        else
        {
            pool.shutdown();
        }
    }
}
