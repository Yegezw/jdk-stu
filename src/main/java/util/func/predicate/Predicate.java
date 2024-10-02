package util.func.predicate;

import java.util.Objects;

@FunctionalInterface
public interface Predicate<T>
{
    boolean test(T t);

    static <T> Predicate<T> isEqual(Object targetRef)
    {
        return (null == targetRef)
                ? Objects::isNull
                : targetRef::equals;
    }

    /**
     * 与 &&
     */
    default Predicate<T> and(Predicate<? super T> other)
    {
        Objects.requireNonNull(other);
        return (t) -> test(t) && other.test(t);
    }

    /**
     * 或 ||
     */
    default Predicate<T> or(Predicate<? super T> other)
    {
        Objects.requireNonNull(other);
        return (t) -> test(t) || other.test(t);
    }

    /**
     * 非 !
     */
    default Predicate<T> negate()
    {
        return (t) -> !test(t);
    }
}
