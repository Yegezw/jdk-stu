package util.thread;

import java.lang.ref.WeakReference;

/**
 * ThreadLocalMap is a customized hash map suitable only for
 * maintaining thread local values. No operations are exported
 * outside of the ThreadLocal class. The class is package private to
 * allow declaration of fields in class Thread.  To help deal with
 * very large and long-lived usages, the hash table entries use
 * WeakReferences for keys. However, since reference queues are not
 * used, stale entries are guaranteed to be removed only when
 * the table starts running out of space.
 */
class ThreadLocalMap
{

    /**
     * The entries in this hash map extend WeakReference, using
     * its main ref field as the key (which is always a
     * ThreadLocal object).  Note that null keys (i.e. entry.get()
     * == null) mean that the key is no longer referenced, so the
     * entry can be expunged from table.  Such entries are referred to
     * as "stale entries" in the code that follows.
     */
    static class Entry extends WeakReference<ThreadLocal<?>>
    {
        /*
         * key 为弱引用
         * get()   函数可以返回 key
         * clear() 函数可以清除 key
         *
         * 当把 ThreadLocal 置 null 时, 它所对应 Thread.ThreadLocalMap.Entry 便无法找到了
         * 但在此之前如果没有调用 ThreadLocal.remove(), 那么 Entry 既无法被找到, 又无法被 GC 回收
         * 因此将 key 设为弱引用, 即使直接将 ThreadLocal 置 null, 也有一种兜底的机制让 ThreadLocalMap 给失效的 Entry 打标记
         *
         * ThreadLocalMap 的 set()、get()、remove() 都会主动清理 key 被 GC 的 Entry 数据
         *
         * 实际开发过程中, 最好在 finally 中调用 ThreadLocal.remove()
         */

        /**
         * The value associated with this ThreadLocal.
         */
        Object value;

        Entry(ThreadLocal<?> k, Object v)
        {
            super(k);
            value = v;
        }
    }

    // =================================================================================================================

    /**
     * The initial capacity -- MUST be a power of two.
     */
    private static final int INITIAL_CAPACITY = 16; // 默认 table 大小

    /**
     * The table, resized as necessary.
     * table.length MUST always be a power of two.
     */
    private Entry[] table;

    /**
     * The number of entries in the table.
     */
    private int size = 0;

    /**
     * The next size value at which to resize.
     */
    private int threshold; // 负载因子 Default to 0, table.length * 2 / 3 

    // ------------------------------------------------

    /**
     * Set the resize threshold to maintain at worst a 2/3 load factor.
     */
    private void setThreshold(int len)
    {
        threshold = len * 2 / 3;
    }

    /**
     * Increment i modulo len.
     */
    private static int nextIndex(int i, int len)
    {
        return ((i + 1 < len) ? i + 1 : 0);      // 循环队列 next
    }

    /**
     * Decrement i modulo len.
     */
    private static int prevIndex(int i, int len)
    {
        return ((i - 1 >= 0) ? i - 1 : len - 1); // 循环队列 prev
    }

    // =================================================================================================================

    /**
     * Construct a new map initially containing (firstKey, firstValue).
     * ThreadLocalMaps are constructed lazily, so we only create
     * one when we have at least one entry to put in it.
     */
    ThreadLocalMap(ThreadLocal<?> firstKey, Object firstValue)
    {
        table = new Entry[INITIAL_CAPACITY];
        int i = firstKey.threadLocalHashCode & (INITIAL_CAPACITY - 1);
        table[i] = new Entry(firstKey, firstValue);
        size     = 1;
        setThreshold(INITIAL_CAPACITY);
    }

    /**
     * Construct a new map including all Inheritable ThreadLocals
     * from given parent map. Called only by createInheritedMap.
     *
     * @param parentMap the map associated with parent thread.
     */
    @SuppressWarnings("all")
    ThreadLocalMap(ThreadLocalMap parentMap)
    {
        Entry[] parentTable = parentMap.table;
        int     len         = parentTable.length;
        setThreshold(len);
        table = new Entry[len];

        for (Entry e : parentTable)
        {
            if (e == null) continue;

            // key 为弱引用
            // 因此 key 可能为 null, 但 value 不为 null
            ThreadLocal<Object> key = (ThreadLocal<Object>) e.get();
            if (key == null) continue;

            // Entry( e.key, key.childValue(e.value) )
            Object value = key.childValue(e.value);
            Entry  c     = new Entry(key, value);
            int    h     = key.threadLocalHashCode & (len - 1);
            while (table[h] != null)
            {
                h = nextIndex(h, len);
            }
            table[h] = c;
            size++;
        }
    }

    // =================================================================================================================

