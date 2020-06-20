package org.mos.mcore.tools.bytes;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class BytesSetAdapter<E, V> implements Set<E> {
    private static final Object DummyValue = new Object();
    Map<E, V> delegate;

    public BytesSetAdapter(Map<E, V> delegate) {
        this.delegate = (Map<E, V>) delegate;
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return delegate.containsKey(o);
    }

    @Override
    public Iterator<E> iterator() {
        return delegate.keySet().iterator();
    }

    @Override
    public Object[] toArray() {
        return delegate.keySet().toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return delegate.keySet().toArray(a);
    }

    @Override
    public boolean add(E e) {
        return delegate.put(e, (V)DummyValue) == null;
    }

    @Override
    public boolean remove(Object o) {
        return delegate.remove(o) != null;
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return delegate.keySet().containsAll(c);
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        boolean ret = false;
        for (E e : c) {
            ret |= add(e);
        }
        return ret;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        throw new RuntimeException("Not implemented"); // TODO add later if required
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        boolean ret = false;
        for (Object e : c) {
            ret |= remove(e);
        }
        return ret;
    }

    @Override
    public void clear() {
        delegate.clear();
    }
}

