package util.thread;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * This class provides thread-local variables.  These variables differ from
 * their normal counterparts in that each thread that accesses one (via its
 * {@code get} or {@code set} method) has its own, independently initialized
 * copy of the variable.  {@code ThreadLocal} instances are typically private
 * static fields in classes that wish to associate state with a thread (e.g.,
 * a user ID or Transaction ID).
 *
 * <p>For example, the class below generates unique identifiers local to each
 * thread.
 * A thread's id is assigned the first time it invokes {@code ThreadId.get()}
 * and remains unchanged on subsequent calls.
 * <pre>
 * import java.util.concurrent.atomic.AtomicInteger;
 *
 * public class ThreadId {
 *     // Atomic integer containing the next thread ID to be assigned
 *     private static final AtomicInteger nextId = new AtomicInteger(0);
 *
 *     // Thread local variable containing each thread's ID
 *     private static final ThreadLocal&lt;Integer&gt; threadId =
 *         new ThreadLocal&lt;Integer&gt;() {
 *             &#64;Override protected Integer initialValue() {
 *                 return nextId.getAndIncrement();
 *         }
 *     };
 *
 *     // Returns the current thread's unique ID, assigning it if necessary
 *     public static int get() {
 *         return threadId.get();
 *     }
 * }
 * </pre>
 * <p>Each thread holds an implicit reference to its copy of a thread-local
 * variable as long as the thread is alive and the {@code ThreadLocal}
 * instance is accessible; after a thread goes away, all of its copies of
 * thread-local instances are subject to garbage collection (unless other
 * references to these copies exist).
 *
 * @author Josh Bloch and Doug Lea
 * @since 1.2
 */
public class ThreadLocal<T>
{

    /**
     * An extension of ThreadLocal that obtains its initial value from
     * the specified {@code Supplier}.
     */
    static final class SuppliedThreadLocal<T> extends ThreadLocal<T>
    {

        private final Supplier<? extends T> supplier;

        SuppliedThreadLocal(Supplier<? extends T> supplier)
        {
            this.supplier = Objects.requireNonNull(supplier);
        }

        @Override
        protected T initialValue()
        {
            return supplier.get();
        }
    }

    /**
     * Creates a thread local variable. The initial value of the variable is
     * determined by invoking the {@code get} method on the {@code Supplier}.
     *
     * @param <S>      the type of the thread local's value
     * @param supplier the supplier to be used to determine the initial value
     * @return a new thread local variable
     * @throws NullPointerException if the specified supplier is null
     * @since 1.8
     */
    public static <S> ThreadLocal<S> withInitial(Supplier<? extends S> supplier)
    {
        return new SuppliedThreadLocal<>(supplier);
    }

    // =================================================================================================================

    /**
     * The next hash code to be given out. Updated atomically. Starts at
     * zero.
     */
    private static final AtomicInteger nextHashCode = new AtomicInteger();

    /**
     * The difference between successively generated hash codes - turns
     * implicit sequential thread-local IDs into near-optimally spread
     * multiplicative hash values for power-of-two-sized tables.
     */
    private static final int HASH_INCREMENT = 0x61c88647;

    /**
     * Returns the next hash code.
     */
    private static int nextHashCode()
    {
        return nextHashCode.getAndAdd(HASH_INCREMENT);
    }

    // ------------------------------------------------

    /**
     * ThreadLocals rely on per-thread linear-probe hash maps attached
     * to each thread (Thread.threadLocals and
     * inheritableThreadLocals).  The ThreadLocal objects act as keys,
     * searched via threadLocalHashCode.  This is a custom hash code
     * (useful only within ThreadLocalMaps) that eliminates collisions
     * in the common case where consecutively constructed ThreadLocals
     * are used by the same threads, while remaining well-behaved in
     * less common cases.
     */
    final int threadLocalHashCode = nextHashCode(); // ThreadLocal.HashCode

    // ------------------------------------------------

    /**
     * Creates a thread local variable.
     *
     * @see #withInitial(java.util.function.Supplier)
     */
    public ThreadLocal()
    {
    }

    /**
     * Returns the current thread's "initial value" for this
     * thread-local variable.  This method will be invoked the first
     * time a thread accesses the variable with the {@link #get}
     * method, unless the thread previously invoked the {@link #set}
     * method, in which case the {@code initialValue} method will not
     * be invoked for the thread.  Normally, this method is invoked at
     * most once per thread, but it may be invoked again in case of
     * subsequent invocations of {@link #remove} followed by {@link #get}.
     *
     * <p>This implementation simply returns {@code null}; if the
     * programmer desires thread-local variables to have an initial
     * value other than {@code null}, {@code ThreadLocal} must be
     * subclassed, and this method overridden.  Typically, an
     * anonymous inner class will be used.
     *
     * @return the initial value for this thread-local
     */
    protected T initialValue()
    {
        return null;
    }

