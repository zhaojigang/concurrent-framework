package io.hulk.fast.thread.framework;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * from netty4.1
 */
public class FastThreadLocal<V> {
    /**
     * 每一个FastThreadLocal都有一个唯一标识
     */
    private final int       index;

    /**
     * 每一个FastThreadLocal类都会将自己添加到indexedVariables[variablesToRemoveIndex]处的Set<FastThreaLocal<?>>
     */
    private static final int VARIABLES_TO_REMOVE_INDEX = InternalThreadLocalMap.nextVariableIndex();

    /**
     * 创建一个FastThreadLocal
     */
    public FastThreadLocal() {
        index = InternalThreadLocalMap.nextVariableIndex();
    }

    /**
     * 设置一个value
     */
    public void set(V value) {
        // 1、如果value是UNSET，表示删除当前的ThreadLocal对应的value；
        // 如果不是UNSET，则可能是修改，也可能是新增；
        // 如果是修改，修改value结束后返回，
        // 如果是新增，则先新增value，然后新增ThreadLocal到Set中，最后注册Cleaner清除线程
        if (value != InternalThreadLocalMap.UNSET) {
            InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
            if (setKnownNotUnset(threadLocalMap, value)) {
                registerCleaner(threadLocalMap);
            }
        } else {
            // 如果设置的值是UNSET，表示清除该FastThreadLocal的value
            remove();
        }
    }

    /**
     * 获取当前线程的InternalThreadLocalMap中的当前ftl的value
     */
    public V get() {
        // 1、获取InternalThreadLocalMap
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
        // 2、从InternalThreadLocalMap获取索引为index的value，如果该索引处的value是有效值，不是占位值，则直接返回
        Object value = threadLocalMap.indexedVariable(index);
        if (value != InternalThreadLocalMap.UNSET) {
            return (V) value;
        }
        // 3、indexedVariables[index]没有设置有效值，执行初始化操作，获取初始值
        V initialValue = initialize(threadLocalMap);
        // 4、注册资源清理器：当该ftl所在的线程挂掉时，清理其上当前ftl的value和set<FastThreadLocal<?>>中当前的ftl
        registerCleaner(threadLocalMap);
        return initialValue;
    }

    /**
     * 清除当前的ThreadLocal
     */
    private void remove() {
        remove(InternalThreadLocalMap.getIfSet());
    }

