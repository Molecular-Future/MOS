package org.mos.mcore.trie;

import com.google.protobuf.ByteString;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import onight.tfw.outils.conf.PropHelper;
import org.mos.mcore.api.IStateTrieStorage;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@AllArgsConstructor
@Data
@Slf4j
public class StateTrieTree implements Runnable {
	StateTrieNode root = new StateTrieNode(null);

	IStateTrieStorage storage;

	@Override
	public String toString() {
		return "EMTree@" + ",root=" + toJsonString();
	}

	public StateTrieNode delete(byte[] key) {
		return root.delete(key, storage);
	}

	public byte[] encode(long blocknumber) {
		byte[] rootbb = root.eencode(storage, blocknumber);
		// root.flushMemory(0);
		return rootbb;
	}

	public StateTrieNode.Stat getStat(int blocknumber) {
		if (root != null) {
			root.eencode(storage, blocknumber);
			return root.getStat();
		} else {
			return new StateTrieNode.Stat();
		}
	}

	public String toJsonString() {
		return root.toJsonString();
	}

	public StateTrieNode findByKey(byte[] key) {
		return root.getByKey(key, storage);
	}

	@AllArgsConstructor
	public class PAppend {
		byte[] key;
		byte[] value;
		int deep;
		StateTrieNode node;
	}

	ConcurrentHashMap<Integer, ReentrantLock> firstChildPendLocks = new ConcurrentHashMap<>();
	static PropHelper props = new PropHelper(null);
	int deepInsertParall = props.get("org.mos.mcore.handler.parrall.insert.deep", -1);

	public StateTrieNode inserta(StateTrieNode node, byte[] key,ByteString keybs,byte[] value, int deep) {
		node.setDirty(true);
		if (node.children[StateTrieNode.TREE_RADIX] == null) {
//			node.overrideChildNode(StateTrieNode.TREE_RADIX, value);
			StateTrieNode leafNode = new StateTrieNode(key,keybs, value);
			leafNode.setDirty(true);
			node.appendLeafNode(leafNode);
		} else {
			int idx = (key[deep % key.length] & 0x3F);
			StateTrieNode childNode = node.children[idx];
			if (childNode != null) {
				insert(childNode, key,keybs, value, deep + 1);
			} else {
				node.appendChildNode(new StateTrieNode(key,keybs, value), idx);
			}
		}
		return node;
	}

	public StateTrieNode insert(StateTrieNode node, byte[] key,ByteString keybs, byte[] value, int deep) {
		node.setDirty(true);
		int keySize = StateTrieNode.keySize(key);
		// System.out.println("keysize=="+keySize+",deep=="+deep);
		if (deep > keySize - 1) {
			// System.out.println("leaf node");

			StateTrieNode leafNode = new StateTrieNode(key,keybs,value);
			leafNode.setLeafNode(true);
			leafNode.setDirty(true);
			node.appendLeafNode(leafNode);
		} else {
			int idx = StateTrieNode.keyIndexAt(key, deep);
			// System.out.println("idx=="+idx);

			StateTrieNode child = node.getChild(idx, storage);
			if (child == null) {
				node.appendChildNode(new StateTrieNode(key,keybs, value), idx);

			} else if (Arrays.equals(key, child.getKey())) {
				node.overrideChildNode(idx, value);
			} else {
				if (deep == deepInsertParall) {
					ReentrantLock lock = firstChildPendLocks.get(idx);
					if (lock == null) {
						synchronized (firstChildPendLocks) {
							lock = firstChildPendLocks.get(idx);
							if (lock == null) {
								lock = new ReentrantLock();
								firstChildPendLocks.put(idx, lock);
							}
						}
					}

					final ReentrantLock flock = lock;
					StateTrieNode.executor.submit(new Runnable() {
						@Override
						public void run() {
							try {
								flock.lock();
								insert(child, key,keybs, value, deep + 1);
							} finally {
								flock.unlock();
							}
						}
					});

				} else {
					insert(child, key,keybs, value, deep + 1);
				}

			}
		}
		return node;
	}

	public void run() {

	}

	public void put(byte[] key, ByteString bs, byte[] value) {
		if (root == null) {
			root = new StateTrieNode(key, bs, value);
		} else {
			if (value == null || value.length == 0) {
				root = delete(key);
			} else {
				root = insert(root, key,bs, value, 0);
			}
		}
	}

	public StateTrieTree(IStateTrieStorage storage) {
		this.storage = storage;
	}
}
