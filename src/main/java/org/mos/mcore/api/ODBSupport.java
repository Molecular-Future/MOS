package org.mos.mcore.api;

import onight.tfw.ojpa.api.DomainDaoSupport;
import org.mos.mcore.exception.ODBException;
import org.mos.mcore.tools.bytes.BytesHashMap;

import java.util.List;
import java.util.concurrent.Future;

/**
 * add support for odb -- osgi database.
 * 
 * @author brew
 *
 */
public interface ODBSupport extends DomainDaoSupport {
	/**
	 * get value by key. if key not exists, this method will return null.
	 * 
	 * @param key
	 * @return
	 * @throws ODBException
	 */
	Future<byte[]> get(byte[] key) throws ODBException;

	Future<byte[][]> list(List<byte[]> key) throws ODBException;

	Future<BytesHashMap<byte[]>> listBySecondKey(byte[] secondKey) throws ODBException;

	Future<byte[]> put(byte[] key, byte[] value) throws ODBException;

	Future<byte[]> put(byte[] key, byte[] secondaryKey, byte[] value) throws ODBException;

	Future<byte[][]> batchPuts(List<byte[]> key, List<byte[]> value) throws ODBException;

	Future<byte[]> putIfNotExist(byte[] key, byte[] value) throws ODBException;

	Future<byte[]> delete(byte[] key) throws ODBException;

	Future<byte[][]> batchDelete(List<byte[]> key) throws ODBException;

	Future<BytesHashMap<byte[]>> deleteBySecondKey(byte[] secondKey, List<byte[]> keys) throws ODBException;

	void sync() throws ODBException;
	
	void close() throws ODBException;
	
	void deleteAll()  throws ODBException;
}
