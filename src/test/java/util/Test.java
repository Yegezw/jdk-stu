package util;

import util.func.consumer.Consumer;

public class Test
{

    public static void main(String[] args)
    {
        Consumer<String> consumer1 = new Consumer<String>()
        {
            @Override
            public void accept(String s)
            {
                System.out.println(s.length());
            }
        };
        Consumer<String> consumer2 = new Consumer<String>()
        {
            @Override
            public void accept(String s)
            {
                System.out.println(s);
            }
        };

        Consumer<String> consumer3 = consumer1.andThen(consumer2);
        consumer3.accept("apple");
    }
}
