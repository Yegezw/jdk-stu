import zzw.BlockingQueueSem;

/**
 * 测试 BlockingQueueSem
 */
@SuppressWarnings("all")
public class Test6 {

    public static void main(String[] args) {
        BlockingQueueSem<Integer> queue = new BlockingQueueSem<>(1);

        Runnable r = new Runnable() {
            @Override
            public void run() {
                while (true) queue.dequeue();
            }
        };
        Runnable w = new Runnable() {
            private int num = 1;

            @Override
            public void run() {
                while (true) queue.enqueue(num++);
            }
        };


        Thread rt = new Thread(r);
        Thread wt = new Thread(w);
        rt.start();
        wt.start();
    }
}
