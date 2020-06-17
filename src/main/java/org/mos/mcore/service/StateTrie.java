package org.mos.mcore.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import onight.osgi.annotation.NActorProvider;
import onight.tfw.mservice.ThreadContext;
import onight.tfw.ntrans.api.ActorService;
import onight.tfw.ntrans.api.annotation.ActorRequire;
import onight.tfw.outils.conf.PropHelper;
import org.apache.felix.ipojo.annotations.Instantiate;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Validate;
import org.mos.mcore.api.ICryptoHandler;
import org.mos.mcore.api.IStateTrieStorage;
import org.mos.mcore.datasource.AccountDataAccess;
import org.mos.mcore.tools.bytes.BytesHashMap;
import org.mos.mcore.trie.NullRootNodeException;
import org.mos.mcore.trie.StateTrieNode;
import org.mos.mcore.trie.StateTrieTree;
import org.mos.mcore.trie.StorageTrieNode;
import org.fc.zippo.dispatcher.IActorDispatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@NActorProvider
@Provides(specifications = { ActorService.class }, strategy = "SINGLETON")
@Instantiate(name = "bc_statetrie")
@Slf4j
@Data
public class StateTrie implements ActorService, IStateTrieStorage {
	@ActorRequire(name = "bc_crypto", scope = "global")
	ICryptoHandler crypto;
	@ActorRequire(name = "account_da", scope = "global")
	AccountDataAccess accountDA;

	PropHelper props = new PropHelper(null);

	@ActorRequire(name = "zippo.ddc", scope = "global")
	IActorDispatcher dispatcher = null;

	private StateTrieTree etree = new StateTrieTree(this);

	AtomicLong dbReadCounter = new AtomicLong(0);
	AtomicLong dbReadSize = new AtomicLong(0);
	AtomicLong dbWriteSize = new AtomicLong(0);
	AtomicLong dbWriteCounter = new AtomicLong(0);
	AtomicLong bmCounter = new AtomicLong(0);

	// BloomFilter<String> bmfilter =
	// BloomFilter.create(Funnels.stringFunnel(Charset.defaultCharset()), 1000000,
	// 0.01);

	AtomicLong nullNodeCounter = new AtomicLong(0);

	BytesHashMap<byte[]> deferPuts = new BytesHashMap<>();

	AtomicInteger encodeTimes = new AtomicInteger(0);

	AtomicBoolean lock = new AtomicBoolean();

	public void syncPutCache() {
		try {
			int size = deferPuts.size();
			List<byte[]> okeys = new ArrayList<>();
			List<byte[]> ovalues = new ArrayList<>();
			// FIXME for load test only
			// Map<String, Integer> logMap = new HashMap<String, Integer>();
			for (Entry<byte[], byte[]> entry : deferPuts.entrySet()) {
				okeys.add(entry.getKey());
				byte[] value = entry.getValue();
				ovalues.add(value);
				dbWriteSize.addAndGet(value.length);

				// String slice = String.valueOf(entry.getKey()[0]);
				// if (logMap.containsKey(slice)) {
				// logMap.put(slice, logMap.get(slice) + 1);
				// } else {
				// logMap.put(slice, 1);
				// }
			}
			dbWriteCounter.set(size);
			deferPuts.clear();
			accountDA.getDao().batchPuts(okeys, ovalues);

			// String logs = "";
			// for (Entry<String, Integer> entry : logMap.entrySet()) {
			// logs += entry.getKey() + "=" + entry.getValue() + ",";
			// }
			// log.debug("slice info =" + logs);
			// logMap.clear();
		} catch (Throwable t) {
			log.error("error in put cache", t);
		} finally {
			lock.set(false);
		}
	}

	public void flushPutCache() {
		while (!lock.compareAndSet(false, true)) {

		}
		
		try {
			dispatcher.getExecutorService("blockproc").execute(new Runnable() {
				@Override
				public void run() {
					syncPutCache();
				}
			});
		} catch (Throwable t) {
			log.error("cannot create Thread",t);
			System.exit(-1);
		}
	}