    private void remove(InternalThreadLocalMap threadLocalMap) {
        if (threadLocalMap == null) {
            return;
        }
        // 1、从 InternalThreadLocalMap 中删除当前的FastThreadLocal对应的value
        Object v = threadLocalMap.removeIndexedVariable(index);
        // 2、从 InternalThreadLocalMap 中的Set<FastThreadLocal<?>>中删除当前的FastThreadLocal
        removeFromVariablesToRemove(threadLocalMap, this);
        // 3、如果删除的是有效值，则进行onRemove方法的回调
        if (v != InternalThreadLocalMap.UNSET) {
            try {
                onRemoved((V) v);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 删除当前线程上的InternalThreadLocalMap中的每一个value以及threadLocalMap本身
     */
    public static void removeAll() {
        // 1、获取当前线程的InternalThreadLocalMap，如果当前的InternalThreadLocalMap为null，则直接返回
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap == null) {
            return;
        }
        try {
            // 2、从indexedVariables[VARIABLES_TO_REMOVE_INDEX]获取目前InternalThreadLocalMap存储的有效的FastThreadLocal的值，之后遍历Set，进行remove操作
            // 注意：这也是为什么我们会将有效的FastThreadLocal存储在一个Set中的原因（另外，如果没有Set<FastThreadLocal<?>>这个集合的话，我们需要直接去遍历整个indexedVariables数组，可能其中有效的并不多，影响效率）
            Object v = threadLocalMap.indexedVariable(VARIABLES_TO_REMOVE_INDEX);
            if (v != null && v != InternalThreadLocalMap.UNSET) {
                Set<FastThreadLocal<?>> threadLocals = (Set<FastThreadLocal<?>>) v;
                for (FastThreadLocal<?> threadLocal : threadLocals) {
                    threadLocal.remove();
                }
            }
        } finally {
            // 3、删除当前线程的InternalThreadLocalMap
            threadLocalMap.remove();
        }
    }

    /**
     * 返回true：如果是新添加了一个value；
     * 返回false：如果是修改了一个value。
     */
    private boolean setKnownNotUnset(InternalThreadLocalMap threadLocalMap, V value) {
        // 新增value
        if (threadLocalMap.setIndexedVariables(index, value)) {
            addToVariablesToRemove(threadLocalMap, this);
            return true;
        }
        // 修改value
        return false;
    }

    /**
     * 注册资源清理器：当该ftl所在的线程不可达时，清理其上当前ftl的value和set<FastThreadLocal<?>>中当前的ftl
     */
    private void registerCleaner(InternalThreadLocalMap threadLocalMap) {
        Thread current = Thread.currentThread();
        // 如果已经开启了自动清理功能 或者 已经对threadLocalMap中当前的FastThreadLocal的开启了清理线程
        if (FastThreadLocalThread.willCleanupFastThreadLocals(current) || threadLocalMap.isCleanerFlags(index)) {
            return;
        }
        // 设置是否已经开启了对当前的FastThreadLocal清理线程的标志
        threadLocalMap.setCleanerFlags(index);
        // 将当前线程和清理任务注册到ObjectCleaner上去
        ObjectCleaner.register(current, () -> remove(threadLocalMap));
    }

    /**
     * 初始化value
     */
    private V initialize(InternalThreadLocalMap threadLocalMap) {
        V v = null;
        try {
            //1、获取初始值
            v = initialValue();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // 2、设置value到InternalThreadLocalMap中
        threadLocalMap.setIndexedVariables(index, v);
        // 3、添加当前的FastThreadLocal到InternalThreadLocalMap的Set<FastThreadLocal<?>>中
        addToVariablesToRemove(threadLocalMap, this);

        return v;
    }

    /**
     * 将当前的FastThreadLocal添加到indexedVariables[variablesToRemoveIndex]位置上的Set<FastThreadLocal<?>>中
     */
    private void addToVariablesToRemove(InternalThreadLocalMap threadLocalMap, FastThreadLocal<V> threadLocal) {
        // 1、首先从InternalThreadLocalMap获取Set，如果存在，直接往Set里添加值FastThreadLocal；
        // 如果不存在，则先创建一个Set，然后将创建的Set添加到InternalThreadLocalMap中，最后将FastThreadLocal添加到这个Set中
        Object v = threadLocalMap.indexedVariable(VARIABLES_TO_REMOVE_INDEX);
        // Set中的FastThreadLocal可能有多个类型，所以此处的泛型使用?，而不是用指定的V
        Set<FastThreadLocal<?>> variablesToRemove;
        if (v == InternalThreadLocalMap.UNSET) {
            variablesToRemove = Collections.newSetFromMap(new IdentityHashMap<FastThreadLocal<?>, Boolean>());
            threadLocalMap.setIndexedVariables(VARIABLES_TO_REMOVE_INDEX, variablesToRemove);
        } else {
            variablesToRemove = (Set<FastThreadLocal<?>>) v;
        }
        variablesToRemove.add(threadLocal);
    }

    private void removeFromVariablesToRemove(InternalThreadLocalMap threadLocalMap, FastThreadLocal<V> threadLocal) {
        Object v = threadLocalMap.indexedVariable(VARIABLES_TO_REMOVE_INDEX);
        if (v == InternalThreadLocalMap.UNSET || v == null) {
            return;
        }
        Set<FastThreadLocal<?>> variablesToRemove = (Set<FastThreadLocal<?>>) v;
        variablesToRemove.remove(threadLocal);
    }

    public final boolean isSet(){
        return isSet(InternalThreadLocalMap.getIfSet());
    }

    private final boolean isSet(InternalThreadLocalMap threadLocalMap) {
        return threadLocalMap != null && threadLocalMap.isIndexedVariableSet(index);
    }

    /**
     * 初始化参数：由子类复写
     */
    protected V initialValue() throws Exception {
        return null;
    }

    /**
     * 当前的threadLocal被删除后的回调：由子类复写
     */
    protected void onRemoved(V value) throws Exception {

    }

}
