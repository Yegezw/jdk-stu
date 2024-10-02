package util;

import util.func.consumer.Consumer;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

@SuppressWarnings("all")
public final class Optional<T>
{

    private final T value;

    private Optional(T value)
    {
        this.value = value;
    }

    // ------------------------------------------------

    private static final Optional<?> EMPTY = new Optional<>(null);

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
        return new Optional<>(Objects.requireNonNull(value));
    }

    /**
     * value 可以为 null
     */
    public static <T> Optional<T> ofNullable(T value)
    {
        return value == null ? (Optional<T>) EMPTY : new Optional<>(value);
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

    public Stream<T> stream()
    {
        if (isEmpty())
        {
            return Stream.empty();
        }
        else
        {
            return Stream.of(value);
        }
    }

    /**
     * 是否为空
     */
    public boolean isEmpty()
    {
        return value == null;
    }

    /**
     * 是否存在 value
     */
    public boolean isPresent()
    {
        return value != null;
    }

    /**
     * 存在 value 则 action.accept(value)
     */
    public void ifPresent(Consumer<? super T> action)
    {
        if (value != null)
        {
            action.accept(value);
        }
    }

    /**
     * 存在 value 则 action.accept(value), 否则 emptyAction.run()
     */
    public void ifPresentOrElse(Consumer<? super T> action, Runnable emptyAction)
    {
        if (value != null)
        {
            action.accept(value);
        }
        else
        {
            emptyAction.run();
        }
    }

    // ------------------------------------------------

    /**
     * predicate.test(value) ? this : empty()
     */
    public Optional<T> filter(Predicate<? super T> predicate)
    {
        Objects.requireNonNull(predicate);
        if (isEmpty())
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
        if (isEmpty())
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
        if (isEmpty())
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
     * 不存在 value 则 supplier.get()
     */
    public Optional<T> or(Supplier<? extends Optional<? extends T>> supplier)
    {
        Objects.requireNonNull(supplier);
        if (isPresent())
        {
            return this;
        }
        else
        {
            Optional<T> r = (Optional<T>) supplier.get();
            return Objects.requireNonNull(r);
        }
    }

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
     * value 为空则为 throw new NoSuchElementException("No value present")
     */
    public T orElseThrow()
    {
        if (value == null)
        {
            throw new NoSuchElementException("No value present");
        }
        return value;
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