    // =================================================================================================================

    /**
     * Sets the current thread's copy of this thread-local variable
     * to the specified value.  Most subclasses will have no need to
     * override this method, relying solely on the {@link #initialValue}
     * method to set the values of thread-locals.
     *
     * @param value the value to be stored in the current thread's copy of
     *              this thread-local.
     */
    public void set(T value)
    {
        Thread         t   = Thread.currentThread();
        ThreadLocalMap map = getMap(t);

        // Thread.ThreadLocalMap 存在
        if (map != null)
        {
            map.set(this, value);
        }
        // Thread.ThreadLocalMap 不存在
        else
        {
            createMap(t, value);
        }
    }

    /**
     * Variant of set() to establish initialValue. Used instead
     * of set() in case user has overridden the set() method.
     *
     * @return the initial value
     */
    private T setInitialValue()
    {
        T              value = initialValue(); // 钩子方法
        Thread         t     = Thread.currentThread();
        ThreadLocalMap map   = getMap(t);

        // Thread.ThreadLocalMap 存在
        if (map != null)
        {
            map.set(this, value);
        }
        // Thread.ThreadLocalMap 不存在
        else
        {
            createMap(t, value);
        }

        // TODO ?
        if (this instanceof TerminatingThreadLocal)
        {
            TerminatingThreadLocal.register((TerminatingThreadLocal<?>) this);
        }

        return value;
    }

    /**
     * Returns the value in the current thread's copy of this
     * thread-local variable.  If the variable has no value for the
     * current thread, it is first initialized to the value returned
     * by an invocation of the {@link #initialValue} method.
     *
     * @return the current thread's value of this thread-local
     */
    public T get()
    {
        // return (T) ( Thread.currentThread().threadLocals.getEntry(this).value )
        Thread         t   = Thread.currentThread();
        ThreadLocalMap map = getMap(t);

        if (map != null)
        {
            ThreadLocalMap.Entry e = map.getEntry(this);
            if (e != null)
            {
                @SuppressWarnings("unchecked")
                T result = (T) e.value;
                return result;
            }
        }

        /*
         * 执行初始化操作
         * 1、Thread.ThreadLocalMap == null
         * 2、Thread.ThreadLocalMap.getEntry(this) == null
         */
        return setInitialValue();
    }

    /**
     * Removes the current thread's value for this thread-local
     * variable.  If this thread-local variable is subsequently
     * {@linkplain #get read} by the current thread, its value will be
     * reinitialized by invoking its {@link #initialValue} method,
     * unless its value is {@linkplain #set set} by the current thread
     * in the interim.  This may result in multiple invocations of the
     * {@code initialValue} method in the current thread.
     *
     * @since 1.5
     */
    public void remove()
    {
        ThreadLocalMap m = getMap(Thread.currentThread());
        // Thread.ThreadLocalMap 存在
        if (m != null)
        {
            m.remove(this);
        }
    }

    // ------------------------------------------------

    /**
     * Returns {@code true} if there is a value in the current thread's copy of
     * this thread-local variable, even if that values is {@code null}.
     *
     * @return {@code true} if current thread has associated value in this
     * thread-local variable; {@code false} if not
     */
    boolean isPresent()
    {
        // return Thread.currentThread().threadLocals.getEntry(this) != null
        Thread         t   = Thread.currentThread();
        ThreadLocalMap map = getMap(t);
        return map != null && map.getEntry(this) != null;
    }

    /**
     * Get the map associated with a ThreadLocal. Overridden in
     * InheritableThreadLocal.
     *
     * @param t the current thread
     * @return the map
     */
    ThreadLocalMap getMap(Thread t)
    {
        // return t.threadLocals;
        return null; // fixme 报错
    }

    /**
     * Create the map associated with a ThreadLocal. Overridden in
     * InheritableThreadLocal.
     *
     * @param t          the current thread
     * @param firstValue value for the initial entry of the map
     */
    void createMap(Thread t, T firstValue)
    {
        // t.threadLocals = new ThreadLocalMap(this, firstValue);
        new ThreadLocalMap(this, firstValue); // fixme 报错
    }

    /**
     * Factory method to create map of inherited thread locals.
     * Designed to be called only from Thread constructor.
     *
     * @param parentMap the map associated with parent thread
     * @return a map containing the parent's inheritable bindings
     */
    static ThreadLocalMap createInheritedMap(ThreadLocalMap parentMap)
    {
        return new ThreadLocalMap(parentMap);
    }

    /**
     * Method childValue is visibly defined in subclass
     * InheritableThreadLocal, but is internally defined here for the
     * sake of providing createInheritedMap factory method without
     * needing to subclass the map class in InheritableThreadLocal.
     * This technique is preferable to the alternative of embedding
     * instanceof tests in methods.
     */
    T childValue(T parentValue)
    {
        throw new UnsupportedOperationException();
    }

}
