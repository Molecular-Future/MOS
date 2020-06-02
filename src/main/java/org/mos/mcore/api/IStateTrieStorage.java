package org.mos.mcore.api;

public interface IStateTrieStorage {
	public byte[] esPut(byte[] key, byte[] value);

	public byte[] esGet(byte[] key);

	public byte[] esRemove(Object key);

	public byte[] sha3(byte[] data);

	String bytesToHexStr(byte[] bytes);

	byte[] hexStrToBytes(String hexStr);

}
