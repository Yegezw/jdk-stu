package concurrent_collections;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 学习 ConcurrentHashMap 分段加锁
 */
@SuppressWarnings("all")
public class Test4 {

    private static void test1() {
        HashMap<Integer, Integer> map = new HashMap<>();
        map.put(1, 1);
        System.out.println(map.get(1));
    }

    private static void test2() {
        ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>();

        // ----------------------------------------------------

        // 增改: 写操作、扩容、树化
        // 除了树化与树化之间, 其它任意两个操作之间均存在线程安全问题, 因此这 3 个操作均使用 synchronized 进行了加锁

        // put() 写: 通过待插入数据的哈希值定位到链表 table[index] 之后
        // 如果链表为空(table[index] == null), 那么就通过 CAS 操作将 table[index] 指向写入数据对应的节点
        // 如果链表不为空, 那么就对链表的头节点(也就是 table[index])使用 synchronized 加锁, 然后再执行写操作

        // put() 树化: 在写入操作执行完成之后, 如果链表中的节点个数 >= 树化阈值(8)
        // 那么 put() 函数会执行树化操作, 对 table[index] 使用 synchronized 加锁

        // 扩容: 使用写时复制、复制替代搬移、多线程并发扩容
        // 将 oldTable 中的数据完全复制到 newTable 中之后, 才将 table 引用指向新创建的 newTable 数组(解决读与扩容)
        // 将 oldTable 节点中的 key、value 等数据, 复制一份存储在一个新创建的节点中, 再将新创建的节点插入到新的 newTable 中(解决读与扩容)

        // 扩容操作会针对 oldTable 中的每条链表逐一进行复制
        // 在复制某个链表之前, 先对这个链表加锁(类似写操作和树化的加锁方式)然后再复制, 复制完成之后再解锁
        // 在扩容的过程中 oldTable 中会存在三种不同类型的链表: 已复制未加锁链表、在复制已加锁链表、未复制未加锁链表
        // 我们需要对已复制未加锁的链表做标记: 当对已标记的链表进行读、写、树化操作时, 引导在新创建的 newTable 数组中执行
        // 当某个链表复制完成之后, 会将这个链表首节点替换为 ForwardingNode 节点, 并且节点中的 nextTable 属性指向 newTable
        // 对于空链表, ConcurrentHashMap 会补充一个 key、value 均为 null 的 ForwardingNode 节点
        // 当读、写、树化 table 数组中的某个链表时, 先检查链表首节点的 hash 值, 如果 hash 值等于 -1
        // 那么就在这个节点的 nextTable 属性所指向的 table 数组中重新查找对应的链表, 再执行读、写、树化操作

        // 多线程并发扩容
        // transferIndex(转移索引)初始化为 oldTable.length, stride(默认 16)
        // 多个线程通过 CAS 修改 transferIndex 共享变量为 transferIndex - stride
        // 谁成功更新 transferIndex, 谁就获取了下标在 [transferIndex - stride, transferIndex) 之间的 stride(大步走)个链表的复制权
        // 某个线程处理完分配的 stride 个链表之后, 可以再次自旋执行 CAS, 竞争剩余链表的复制权
        // ConcurrentHashMap 中的定义了一个 int 类型的共享变量 sizeCtl
        // 用来标记当前正在参与扩容的线程个数, 参与扩容 + 1, 复制完成 - 1
        // 当 sizeCtl = 0 时, 就表示这个线程就是最后一个线程, 负责将 table 引用更新为指向新的 newTable 数组

        map.put(1, 1);
        map.put(2, 2);
        map.put(3, 3);

        // ----------------------------------------------------

        // 删
        map.remove(1);

        // ----------------------------------------------------

        // 查
        // 没加锁, HashMap 中读与扩容有线程安全问题
        // 因此 ConcurrentHashMap 扩容需要做一些特殊处理, 以兼容并行执行读操作
        // Node: volatile val、volatile next
        int num = map.get(1);
        System.out.println(num);

        // ----------------------------------------------------

        // 类似 LongAddr, size 并不准确
        int size = map.size();
        System.out.println(size);
    }
}
