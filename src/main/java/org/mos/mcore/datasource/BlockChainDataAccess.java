package org.mos.mcore.datasource;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.protobuf.ByteString;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import onight.osgi.annotation.NActorProvider;
import onight.tfw.ntrans.api.annotation.ActorRequire;
import onight.tfw.ojpa.api.DomainDaoSupport;
import onight.tfw.ojpa.api.annotations.StoreDAO;
import org.apache.commons.codec.binary.Hex;
import org.apache.felix.ipojo.annotations.Instantiate;
import org.mos.mcore.api.ICryptoHandler;
import org.mos.mcore.api.ODBSupport;
import org.mos.mcore.exception.ODBException;
import org.mos.mcore.model.Block.BlockInfo;
import org.mos.mcore.service.BlockMessageBuffer;
import org.mos.mcore.tools.bytes.BytesComparisons;
import org.mos.mcore.tools.bytes.BytesHashMap;
import org.mos.mcore.tools.bytes.BytesHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@NActorProvider
@Instantiate(name = "blockchain_da")
@Slf4j
@Data
public class BlockChainDataAccess extends SecondaryBaseDatabaseAccess {
	@ActorRequire(name = "bc_block_buffer", scope = "global")
	BlockMessageBuffer blockMessageBuffer;
	@StoreDAO(target = daoProviderId, daoClass = BlockDao.class)
	ODBSupport blockDao;

	@StoreDAO(target = daoProviderId, daoClass = BlockSecondaryDao.class)
	ODBSupport blockSecondIdxDao;

	@ActorRequire(name = "bc_crypto", scope = "global")
	ICryptoHandler crypto;
	@ActorRequire(name = "common_da", scope = "global")
	CommonDataAccess commonDA;

	LoadingCache<ByteString, BlockInfo> blockCache;

	@Override
	public String[] getCmds() {
		return new String[] { "BCDAO" };
	}

	@Override
	public String getModule() {
		return "CHAIN";
	}

	public void setBlockDao(DomainDaoSupport dao) {
		this.blockDao = (ODBSupport) dao;
	}

	public ODBSupport getBlockDao() {
		return blockDao;
	}

	public void setBlockSecondIdxDao(DomainDaoSupport dao) {
		this.blockSecondIdxDao = (ODBSupport) dao;
	}

	public ODBSupport getBlockSecondIdxDao() {
		return blockSecondIdxDao;
	}

	public BlockChainDataAccess() {
		blockCache = CacheBuilder.newBuilder().refreshAfterWrite(5, TimeUnit.MINUTES)
				.expireAfterWrite(5, TimeUnit.MINUTES).expireAfterAccess(10, TimeUnit.MINUTES).maximumSize(100)
				.build(new CacheLoader<ByteString, BlockInfo>() {
					@Override
					public BlockInfo load(ByteString key) throws Exception {
						return null;
					}
				});
	}

	public void saveBlock(BlockInfo block) throws ODBException, InterruptedException, ExecutionException {
		byte[] blockHash = block.getHeader().getHash().toByteArray();
		this.put(blockDao, blockHash, block.toByteArray());
		put(blockSecondIdxDao, blockHash, blockHash, BytesHelper.longToBytes(block.getHeader().getHeight()));

		blockCache.put(block.getHeader().getHash(), block);

		if (log.isDebugEnabled())
			log.debug(String.format("save block   ===>   height[%-10s] org.mos.mcore.crypto.hash[%s]",
					block.getHeader().getHeight(), crypto.bytesToHexStr(block.getHeader().getHash().toByteArray())));
	}

	public BlockInfo getBlockByHash(byte[] hash) throws Exception {
		BlockInfo block = this.blockCache.getIfPresent(ByteString.copyFrom(hash));
		if (block != null) {
			return block;
		}

		byte[] v = get(blockDao, hash);
		if (v != null) {
			block = BlockInfo.parseFrom(v);
			blockCache.put(block.getHeader().getHash(), block);
			return block;
		}
		if(hash.length==33) {
			byte originHash[] = new byte[32];
			System.arraycopy(hash, 1, originHash, 0, 32);
			log.info("load block by short 32 bytes hash:"+Hex.encodeHexString(hash)+"==>"+Hex.encodeHexString(originHash));
			return getBlockByHash(originHash);
		}
		return null;
	}

