package org.mos.mcore.odb;

import onight.tfw.ojpa.api.DomainDaoSupport;
import onight.tfw.ojpa.api.ServiceSpec;
import onight.tfw.ojpa.api.exception.NotSuportException;
import org.mos.mcore.api.ODBSupport;
import org.mos.mcore.exception.ODBException;
import org.mos.mcore.tools.bytes.BytesHashMap;

import java.util.List;
import java.util.concurrent.Future;

public class NoneODBDao implements ODBSupport {

	@Override
	public void setDaosupport(DomainDaoSupport support) {
		throw new NotSuportException("DaoNotFound");
	}

	@Override
	public DomainDaoSupport getDaosupport() {
		return null;
	}

	@Override
	public ServiceSpec getServiceSpec() {
		return null;
	}

	@Override
	public String getDomainName() {
		return null;
	}

	@Override
	public Class<?> getDomainClazz() {
		return null;
	}
	
	@Override
	public Future<byte[]> get(byte[] key) throws ODBException {
		throw new NotSuportException("DaoNotFound");
	}

	@Override
	public Future<byte[][]> list(List<byte[]> key) throws ODBException {
		throw new NotSuportException("DaoNotFound");
	}

	@Override
	public Future<BytesHashMap<byte[]>> listBySecondKey(byte[] secondKey) throws ODBException {
		throw new NotSuportException("DaoNotFound");
	}

	@Override
	public Future<byte[]> put(byte[] key, byte[] value) throws ODBException {
		throw new NotSuportException("DaoNotFound");
	}

	@Override
	public Future<byte[]> put(byte[] key, byte[] secondaryKey, byte[] value) throws ODBException {
		throw new NotSuportException("DaoNotFound");
	}

	@Override
	public Future<byte[][]> batchPuts(List<byte[]> key, List<byte[]> value) throws ODBException {
		throw new NotSuportException("DaoNotFound");
	}

	@Override
	public Future<byte[]> putIfNotExist(byte[] key, byte[] value) throws ODBException {
		throw new NotSuportException("DaoNotFound");
	}

	@Override
	public Future<byte[]> delete(byte[] key) throws ODBException {
		throw new NotSuportException("DaoNotFound");
	}

	@Override
	public Future<byte[][]> batchDelete(List<byte[]> key) throws ODBException {
		throw new NotSuportException("DaoNotFound");
	}

	@Override
	public Future<BytesHashMap<byte[]>> deleteBySecondKey(byte[] secondKey, List<byte[]> keys) throws ODBException {
		throw new NotSuportException("DaoNotFound");
	}

	@Override
	public void sync() throws ODBException {
		throw new NotSuportException("DaoNotFound");
	}
	
	@Override
	public void close()  throws ODBException {
		throw new NotSuportException("DaoNotFound");
	}

	@Override
	public void deleteAll() throws ODBException {
		throw new NotSuportException("DaoNotFound");
	}

}
