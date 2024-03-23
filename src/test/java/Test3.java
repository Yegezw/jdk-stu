import zzw.BlockingQueueCond;

/**
 * 测试 BlockingQueueCond
 */
@SuppressWarnings("all")
public class Test3 {

    public static void main(String[] args) {
        BlockingQueueCond<Integer> queue = new BlockingQueueCond<>(1);

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
