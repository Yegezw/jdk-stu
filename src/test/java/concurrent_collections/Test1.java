package concurrent_collections;

import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 并发容器 CopyOnWriteArrayList、ArrayBlockingQueue
 */
@SuppressWarnings("all")
public class Test1 {

    /**
     * 写时复制: 适用于读多写少
     */
    private static void test1() {
        // CopyOnWriteArraySet 是基于 CopyOnWriteArrayList 实现的
        CopyOnWriteArrayList<Integer> list = new CopyOnWriteArrayList<>();

        // 写加锁
        list.add(1);
        list.add(2);
        list.add(3);

        // 删加锁
        list.remove(0);

        // 改加锁
        list.set(0, 0);

        // 查不加锁
        int num = list.get(0);
        System.out.println(num);

        boolean res = list.contains(1);

        // 写时复制只能保证弱一致性, 普通的遍历方式可能导致重复遍历
        // 必须使用迭代器遍历
        int               sum      = 0;
        Iterator<Integer> iterator = list.iterator();
        while (iterator.hasNext()) {
            sum += iterator.next();
        }
        System.out.println(sum);
    }

    /**
     * 阻塞并发队列
     */
    private static void test2() throws InterruptedException {
        // 基于 ReentrantLock 锁来实现线程安全
        // 基于 Condition 条件变量来实现阻塞等待
        // ArrayBlockingQueue 是基于 "数组" 实现的 "有界阻塞并发队列(循环队列)"
        ArrayBlockingQueue<Integer> queue = new ArrayBlockingQueue<Integer>(100);

        // 支持阻塞写的入队
        queue.put(1);

        // 支持阻塞读的出队
        int num = queue.take();
        System.out.println(num);

        // size() 也加锁了
        int size = queue.size();
        System.out.println(size);

        // 抛出异常    add(e)    remove()
        // 一直阻塞    put(e)    take()
        // 超时退出    offer(e)、offer(e, time, unit)    poll()、poll(time, unit)
    }
}