    /**
     * Set the value associated with key.
     *
     * @param key   the thread local object
     * @param value the value to be set
     */
    void set(ThreadLocal<?> key, Object value)
    {
        // We don't use a fast path as with get() because it is at
        // least as common to use set() to create new entries as
        // it is to replace existing ones, in which case, a fast
        // path would fail more often than not.

        // 计算槽位
        Entry[] tab = table;
        int     len = tab.length;
        int     i   = key.threadLocalHashCode & (len - 1);

        // 哈希冲突: 开放寻址法
        for (Entry e = tab[i]; e != null; e = tab[i = nextIndex(i, len)])
        {
            // key 为弱引用
            // 因此 key 可能为 null, 但 value 不为 null
            ThreadLocal<?> k = e.get();

            // 覆盖 Entry.value
            if (k == key)
            {
                e.value = value;
                return;
            }

            // 弱引用 key 被 GC, 覆盖 table[i]
            if (k == null)
            {
                replaceStaleEntry(key, value, i);
                return;
            }

            // e.k != key 则 e = tab[i = nextIndex(i, len)]
        }

        // tab[i] == null
        tab[i] = new Entry(key, value);
        int sz = ++size;
        if (!cleanSomeSlots(i, sz) && sz >= threshold)
        {
            rehash();
        }
    }

    /**
     * Replace a stale entry encountered during a set operation
     * with an entry for the specified key.  The value passed in
     * the value parameter is stored in the entry, whether or not
     * an entry already exists for the specified key.
     * <p>
     * As a side effect, this method expunges all stale entries in the
     * "run" containing the stale entry.  (A run is a sequence of entries
     * between two null slots.)
     *
     * @param key       the key
     * @param value     the value to be associated with key
     * @param staleSlot index of the first stale entry encountered while
     *                  searching for key.
     */
    private void replaceStaleEntry(ThreadLocal<?> key, Object value, int staleSlot)
    {
        Entry[] tab = table;
        int     len = tab.length;
        Entry   e;

        // Back up to check for prior stale entry in current run.
        // We clean out whole runs at a time to avoid continual
        // incremental rehashing due to garbage collector freeing
        // up refs in bunches (i.e., whenever the collector runs).
        int slotToExpunge = staleSlot;
        for (int i = prevIndex(staleSlot, len);
             (e = tab[i]) != null;
             i = prevIndex(i, len))
        {
            if (e.get() == null)
            {
                slotToExpunge = i;
            }
        }

        // Find either the key or trailing null slot of run, whichever
        // occurs first
        for (int i = nextIndex(staleSlot, len);
             (e = tab[i]) != null;
             i = nextIndex(i, len))
        {
            ThreadLocal<?> k = e.get();

            // If we find key, then we need to swap it
            // with the stale entry to maintain hash table order.
            // The newly stale slot, or any other stale slot
            // encountered above it, can then be sent to expungeStaleEntry
            // to remove or rehash all of the other entries in run.
            if (k == key)
            {
                e.value = value;

                tab[i]         = tab[staleSlot];
                tab[staleSlot] = e;

                // Start expunge at preceding stale entry if it exists
                if (slotToExpunge == staleSlot)
                {
                    slotToExpunge = i;
                }
                cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
                return;
            }

            // If we didn't find stale entry on backward scan, the
            // first stale entry seen while scanning for key is the
            // first still present in the run.
            if (k == null && slotToExpunge == staleSlot)
            {
                slotToExpunge = i;
            }
        }

        // If key not found, put new entry in stale slot
        tab[staleSlot].value = null;
        tab[staleSlot]       = new Entry(key, value);

        // If there are any other stale entries in run, expunge them
        if (slotToExpunge != staleSlot)
        {
            cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
        }
    }

    /**
     * Re-pack and/or re-size the table. First scan the entire
     * table removing stale entries. If this doesn't sufficiently
     * shrink the size of the table, double the table size.
     */
    private void rehash()
    {
        expungeStaleEntries();

        // Use lower threshold for doubling to avoid hysteresis
        if (size >= threshold - threshold / 4)
        {
            resize();
        }
    }

    /**
     * Expunge all stale entries in the table.
     */
    private void expungeStaleEntries()
    {
        Entry[] tab = table;
        int     len = tab.length;
        for (int j = 0; j < len; j++)
        {
            Entry e = tab[j];
            if (e != null && e.get() == null)
            {
                expungeStaleEntry(j);
            }
        }
    }

    /**
     * Double the capacity of the table.
     */
    private void resize()
    {
        Entry[] oldTab = table;
        int     oldLen = oldTab.length;
        int     newLen = oldLen * 2;
        Entry[] newTab = new Entry[newLen];
        int     count  = 0;

        for (Entry e : oldTab)
        {
            if (e != null)
            {
                ThreadLocal<?> k = e.get();
                if (k == null)
                {
                    e.value = null; // Help the GC
                }
                else
                {
                    int h = k.threadLocalHashCode & (newLen - 1);
                    while (newTab[h] != null)
                    {
                        h = nextIndex(h, newLen);
                    }
                    newTab[h] = e;
                    count++;
                }
            }
        }

        setThreshold(newLen);
        size  = count;
        table = newTab;
    }

