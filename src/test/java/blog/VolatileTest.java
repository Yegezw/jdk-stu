package blog;

/**
 * <a href="https://mp.weixin.qq.com/s/4tlE5t4oysoHop6OlswSlg">Java volatile 关键字到底是什么｜得物技术<a/>
 */
public class VolatileTest {

    static long h1, h2, h3, h4, h5, h6, h7;
    static int m = 0;
    static int a = 0, b = 0;
    static int x = 0, y = 0;
    static long t1, t2, t3, t4, t5, t6, t7;

    /*
     * | a = 1 [A] | a = 1 [A] | a = 1 [A] | b = 1 [B] | b = 1 [B] | b = 1 [B] |
     * | x = b [A] | b = 1 [B] | b = 1 [B] | a = 1 [A] | a = 1 [A] | y = a [B] |
     * | b = 1 [B] | x = b [A] | y = a [B] | y = a [B] | x = b [A] | a = 1 [A] |
     * | y = a [B] | y = a [B] | x = b [A] | x = b [A] | y = a [B] | x = b [A] |
     * -------------------------------------------------------------------------
     * | x 0 | y 1 | x 1 | y 1 | x 1 | y 1 | x 1 | y 1 | x 1 | y 1 | x 1 | y 0 |
     *
     * 我们需要限制针对数据 X、Y 的写操作之前
     * 位于 store-buffer 中的数据 A、B 全部 flush 到高速缓存即可 (给变量 A、B 添加 volatile 关键字)
     */

    public static void main(String[] args) throws InterruptedException {
        for (int i = 0; true; i++) {
            a = 0;
            b = 0;
            x = 0;
            y = 0;

            Thread threadA = new Thread(() -> {
                m = 1; // 保证线程 A 读到 a b x y 缓存行
                a = 1;
                x = b;
            });

            Thread threadB = new Thread(() -> {
                m = 1; // 保证线程 B 读到 a b x y 缓存行
                b = 1;
                y = a;
            });

            threadA.start();
            threadB.start();

            threadA.join();
            threadB.join();

            if (x == 0 && y == 0) {
                System.err.println("bingo! i: " + i);
                break;
            }
        }
    }
}
