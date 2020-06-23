package org.mos.mcore.tools.queue;

import java.util.List;

public interface IPendingQueue<T> {
	void shutdown();
	void addElement(T hp);
	void addLast(T hp);
	int size();
	boolean hasMoreElement();
	T pollFirst();
	List<T> poll(int size);
	String getStatInfo() ;
}
