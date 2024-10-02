package util.func.function;

import java.util.Objects;

@FunctionalInterface
public interface BiFunction<T, U, R>
{
    R apply(T t, U u);

    /**
     * (T + U) -> R -> V
     */
    default <V> BiFunction<T, U, V> andThen(Function<? super R, ? extends V> after)
    {
        Objects.requireNonNull(after);
        return (T t, U u) -> after.apply(apply(t, u));
    }
}
