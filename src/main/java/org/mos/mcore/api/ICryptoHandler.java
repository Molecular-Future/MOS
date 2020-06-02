package org.mos.mcore.api;

import org.mos.mcore.crypto.KeyPairs;

public interface ICryptoHandler {
	String bytesToBase64Str(byte[] bytes);

	byte[] base64StrToBytes(String base64Str);

	String bytesToHexStr(byte[] bytes);

	byte[] hexStrToBytes(String hexStr);

	byte[] sha3(byte[] bytes);

	byte[] sha256(byte[] bytes);

	KeyPairs genAccountKey();

	String genBcuid(String pubkey);

	KeyPairs genAccountKey(String text);

	byte[] privateKeyToAddress(byte[] privateKey);

	byte[] privateKeyToPublicKey(byte[] privateKey);

	KeyPairs privatekeyToAccountKey(byte[] privateKey);

	byte[] sign(byte[] privateKey, byte[] content);

	byte[] signatureToAddress(byte[] content, byte[] signature);

	byte[] signatureToKey(byte[] content, byte[] signature);

	boolean verify(byte[] publicKey, byte[] content, byte[] signature);

	KeyPairs restoreKeyStore(String keyStoreStr, String pwd);

	String genKeyStoreJson(KeyPairs accountKey, String pwd);

	public boolean isReady();

	String mnemonicGenerate();

	byte[] encrypt(byte[] publicKey, byte[] content);

	byte[] decrypt(byte[] privateKey, byte[] content);

	public byte[] desEncode(byte[] content, String desKey);

	public byte[] desDecode(byte[] encBytes, String desKey);

}
