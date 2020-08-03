package org.mos.mcore.trie;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.mos.mcore.api.IStateTrieStorage;

import java.util.Arrays;

@AllArgsConstructor
@Data
@Slf4j
public class StorageTrieTree {

	IStateTrieStorage storage;

	// byte[] owner;

	@Override
	public String toString() {
		return "EMTree@";
	}

	public StorageTrieNode delete(byte[] key) {
		return root.delete(key, storage);
	}

	public byte[] encode(long blocknumber) {
		byte[] rootbb = root.eencode(storage, blocknumber);
		return rootbb;
	}

	public StorageTrieNode findByKey(byte[] key) {
		return root.getByKey(key, storage);
	}

	@AllArgsConstructor
	public class PAppend {
		byte[] key;
		byte[] value;
		int deep;
		StorageTrieNode node;
	}

	public StorageTrieNode insert(StorageTrieNode node, byte[] key, byte[] value, int deep) {
		node.setDirty(true);
		int keySize = StorageTrieNode.keySize(key);
		if (deep > keySize - 1) {
			StorageTrieNode leafNode = new StorageTrieNode(key, value);
			leafNode.setLeafNode(true);
			leafNode.setDirty(true);
			node.appendLeafNode(leafNode);
		} else {
			int idx = StorageTrieNode.keyIndexAt(key, deep);
			StorageTrieNode child = node.getChild(idx, storage);
			if (child == null) {
				node.appendChildNode(new StorageTrieNode(key, value), idx);
			} else if (Arrays.equals(key, child.getKey())) {
				node.overrideChildNode(idx, value);
			} else {
				insert(child, key, value, deep + 1);
			}
		}
		return node;
	}

	public void put(byte[] key, byte[] value) {
		if (root == null) {
			root = new StorageTrieNode(key, value);
		} else {
			if (value == null || value.length == 0) {
				root = delete(key);
			} else {
				root = insert(root, key, value, 0);
			}
		}
	}

	public StorageTrieTree(IStateTrieStorage storage) {
//		this.owner = owner;
		this.storage = storage;
		this.root = new StorageTrieNode(null);
	}

	StorageTrieNode root;
}
