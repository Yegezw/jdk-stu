package other;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

/**
 * ReferenceQueue + WeakReference
 */
public class ReferenceTest
{

    @SuppressWarnings("all")
    public static void main(String[] args) throws InterruptedException
    {
        // 创建引用队列
        ReferenceQueue<Object> queue = new ReferenceQueue<>();

        // 创建对象及其弱引用
        Student                student = new Student("张三");
        WeakReference<Student> weakRef = new WeakReference<>(student, queue);

        // 触发 GC
        student = null;
        System.gc();

        // 检查队列: 若 weakRef 进入队列, 说明对象已被回收
        Reference<?> clearedRef = queue.remove(1000); // 阻塞等待最多 1 秒
        if (clearedRef != null)
        {
            System.out.println(clearedRef == weakRef);
            System.out.println(clearedRef.get());
        }
    }

    private static class Student
    {
        String name;

        public Student(String name)
        {
            this.name = name;
        }
    }
}
