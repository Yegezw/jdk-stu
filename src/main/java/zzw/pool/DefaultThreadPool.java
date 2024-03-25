package zzw.pool;

import aqs.Condition;
import aqs.lock.Lock;
import aqs.lock.ReentrantLock;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

public class DefaultThreadPool implements ThreadPool {

    private class Worker implements Runnable {

        /**
         * 是否工作
         */
        private volatile boolean running = true;

        @Override
        public void run() {
            while (running) {
                Runnable task;

                lock.lock();
                try {
                    while (taskQueue.isEmpty()) notEmpty.await();
                    task = taskQueue.removeFirst();
                } catch (InterruptedException e) {
                    return;
                } finally {
                    lock.unlock();
                }

                try {
                    task.run();
                } catch (Exception ignore) {
                    // 忽略异常
                }
            }
        }

        public void shutdown() {
            running = false;
        }
    }

    // ==================================================================================

    private static final int DEFAULT_WORKER_NUMBERS  = 5;    // 默认线程数
    private static final int MIN_WORKER_NUMBERS      = 1;    // 最小线程数
    private static final int MAX_WORKER_NUMBERS      = 10;   // 最大线程数
    private static final int DEFAULT_WORK_QUEUE_SIZE = 100;  // 工作队列默认大小

    private final Lock      lock     = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    /**
     * 等待队列
     */
    private final LinkedList<Runnable> taskQueue;
    private final int                  capacity;

    private final int          workerNum;
    private final List<Worker> workers;
    private final AtomicLong   threadNum = new AtomicLong();

    public DefaultThreadPool() {
        this(DEFAULT_WORKER_NUMBERS, DEFAULT_WORK_QUEUE_SIZE);
    }

    public DefaultThreadPool(int threadNum, int taskQueueSize) {
        if (threadNum > MAX_WORKER_NUMBERS) this.workerNum = MAX_WORKER_NUMBERS;
        else this.workerNum = Math.max(threadNum, MIN_WORKER_NUMBERS);

        workers = new ArrayList<>(threadNum);
        initializeWorkers(threadNum);

        taskQueue = new LinkedList<>();
        capacity = taskQueueSize;
    }

    /**
     * 初始化 workers
     */
    private void initializeWorkers(int num) {
        for (int i = 0; i < num; i++) {
            Worker worker = new Worker();
            workers.add(worker);
            Thread thread = new Thread(worker, "ThreadPool-Worker-" + threadNum.incrementAndGet());
            thread.start();
        }
    }

    // ==================================================================================

    @Override
    public void execute(Runnable task) {
        if (task == null) throw new NullPointerException();
        if (taskQueue.size() == capacity) throw new RejectedExecutionException();

        lock.lock();
        try {
            if (taskQueue.size() == capacity) throw new RejectedExecutionException();
            taskQueue.add(task);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int getWaitingTaskCount() {
        lock.lock();
        try {
            return taskQueue.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void shutdown() {
        synchronized (workers) {
            for (Worker worker : workers) {
                worker.shutdown();
            }
        }
    }

    public int getWorkerNum() {
        return workerNum;
    }
}
