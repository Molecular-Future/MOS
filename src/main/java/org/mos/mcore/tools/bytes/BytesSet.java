package org.mos.mcore.tools.bytes;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class BytesSet implements Set<byte[]> {
	Set<BytesKey> delegate;

	public BytesSet() {
		this(new HashSet<BytesKey>());
	}

	BytesSet(Set<BytesKey> delegate) {
		this.delegate = delegate;
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
		return delegate.contains(new BytesKey((byte[]) o));
	}

	@Override
	public Iterator<byte[]> iterator() {
		return new Iterator<byte[]>() {

			Iterator<BytesKey> it = delegate.iterator();

			@Override
			public boolean hasNext() {
				return it.hasNext();
			}

			@Override
			public byte[] next() {
				return it.next().getData();
			}

			@Override
			public void remove() {
				it.remove();
			}
		};
	}

	@Override
	public Object[] toArray() {
		byte[][] ret = new byte[size()][];

		BytesKey[] arr = delegate.toArray(new BytesKey[size()]);
		for (int i = 0; i < arr.length; i++) {
			ret[i] = arr[i].getData();
		}
		return ret;
	}

	@Override
	public <T> T[] toArray(T[] a) {
		return (T[]) toArray();
	}

	@Override
	public boolean add(byte[] bytes) {
		return delegate.add(new BytesKey(bytes));
	}

	@Override
	public boolean remove(Object o) {
		return delegate.remove(new BytesKey((byte[]) o));
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		throw new RuntimeException("Not implemented");
	}

	@Override
	public boolean addAll(Collection<? extends byte[]> c) {
		boolean ret = false;
		for (byte[] bytes : c) {
			ret |= add(bytes);
		}
		return ret;
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		throw new RuntimeException("Not implemented");
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		boolean changed = false;
		for (Object el : c) {
			changed |= remove(el);
		}
		return changed;
	}

	@Override
	public void clear() {
		delegate.clear();
	}

	@Override
	public boolean equals(Object o) {
		throw new RuntimeException("Not implemented");
	}

	@Override
	public int hashCode() {
		throw new RuntimeException("Not implemented");
	}
}
