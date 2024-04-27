package util;

import java.util.Objects;

public interface Consumer<T>
{

    void accept(T t);

    default Consumer<T> andThen(Consumer<? super T> after)
    {
        Objects.requireNonNull(after);
        return new Consumer<T>()
        {
            @Override
            public void accept(T t)
            {
                Consumer.this.accept(t);
                after.accept(t);
            }
        };
    }
}
