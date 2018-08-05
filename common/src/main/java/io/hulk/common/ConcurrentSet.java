package io.hulk.common;

import java.io.Serializable;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * from netty4.1
 * 基于ConcurrentHashMap实现的支持并发的Set（实际上就是使用map的set，原理类比：HashSet和HashMap），所以在mina中该类被称为ConcurrentHashSet
 */
public class ConcurrentSet<E> extends AbstractSet<E> implements Serializable {
    private static final long serialVersionUID = -1244664838594578508L;
    /**
     * 基本数据结构
     */
    private ConcurrentMap<E, Boolean> map;

    /**
     * 创建ConcurrentSet实例
     * 并初始化内部的ConcurrentHashMap
     */
    public ConcurrentSet() {
        map = new ConcurrentHashMap<>();
    }


    @Override
    public boolean contains(Object o) {
        return map.containsKey(o);
    }

    @Override
    public boolean add(E e) {
        return map.putIfAbsent(e, Boolean.TRUE) == null;
    }

    @Override
    public boolean remove(Object o) {
        return map.remove(o);
    }

    @Override
    public Iterator<E> iterator() {
        return map.keySet().iterator();
    }

    @Override
    public int size() {
        return map.size();
    }
}
