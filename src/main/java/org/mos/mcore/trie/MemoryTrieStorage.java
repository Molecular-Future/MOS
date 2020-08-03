package org.mos.mcore.trie;

import org.mos.mcore.api.IStateTrieStorage;
import org.mos.mcore.tools.bytes.BytesHashMap;
import org.spongycastle.util.encoders.Hex;

public class MemoryTrieStorage implements IStateTrieStorage {
	private BytesHashMap<byte[]> storage = new BytesHashMap<>();

	@Override
	public byte[] esGet(byte[] key) {
		return storage.get(key);
	}

	@Override
	public byte[] esPut(byte[] key, byte[] value) {
		return storage.put(key, value);
	}

	@Override
	public byte[] esRemove(Object key) {
		if (key instanceof String) {
			return storage.remove(Hex.decode(key.toString()));
		} else {
			return storage.remove((byte[]) key);
		}
	}
	public int size() {
		return storage.size();
	}
	
	public String content() {
		return ""+storage;
	}

	@Override
	public byte[] sha3(byte[] data) {
		return data;
	}

	@Override
	public String bytesToHexStr(byte[] bytes) {
		// TODO Auto-generated method stub
		return Hex.toHexString(bytes);
	}

	@Override
	public byte[] hexStrToBytes(String hexStr) {
		// TODO Auto-generated method stub
		return Hex.decode(hexStr);
	}
}
