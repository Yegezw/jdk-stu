package concurrent_collections;

import java.util.concurrent.DelayQueue;

/**
 * 延迟阻塞并发队列 DelayQueue
 */
public class Test2 {

    /**
     * 从任务池拿取任务 Job 并执行
     */
    private static class JobThread extends Thread {
        private final DelayQueue<Job> jobs; // 延迟阻塞并发队列, 它其实是一个任务池

        public JobThread(DelayQueue<Job> jobs) {
            this.jobs = jobs;
        }

        /**
         * 从任务池拿取任务 Job 并执行
         */
        @Override
        public void run() {
            try {
                Job job = jobs.take(); // 获取任务 Job, 可能会被阻塞
                job.run();             // 执行任务
            } catch (InterruptedException ignore) {
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // 延迟阻塞并发队列, 它其实是一个任务池
        DelayQueue<Job> jobs = new DelayQueue<>();
        jobs.put(new Job("job1", 1000));
        jobs.put(new Job("job2", 2000));
        jobs.put(new Job("job3", 3000));

        Thread t1 = new JobThread(jobs);
        Thread t2 = new JobThread(jobs);
        Thread t3 = new JobThread(jobs);

        // Util.sleep(3000);
        System.out.println("start");
        t1.start();
        t2.start();
        t3.start();
    }
}