	public BlockInfo[] getBlockByHeight(long height) throws Exception {
		BytesHashMap<byte[]> blockHashs = getBySecondaryKey(blockSecondIdxDao, BytesHelper.longToBytes(height));

		ArrayList<BlockInfo> bis = new ArrayList<BlockInfo>();
		for (Map.Entry<byte[], byte[]> entrySet : blockHashs.entrySet()) {
			BlockInfo block = getBlockByHash(entrySet.getValue());
			if(block!=null) {
				bis.add(block);
			}
		}
		
		BlockInfo ret [] = new BlockInfo[bis.size()];
		for(int i=0;i<bis.size();i++) {
			ret[i] = bis.get(i);
		}
		return ret;
	}

	public void connectBlock(BlockInfo block) throws Exception {
		commonDA.setConnectBlockHash(block.getHeader().getHash().toByteArray());

	}

	// public void saveBlockToFile(BlockInfo block) {
	// String blockdir = "." + File.separator + "db" + File.separator + "block" +
	// File.separator + "blks"
	// + File.separator + block.getHeader().getHeight();
	// boolean success = true;
	// new File(blockdir).getParentFile().mkdirs();
	// try (FileOutputStream fout = new FileOutputStream(blockdir + ".tmp");) {
	// fout.write(block.toByteArray());
	// fout.flush();
	// } catch (Exception e) {
	// log.error("error in writing block:" + blockdir + ".tmp",e);
	// success = false;
	// }
	// if (success) {
	// try {
	// new File(blockdir).delete();
	// new File(blockdir + ".tmp").renameTo(new File(blockdir));
	// } catch (Exception e) {
	// log.error("error in rename file:" + blockdir,e);
	// }
	//
	// }
	//
	// }

	public BlockInfo loadMaxConnectBlock() throws Exception {
		byte[] v = commonDA.getConnectBlockHash();
		if (v != null) {
			return getBlockByHash(v);
		}
		return null;
	}

	public void stableBlock(BlockInfo block) throws Exception {
		commonDA.setStableBlockHash(block.getHeader().getHash().toByteArray());

//		BytesHashMap<byte[]> blocks = getBySecondaryKey(blockDao,
//				BytesHelper.longToBytes(block.getHeader().getHeight()));
		
		BytesHashMap<byte[]> blockHashs = getBySecondaryKey(blockSecondIdxDao, BytesHelper.longToBytes(block.getHeader().getHeight()));
		
		List<byte[]> rmKeys = new ArrayList<>();
		for (Map.Entry<byte[], byte[]> entrySet : blockHashs.entrySet()) {
			if (!BytesComparisons.equal(block.getHeader().getHash().toByteArray(), entrySet.getKey())) {
				rmKeys.add(entrySet.getKey());
				blockCache.invalidate(ByteString.copyFrom(entrySet.getKey()));
			}
		}

		if (log.isInfoEnabled()) {
			log.info("stable block org.mos.mcore.crypto.hash="
					+ crypto.bytesToHexStr(block.getHeader().getHash().toByteArray())
					+ ", and remove fork block from db "
					+ rmKeys.stream().map(x -> crypto.bytesToHexStr(x)).collect(Collectors.joining(",")));
		}
		if (rmKeys.size() > 0) {
			deleteBySecondKey(blockSecondIdxDao, BytesHelper.longToBytes(block.getHeader().getHeight()), rmKeys);
		}
	}

	public void unStableBlock(BlockInfo block) throws Exception {

	}

	public BlockInfo loadMaxStableBlock() throws Exception {
		byte[] v = commonDA.getStableBlockHash();
		if (v != null) {
			return getBlockByHash(v);
		}
		return null;
	}

	public BlockInfo loadGenesisBlock() throws Exception {
		return getBlockByHeight(0)[0];
	}
}