    // ------------------------------------------------

    /**
     * Get the entry associated with key.  This method
     * itself handles only the fast path: a direct hit of existing
     * key. It otherwise relays to getEntryAfterMiss.  This is
     * designed to maximize performance for direct hits, in part
     * by making this method readily inlinable.
     *
     * @param key the thread local object
     * @return the entry associated with key, or null if no such
     */
    Entry getEntry(ThreadLocal<?> key)
    {
        int   i = key.threadLocalHashCode & (table.length - 1);
        Entry e = table[i];
        if (e != null && e.get() == key)
        {
            return e;
        }
        // 哈希冲突: 开放寻址法
        else
        {
            return getEntryAfterMiss(key, i, e);
        }
    }

    /**
     * Version of getEntry method for use when key is not found in
     * its direct hash slot.
     *
     * @param key the thread local object
     * @param i   the table index for key's hash code
     * @param e   the entry at table[i]
     * @return the entry associated with key, or null if no such
     */
    private Entry getEntryAfterMiss(ThreadLocal<?> key, int i, Entry e)
    {
        Entry[] tab = table;
        int     len = tab.length;

        while (e != null)
        {
            ThreadLocal<?> k = e.get();
            if (k == key)
            {
                return e;
            }
            if (k == null)
            {
                expungeStaleEntry(i);
            }
            else
            {
                i = nextIndex(i, len);
            }
            e = tab[i];
        }

        return null;
    }

    // ------------------------------------------------

    /**
     * Remove the entry for key.
     */
    void remove(ThreadLocal<?> key)
    {
        Entry[] tab = table;
        int     len = tab.length;
        int     i   = key.threadLocalHashCode & (len - 1);
        for (Entry e = tab[i]; e != null; e = tab[i = nextIndex(i, len)])
        {
            if (e.get() == key)
            {
                e.clear();
                expungeStaleEntry(i);
                return;
            }
        }
    }

    /**
     * Expunge a stale entry by rehashing any possibly colliding entries
     * lying between staleSlot and the next null slot.  This also expunges
     * any other stale entries encountered before the trailing null.  See
     * Knuth, Section 6.4
     *
     * @param staleSlot index of slot known to have null key
     * @return the index of the next null slot after staleSlot
     * (all between staleSlot and this slot will have been checked
     * for expunging).
     */
    private int expungeStaleEntry(int staleSlot)
    {
        Entry[] tab = table;
        int     len = tab.length;

        // expunge entry at staleSlot
        tab[staleSlot].value = null;
        tab[staleSlot]       = null;
        size--;

        // Rehash until we encounter null
        Entry e;
        int   i;
        for (i = nextIndex(staleSlot, len); (e = tab[i]) != null; i = nextIndex(i, len))
        {
            ThreadLocal<?> k = e.get();
            if (k == null)
            {
                e.value = null;
                tab[i]  = null;
                size--;
            }
            else
            {
                int h = k.threadLocalHashCode & (len - 1);
                if (h != i)
                {
                    tab[i] = null;

                    // Unlike Knuth 6.4 Algorithm R, we must scan until
                    // null because multiple entries could have been stale.
                    while (tab[h] != null)
                    {
                        h = nextIndex(h, len);
                    }
                    tab[h] = e;
                }
            }
        }
        return i;
    }

    /**
     * Heuristically scan some cells looking for stale entries.
     * This is invoked when either a new element is added, or
     * another stale one has been expunged. It performs a
     * logarithmic number of scans, as a balance between no
     * scanning (fast but retains garbage) and a number of scans
     * proportional to number of elements, that would find all
     * garbage but would cause some insertions to take O(n) time.
     *
     * @param i a position known NOT to hold a stale entry. The
     *          scan starts at the element after i.
     * @param n scan control: {@code log2(n)} cells are scanned,
     *          unless a stale entry is found, in which case
     *          {@code log2(table.length)-1} additional cells are scanned.
     *          When called from insertions, this parameter is the number
     *          of elements, but when from replaceStaleEntry, it is the
     *          table length. (Note: all this could be changed to be either
     *          more or less aggressive by weighting n instead of just
     *          using straight log n. But this version is simple, fast, and
     *          seems to work well.)
     * @return true if any stale entries have been removed.
     */
    private boolean cleanSomeSlots(int i, int n)
    {
        // 清理过槽位 ?
        boolean removed = false;

        Entry[] tab = table;
        int     len = tab.length;
        do
        {
            i = nextIndex(i, len);
            Entry e = tab[i];
            // Entry.key == null
            if (e != null && e.get() == null)
            {
                n       = len;
                removed = true;
                i       = expungeStaleEntry(i);
            }
        }
        while ((n >>>= 1) != 0);

        return removed;
    }

}