package util.func.function;

import java.util.Objects;

@FunctionalInterface
public interface Function<T, R>
{
    /**
     * T -> R
     */
    R apply(T t);

    /**
     * T -> T
     */
    static <T> Function<T, T> identity()
    {
        return t -> t;
    }

    /**
     * V -> T -> R
     */
    default <V> Function<V, R> compose(Function<? super V, ? extends T> before)
    {
        Objects.requireNonNull(before);
        return (V v) -> apply(before.apply(v));
    }

    /**
     * T -> R -> V
     */
    default <V> Function<T, V> andThen(Function<? super R, ? extends V> after)
    {
        Objects.requireNonNull(after);
        return (T t) -> after.apply(apply(t));
    }
}
