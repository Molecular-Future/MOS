package org.mos.mcore.trie;

import lombok.extern.slf4j.Slf4j;
import onight.tfw.ntrans.api.ActorService;
import org.mos.mcore.api.IStateTrieStorage;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class StorageTrie implements ActorService, IStateTrieStorage {

	private StorageTrieTree etree;

	AtomicInteger encodeTimes = new AtomicInteger(0);

	AtomicBoolean lock = new AtomicBoolean();
	// byte[] owner;
	IStateTrieStorage storageRoot;//

	public StorageTrie(IStateTrieStorage storageRoot) {
		// this.owner = owner;
		this.storageRoot = storageRoot;
		this.etree = new StorageTrieTree(this);
	}

	public static StorageTrie getStorageTrie(byte[] keyHash, IStateTrieStorage storageRoot) {
		StorageTrieNode rootnode = StorageTrieNode.fromBytes(keyHash, storageRoot);
		if (rootnode != null) {
			StorageTrie ret = new StorageTrie(storageRoot);
			ret.etree.setRoot(rootnode);
			return ret;
		}
		return null;
	}

	public void delete(byte[] key) {
		etree.delete(key);
	}

	public byte[] get(byte[] address) throws Exception {
		try {
			byte[] ret = null;
			StorageTrieNode node = getETNode(address);
			if (node != null) {
				ret = node.getV();
				return ret;
			}
			return null;
		} catch (Exception e) {
			throw new Exception("get from trie error", e);
		}
	}

	public StorageTrieNode getETNode(byte[] address) {
		return etree.findByKey(address);
	}

	public long getCacheSize() {
		return 0;
	}

	public byte[] getRootHash(long blocknumber) {
		byte[] hash = etree.encode(blocknumber);
		return hash;
	}

	public void setRoot(byte[] keyhash) {
		StorageTrieNode rootnode = StorageTrieNode.NULL_NODE;
		byte bb[] = esGet(keyhash);
		// log.debug("load node from org.mos.mcore.crypto.hash:bb.size=" + (bb ==
		// null ? 0 : bb.length));
		if (bb != null) {
			rootnode = StorageTrieNode.fromBytes(keyhash, this);
		}
		// log.error("set Root:" + storageRoot.bytesToHexStr(keyhash) + ",bb.size=" +
		// (bb == null ? 0 : bb.length));

		etree.setRoot(rootnode);

		if (bb == null) {
			throw new NullRootNodeException("root is null node");
		}
	}

	public void put(byte[] key, byte[] v) {
		etree.put(key, v);
	}

	@Override
	public byte[] esPut(byte[] hash, byte[] value) {
		storageRoot.esPut(hash, value);
		return value;
	}

	@Override
	public byte[] esGet(byte[] key) {
		return hexget(key);
	}

	public byte[] hexget(byte[] key) {
		return storageRoot.esGet(key);
	}

	@Override
	public byte[] esRemove(Object hash) {
		return null;
	}

	@Override
	public byte[] sha3(byte[] data) {
		return storageRoot.sha3(data);
	}

	@Override
	public String bytesToHexStr(byte[] bytes) {
		return storageRoot.bytesToHexStr(bytes);
	}

	@Override
	public byte[] hexStrToBytes(String hexStr) {
		return storageRoot.hexStrToBytes(hexStr);
	}
}
