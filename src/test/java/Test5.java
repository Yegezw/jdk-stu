import aqs.synchronization_tool.Semaphore;

/**
 * 使用 Semaphore 实现接口并发限制功能
 */
@SuppressWarnings("all")
public class Test5 {

    private final Semaphore semaphore = new Semaphore(10);

    public void apiX() {
        semaphore.acquireUninterruptibly();
        try {
            // 执行业务逻辑
        } finally {
            semaphore.release();
        }
    }
}
