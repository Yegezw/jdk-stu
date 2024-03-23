package zzw;

/**
 * <p>
 * 在一个标准的 Controller-Service-Repository 三层结构的后端系统中
 * </p>
 * <p>
 * 希望实现一个简单的调用链追踪功能<br>
 * 每个接口请求所对应的所有日志都附带同一个 traceId<br>
 * 这样我们通过 traceId 便可以轻松得到一个接口请求的所有日志, 方便通过日志查找代码问题
 * </p>
 * <p>
 * 每个函数中都定义 traceId 参数, 会导致非业务代码和业务代码耦合在一起<br>
 * ThreadLocal 既是线程私有的, 又可以在函数之间共享<br>
 * 使用 ThreadLocal 传递 traceId, 既能避免线程安全问题, 又能避免变量在函数之间不停传递
 * </p>
 */
public class Context {

    /**
     * <p>
     * 每个 Thread 都有 ThreadLocalMap 类型的 threadLocals 成员变量<br>
     * ThreadLocalMap 是 ThreadLocal 的内部类, K : V = ThreadLocal : Object
     * </p>
     * <p>
     * ThreadLocal 用于将值写入 Thread.threadLocals<br>
     * ThreadLocal.set(obj) 等价于 Thread.threadLocals.set(this, obj)
     * </p>
     */
    private static final ThreadLocal<String> threadLocalTraceId = new ThreadLocal<String>() {
        @Override
        protected String initialValue() {
            return "[" + System.currentTimeMillis() + "]";
        }
    };

    public static void setTraceId(String traceId) {
        // 如果 Thread.threadLocals = null, 会初始化 threadLocals
        threadLocalTraceId.set(traceId);
    }

    public static String getTraceId() {
        // 如果 Thread.threadLocals = null, 会初始化 threadLocals
        // 如果 Thread.threadLocals.get(ThreadLocal) == null, 则 threadLocals.set(ThreadLocal, initialValue())
        return threadLocalTraceId.get();
    }

    public static void remove() {
        threadLocalTraceId.remove();
    }
}