	public void clear() {
		if (etree != null) {
			log.error("clear deferputs::" + deferPuts.size() + " cacheByHash::" + StateTrieNode.cacheByHash.size()
					+ " StateTrieNode.cacheByAddress::" + StateTrieNode.cacheByAddress.size());
			try {
				while (!lock.compareAndSet(false, true)) {
					try {
						Thread.sleep(10);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
				deferPuts.clear();
				// cacheByHash.invalidateAll();
				StateTrieNode.cacheByHash.invalidateAll();
				StateTrieNode.cacheByAddress.invalidateAll();
				etree = new StateTrieTree(this);
			} finally {
				lock.compareAndSet(true, false);
			}
		}
	}

	public String toString() {
		return "ETStateTrie@,root=" + etree.toJsonString();
	}

	@Validate
	public void init() {
		etree.setStorage(this);
		lock.compareAndSet(false, true);
		new Thread(new Runnable() {

			@Override
			public void run() {

				try {
					while (dispatcher == null) {
						Thread.sleep(1000);
					}
					//reset state trie
					
					ExecutorService oldexecutor = StateTrieNode.executor;
					StateTrieNode.executor = dispatcher.getExecutorService("blockproc");
					oldexecutor.shutdown();
					//reset storage trie
					oldexecutor = StorageTrieNode.executor;
					StorageTrieNode.executor = dispatcher.getExecutorService("blockproc");
					oldexecutor.shutdown();
					log.error("init statetrie executor okok!!");
				} catch (Exception e) {
					log.error("error in seting runner:", e);
				} finally {
					lock.compareAndSet(true, false);

				}

			}
		}).start();
		log.info("ETStateTrie .init [ok]");
	}

	public void delete(byte[] key) {
		etree.delete(key);
		// cache.invalidate(encApi.hexEnc(key));
	}

	public byte[] get(byte[] address) throws Exception {
		try {
			byte[] ret = null;

			StateTrieNode node = getETNode(address);
			if (node.isNullNode()) {
				return null;
			} else {
				ret = node.getV();
				// bmfilter.put(hexAddr);
				return ret;
			}
		} catch (Exception e) {
			throw new Exception("get from trie error", e);
		}
	}

	public StateTrieNode getETNode(byte[] address) {
		return etree.findByKey(address);
	}

	public byte[] getRootHash(long blocknumber) {
		long startTime = System.currentTimeMillis();
		String threadname = Thread.currentThread().getName();
		Thread.currentThread().setName("getroothash[" + blocknumber + "]");
		while (lock.get()) {
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				log.error("waitup for sleep", e);
			}
		}
		long startEncodeTime = System.currentTimeMillis();
		byte[] hash = etree.encode(new Long(blocknumber).intValue());
		long endEncodeTime = System.currentTimeMillis();

		long endDBTime = System.currentTimeMillis();

		log.info("trie-calc::-->cacheHit=" + StateTrieNode.cacheHashHitCounter.get() + ",misshash="
				+ StateTrieNode.cacheHashMissCounter.get() + ",dbread=" + dbReadCounter.get() + "/" + dbReadSize.get()
				+ ",dbwrite=" + dbWriteCounter.get() + "/" + dbWriteSize.get() //
				+ ",hash=" + StateTrieNode.cacheByHash.size()//
				+ ",encodecc=" + StateTrieNode.codeCC.get()//
				+ ",decodecc=" + StateTrieNode.decodeCC.get()//
				+ ",codedeep=" + StateTrieNode.codeDeep.get() + "/" + StateTrieNode.maxDeep.get()//
				+ ",addr=[cc=" + StateTrieNode.cacheByAddress.size() + ",hit=" + StateTrieNode.cacheAddrHitCounter.get()
				+ ",mis=" + StateTrieNode.cacheAddrMissCounter.get()//
				// + ",bm=" + bmCounter.get()
				+ ",n=" + nullNodeCounter.get() + " " + blocknumber + "]"//
				+ ",cost=[" + (System.currentTimeMillis() - startTime) + "," + (endEncodeTime - startEncodeTime) + ","//
				+ (endDBTime - endEncodeTime) + "]");

		dbReadCounter.set(0);
		dbWriteCounter.set(0);
		StateTrieNode.cacheHashHitCounter.set(0);
		StateTrieNode.cacheHashMissCounter.set(0);
		nullNodeCounter.set(0);
		StateTrieNode.cacheAddrMissCounter.set(0);
		StateTrieNode.cacheAddrHitCounter.set(0);
		dbWriteSize.set(0);
		dbReadSize.set(0);
		Thread.currentThread().setName(threadname);
		return hash;
	}

	public void put(byte[] key, byte[] v) {

		etree.put(key, v);
	}

	public void setRoot(byte[] keyhash) {
		syncPutCache();
		clear();
		StateTrieNode rootnode = StateTrieNode.NULL_NODE;
		byte bb[] = esGet(keyhash);
		log.debug("load node from org.mos.mcore.crypto.hash:bb.size=" + (bb == null ? 0 : bb.length));
		if (bb != null) {
			rootnode = StateTrieNode.fromBytes(keyhash, this);
		}
		log.error("set Root:" + crypto.bytesToHexStr(keyhash) + ",bb.size=" + (bb == null ? 0 : bb.length));

		etree.setRoot(rootnode);

		if (bb == null) {
			throw new NullRootNodeException("root is null node");
		}
	}

	@Override
	public byte[] esPut(byte[] hash, byte[] value) {
		deferPuts.put(hash, value);
		return value;
	}

	@Override
	public byte[] esGet(byte[] key) {
		return hexget(key);
	}

	public byte[] hexget(byte[] key) {
		byte[] cacheValue = deferPuts.get(key);
		if (cacheValue != null) {
			StateTrieNode.cacheHashHitCounter.incrementAndGet();
			return cacheValue;
		}
		try {
			ThreadContext.setContext("_LDB_F", 1);
			byte[] body = accountDA.getDao().get(key).get();
			if (body != null) {
				dbReadSize.addAndGet(body.length);
				dbReadCounter.incrementAndGet();
				return body;
			}
		} catch (Exception e) {
			log.warn("esGetError:" + crypto.bytesToBase64Str(key), e);
		} finally {
			ThreadContext.removeContext("_LDB_F");
		}

		return null;
	}

	@Override
	public byte[] esRemove(Object hash) {
		return null;
	}

	@Override
	public byte[] sha3(byte[] data) {
		return crypto.sha3(data);
	}

	@Override
	public String bytesToHexStr(byte[] bytes) {
		return crypto.bytesToHexStr(bytes);
	}

	@Override
	public byte[] hexStrToBytes(String hexStr) {
		return crypto.hexStrToBytes(hexStr);
	}
}
