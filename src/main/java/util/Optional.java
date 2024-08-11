package util;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

@SuppressWarnings("all")
public final class Optional<T>
{

    private final T value;

    private Optional()
    {
        this.value = null;
    }

    private Optional(T value)
    {
        this.value = Objects.requireNonNull(value);
    }

    // ------------------------------------------------

    private static final Optional<?> EMPTY = new Optional<>();

    public static <T> Optional<T> empty()
    {
        Optional<T> t = (Optional<T>) EMPTY;
        return t;
    }

    /**
     * value 不能为 null
     */
    public static <T> Optional<T> of(T value)
    {
        return new Optional<>(value);
    }

    /**
     * value 可以为 null
     */
    public static <T> Optional<T> ofNullable(T value)
    {
        return value == null ? empty() : of(value);
    }

    // =================================================================================================================

    /**
     * 获取 value, 可能会抛出异常
     */
    public T get()
    {
        if (value == null)
        {
            throw new NoSuchElementException("No value present");
        }
        return value;
    }

    /**
     * 是否存在 value
     */
    public boolean isPresent()
    {
        return value != null;
    }

    /**
     * 存在 value 则 consumer.accept(value)
     */
    public void ifPresent(Consumer<? super T> consumer)
    {
        if (value != null)
        {
            consumer.accept(value);
        }
    }

    // ------------------------------------------------

    /**
     * predicate.test(value) ? this : empty()
     */
    public Optional<T> filter(Predicate<? super T> predicate)
    {
        Objects.requireNonNull(predicate);
        if (!isPresent())
        {
            return this;
        }
        else
        {
            return predicate.test(value) ? this : empty();
        }
    }

    /**
     * T -> U: Optional.ofNullable( mapper.apply(value) )
     */
    public <U> Optional<U> map(Function<? super T, ? extends U> mapper)
    {
        Objects.requireNonNull(mapper);
        if (!isPresent())
        {
            return empty();
        }
        else
        {
            return Optional.ofNullable(mapper.apply(value));
        }
    }

    /**
     * T -> Optional[U]: Objects.requireNonNull( mapper.apply(value) )
     */
    public <U> Optional<U> flatMap(Function<? super T, Optional<U>> mapper)
    {
        Objects.requireNonNull(mapper);
        if (!isPresent())
        {
            return empty();
        }
        else
        {
            return Objects.requireNonNull(mapper.apply(value));
        }
    }

    // =================================================================================================================

    /**
     * value 为空则为 other
     */
    public T orElse(T other)
    {
        return value != null ? value : other;
    }

    /**
     * value 为空则为 other.get()
     */
    public T orElseGet(Supplier<? extends T> other)
    {
        return value != null ? value : other.get();
    }

    /**
     * value 为空则为 throw exceptionSupplier.get()
     */
    public <X extends Throwable> T orElseThrow(Supplier<? extends X> exceptionSupplier) throws X
    {
        if (value != null)
        {
            return value;
        }
        else
        {
            throw exceptionSupplier.get();
        }
    }

    // ------------------------------------------------

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }

        if (!(obj instanceof Optional))
        {
            return false;
        }

        Optional<?> other = (Optional<?>) obj;
        return Objects.equals(value, other.value);
    }

    @Override
    public int hashCode()
    {
        return Objects.hashCode(value);
    }

    @Override
    public String toString()
    {
        return value != null
                ? String.format("Optional[%s]", value)
                : "Optional.empty";
    }
}
