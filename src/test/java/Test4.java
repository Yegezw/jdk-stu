import java.util.concurrent.atomic.AtomicStampedReference;

/**
 * 观察 AtomicStampedReference 如何解决 ABA 问题
 */
public class Test4 {

    private static class Person {
        int age;
        String name;

        public Person(int age, String name) {
            this.age = age;
            this.name = name;
        }
    }

    public static void main(String[] args) {
        Person p1 = new Person(1, "张三"); // oldPerson
        Person p2 = new Person(2, "李四"); // newPerson

        AtomicStampedReference<Person> reference = new AtomicStampedReference<>(p1, 0);

        int oldStamp = reference.getStamp();
        Person oldPerson = reference.getReference();
        reference.compareAndSet(oldPerson, p2, oldStamp, oldStamp + 1); // 查看 ABA 如何解决
    }
}