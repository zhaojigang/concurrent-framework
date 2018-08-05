package io.hulk.fast.thread.framework;

import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * from netty4.1
 */
public class InternalThreadLocalMap {
    /**
     * FastThreadLocal的唯一索引生成器
     */
    private static final AtomicInteger                       nextIndex          = new AtomicInteger();
    /**
     * InternalThreadLocalMap的底层数据结构
     * 其index就是FastThreadLocal的唯一索引index，
     * value是相对应的FastThreadLocal所要存储的值
     */
    private Object[]                                         indexedVariables;
    /**
     * 无效的value值（占位符），不使用null做无效值的原因是因为netty认为null也是一个有效值，
     * 例如：假设没有重写FastThreadLocal的initialValue()方法，则该方法返回为null，netty会将null作为有效值直接存储起来
     */
    public static final Object                               UNSET              = new Object();
    /**
     * 兼容非FastThreadLocalThread
     */
    private static final ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap = new ThreadLocal<>();
    /**
     * BitSet简要原理：
     * BitSet默认底层数据结构是一个long[]数组，开始时长度为1，即只有long[0],而一个long有64bit。
     * 当BitSet.set(1)的时候，表示将long[0]的第二位设置为true，即0000 0000 ... 0010（64bit）,则long[0]==2
     * 当BitSet.get(1)的时候，第二位为1，则表示true；如果是0，则表示false
     * 当BitSet.set(64)的时候，表示设置第65位，此时long[0]已经不够用了，扩容处long[1]来，进行存储
     *
     * 存储类似 {index:boolean} 键值对，用于防止一个FastThreadLocal多次启动清理线程
     * 将index位置的bit设为true，表示该InternalThreadLocalMap中对该FastThreadLocal已经启动了清理线程
     */
    private BitSet                                           cleanerFlags;

    /**
     * 创建indexedVariables数组，并将每一个元素初始化为UNSET
     */
    public InternalThreadLocalMap() {
        indexedVariables = new Object[32];
        Arrays.fill(indexedVariables, UNSET);
    }

    /**
     * 获取FastThreadLocal的唯一索引
     */
    public static Integer nextVariableIndex() {
        Integer index = nextIndex.getAndIncrement();
        if (index < 0) {
            nextIndex.decrementAndGet();
            throw new IllegalStateException("too many thread-local variable index");
        }
        return index;
    }

    /**
     * 获取InternalThreadLocalMap实例
     */
    public static InternalThreadLocalMap get() {
        Thread current = Thread.currentThread();
        if (current instanceof FastThreadLocalThread) {
            return fastGet((FastThreadLocalThread) current);
        }
        return slowGet();
    }

    /**
     * 获取InternalThreadLocalMap实例，如果为null，则直接返回，不会创建；如果不为null，也直接返回
     */
    public static InternalThreadLocalMap getIfSet() {
        Thread current = Thread.currentThread();
        if (current instanceof FastThreadLocalThread) {
            return ((FastThreadLocalThread) current).threadLocalMap();
        } else {
            return slowThreadLocalMap.get();
        }
    }

    private static InternalThreadLocalMap fastGet(FastThreadLocalThread current) {
        InternalThreadLocalMap threadLocalMap = current.threadLocalMap();
        if (threadLocalMap == null) {
            threadLocalMap = new InternalThreadLocalMap();
            current.setThreadLocalMap(threadLocalMap);
        }
        return threadLocalMap;
    }

    private static InternalThreadLocalMap slowGet() {
        InternalThreadLocalMap threadLocalMap = slowThreadLocalMap.get();
        if (threadLocalMap == null) {
            threadLocalMap = new InternalThreadLocalMap();
            slowThreadLocalMap.set(threadLocalMap);
        }
        return threadLocalMap;
    }

    /**
     * 设置值
     */
    public boolean setIndexedVariables(int index, Object value) {
        Object[] lookup = indexedVariables;
        if (index < lookup.length) {
            Object oldValue = lookup[index];
            lookup[index] = value;
            return oldValue == UNSET;
        } else {
            expandIndexedVariables(index, value);
            return true;
        }
    }

    /**
     * 获取指定位置的元素
     */
    public Object indexedVariable(int index) {
        Object[] lookup = indexedVariables;
        return index < lookup.length ? lookup[index] : UNSET;
    }

    /**
     * 删除指定位置的对象
     */
    public Object removeIndexedVariable(int index) {
        Object[] lookup = indexedVariables;
        if (index < lookup.length) {
            // 1、获取旧值
            Object v = lookup[index];
            // 2、设置为UNSET
            lookup[index] = UNSET;
            // 3、返回旧值
            return v;
        } else {
            return UNSET;
        }
    }

    /**
     * 删除当前线程的InternalThreadLocalMap
     */
    public void remove() {
        Thread current = Thread.currentThread();
        if (current instanceof FastThreadLocalThread) {
            ((FastThreadLocalThread) current).setThreadLocalMap(null);
        } else {
            slowThreadLocalMap.remove();
        }
    }

    private void expandIndexedVariables(int index, Object value) {
        Object[] oldArray = indexedVariables;
        int oldCapacity = oldArray.length;
        /**
         * 计算新数组容量：获取>index的最小的2的n次方的数，例如：1->2 2->4 3->4 4->8 5->8 6->8 7->8 8->16
         * Returns a power of two size for the given target capacity.
         * <pre>
         *       
         * {@link java.util.HashMap#tableSizeFor(int)}
         * static final int tableSizeFor(int cap) {
         *   int n = cap - 1;
         *   n |= n >>> 1;
         *   n |= n >>> 2;
         *   n |= n >>> 4;
         *   n |= n >>> 8;
         *   n |= n >>> 16;
         *   return (n < 0) ? 1 : (n >= MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : n + 1;
         * }
         * </pre>
         */
        int newCapacity = index;
        newCapacity |= newCapacity >>> 1;
        newCapacity |= newCapacity >>> 2;
        newCapacity |= newCapacity >>> 4;
        newCapacity |= newCapacity >>> 8;
        newCapacity |= newCapacity >>> 16;
        newCapacity++;
        /**
         * 创建新数组并拷贝旧数组的元素到新数组
         */
        Object[] newArray = Arrays.copyOf(oldArray, newCapacity);
        /**
         * 初始化扩容出来的部分的元素
         */
        Arrays.fill(newArray, oldCapacity, newCapacity, UNSET);
        /**
         * 设置变量
         */
        newArray[index] = value;
        /**
         * 将新数组设置给成员变量
         */
        indexedVariables = newArray;
    }

    /**
     * 设置当前索引位置index（FastThreadLocal）的bit为1
     */
    public void setCleanerFlags(int index) {
        if (cleanerFlags == null) {
            cleanerFlags = new BitSet();
        }
        cleanerFlags.set(index);
    }

    /**
     * 获取 当前index的bit值，1表示true，0表示false（默认值）
     */
    public boolean isCleanerFlags(int index) {
        return cleanerFlags != null && cleanerFlags.get(index);
    }

}
