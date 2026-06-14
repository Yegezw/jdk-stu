package other;

public class InheritableThreadLocalTest {

    private static final InheritableThreadLocal<String> CONTEXT = new InheritableThreadLocal<>();

    public static void main(String[] args) {
        // Thread 内部有两个相关字段
        // 1. ThreadLocal.ThreadLocalMap threadLocals;
        // 2. ThreadLocal.ThreadLocalMap inheritableThreadLocals;
        // 创建子线程时, Thread 构造过程会检查父线程有没有 inheritableThreadLocals
        // 如果有, 就把父线程里的值复制到子线程 inheritableThreadLocals
        // 默认情况下, 子线程拿到的是父线程 value 的同一个对象引用
        // 如果想定制复制逻辑, 可以继承 InheritableThreadLocal 并重写 childValue()

        CONTEXT.set("apple");

        Thread child = new Thread(() -> System.out.println(CONTEXT.get()));

        child.start();
    }
}
