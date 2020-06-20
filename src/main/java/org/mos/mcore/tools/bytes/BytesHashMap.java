package org.mos.mcore.tools.bytes;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BytesHashMap<T> implements Map<byte[], T> {
	private final Map<BytesKey, T> delegate;

	public BytesHashMap() {
		this(new ConcurrentHashMap<BytesKey, T>());
	}

	public BytesHashMap(Map<BytesKey, T> delegate) {
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
	public boolean containsKey(Object key) {
		return delegate.containsKey(new BytesKey((byte[]) key));
	}

	@Override
	public boolean containsValue(Object value) {
		return delegate.containsValue(value);
	}

	@Override
	public T get(Object key) {
		return delegate.get(new BytesKey((byte[]) key));
	}

	@Override
	public T put(byte[] key, T value) {
		return delegate.put(new BytesKey(key), value);
	}

	@Override
	public T remove(Object key) {
		return delegate.remove(new BytesKey((byte[]) key));
	}

	@Override
	public void putAll(Map<? extends byte[], ? extends T> m) {
		for (Entry<? extends byte[], ? extends T> entry : m.entrySet()) {
			delegate.put(new BytesKey(entry.getKey()), entry.getValue());
		}
	}

	@Override
	public void clear() {
		delegate.clear();
	}

	@Override
	public Set<byte[]> keySet() {
		return new BytesSet(new BytesSetAdapter<>(delegate));
	}

	@Override
	public Collection<T> values() {
		return delegate.values();
	}

	@Override
	public Set<Entry<byte[], T>> entrySet() {
		return new MapEntrySet(delegate.entrySet());
	}

	@Override
	public boolean equals(Object o) {
		return delegate.equals(o);
	}

	@Override
	public int hashCode() {
		return delegate.hashCode();
	}

	@Override
	public String toString() {
		return delegate.toString();
	}

	private class MapEntrySet<V> implements Set<Entry<byte[], V>> {
		private final Set<Entry<BytesKey, V>> delegate;

		private MapEntrySet(Set<Entry<BytesKey, V>> delegate) {
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
			throw new RuntimeException("Not implemented");
		}

		@Override
		public Iterator<Entry<byte[], V>> iterator() {
			final Iterator<Entry<BytesKey, V>> it = delegate.iterator();
			return new Iterator<Entry<byte[], V>>() {

				@Override
				public boolean hasNext() {
					return it.hasNext();
				}

				@Override
				public Entry<byte[], V> next() {
					Entry<BytesKey, V> next = it.next();
					return new AbstractMap.SimpleImmutableEntry(next.getKey().getData(), next.getValue());
				}

				@Override
				public void remove() {
					it.remove();
				}
			};
		}

		@Override
		public Object[] toArray() {
			throw new RuntimeException("Not implemented");
		}

		@Override
		public <T> T[] toArray(T[] a) {
			throw new RuntimeException("Not implemented");
		}

		@Override
		public boolean add(Entry<byte[], V> vEntry) {
			throw new RuntimeException("Not implemented");
		}

		@Override
		public boolean remove(Object o) {
			throw new RuntimeException("Not implemented");
		}

		@Override
		public boolean containsAll(Collection<?> c) {
			throw new RuntimeException("Not implemented");
		}

		@Override
		public boolean addAll(Collection<? extends Entry<byte[], V>> c) {
			throw new RuntimeException("Not implemented");
		}

		@Override
		public boolean retainAll(Collection<?> c) {
			throw new RuntimeException("Not implemented");
		}

		@Override
		public boolean removeAll(Collection<?> c) {
			throw new RuntimeException("Not implemented");
		}

		@Override
		public void clear() {
			throw new RuntimeException("Not implemented");

		}
	}
}
