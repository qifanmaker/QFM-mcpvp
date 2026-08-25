package com.example.pvp.util;

import java.util.Collection;
import java.util.Iterator;

/**
 * 基于快照数组的迭代器，防止在遍历 world 集合时修改集合导致 ConcurrentModificationException。
 */
public final class SafeIterator<T> implements Iterator<T> {
    private final Object[] values;
    private int index = 0;

    public SafeIterator(Collection<T> source) {
        this.values = source.toArray();
    }

    @Override
    public boolean hasNext() {
        return this.values.length > this.index;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T next() {
        return (T) this.values[this.index++];
    }
}
