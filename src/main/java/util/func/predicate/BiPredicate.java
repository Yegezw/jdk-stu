package util.func.predicate;

import java.util.Objects;

@FunctionalInterface
public interface BiPredicate<T, U>
{
    boolean test(T t, U u);

    /**
     * 与 &&
     */
    default BiPredicate<T, U> and(BiPredicate<? super T, ? super U> other)
    {
        Objects.requireNonNull(other);
        return (T t, U u) -> test(t, u) && other.test(t, u);
    }

    /**
     * 或 ||
     */
    default BiPredicate<T, U> or(BiPredicate<? super T, ? super U> other)
    {
        Objects.requireNonNull(other);
        return (T t, U u) -> test(t, u) || other.test(t, u);
    }

    /**
     * 非 !
     */
    default BiPredicate<T, U> negate()
    {
        return (T t, U u) -> !test(t, u);
    }
}
