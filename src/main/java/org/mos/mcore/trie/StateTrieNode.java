package org.mos.mcore.trie;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.protobuf.ByteString;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import onight.tfw.outils.conf.PropHelper;
import onight.tfw.outils.pool.ReusefulLoopPool;
import org.mos.mcore.api.IStateTrieStorage;
import org.mos.mcore.handler.MCoreConfig;
import org.spongycastle.util.encoders.Hex;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Slf4j
public class StateTrieNode {
	static int CHILDREN_MASK_BYTES = 16;
	static int TREE_RADIX = 64;
	static byte[] ROOT_KEY = "ROOT".getBytes();
	static ByteString ROOT_KEYBS = ByteString.copyFrom(ROOT_KEY);
	static byte[] NULL_KEY = new byte[0];
	static ByteString NULL_KEYBS = ByteString.copyFrom(NULL_KEY);

	static PropHelper props = new PropHelper(null);

	byte[] childrenHashs[] = new byte[TREE_RADIX + 1][];
	byte[] key = ROOT_KEY;
	ByteString keyBS = ROOT_KEYBS;
	byte v[];
	boolean dirty = false;
	boolean deleted = false;
	byte[] hash = null;
	ByteString hashBS = null;
	// String hashKey = null;
	byte[] contentData = null;
	byte[][] contentSplitData = null;
	boolean leafNode = false;
	StateTrieNode children[] = new StateTrieNode[TREE_RADIX + 1];
	public static StateTrieNode NULL_NODE = new StateTrieNode(NULL_KEY, NULL_KEYBS, null);
	// public static AtomicLong cacheAddrHitCounter = new AtomicLong(0);
	// public static AtomicLong cacheAddrMissCounter = new AtomicLong(0);
	public static AtomicInteger threadRunnerCounter = new AtomicInteger(0);
	public static int MAX_PARALLEL_RUNNER = props.get("org.mos.mcore.handler.state.parallel",
			Runtime.getRuntime().availableProcessors() * 2);
	// final static int POOL_SIZE = new
	// PropHelper(null).get("org.mos.mcore.handler.state.parallel",
	// Math.max(4, Runtime.getRuntime().availableProcessors()));
	public static ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
	public static ExecutorService executor2 = Executors.newFixedThreadPool(MAX_PARALLEL_RUNNER);
	// public static ExecutorService executor1 =null;
	// Executors.newFixedThreadPool(POOL_SIZE);
	public static Cache<ByteString, StateTrieNode> cacheByAddress = CacheBuilder.newBuilder().initialCapacity(100000)
			.expireAfterWrite(props.get("org.mos.mcore.handler.store.trie.addresscache.expire.sec", 3600),
					TimeUnit.SECONDS)
			.maximumSize(props.get("org.mos.mcore.handler.store.trie.addresscache.maxsize", 1000000))
			.concurrencyLevel(Runtime.getRuntime().availableProcessors()).build();
	public static Cache<ByteString, StateTrieNode> cacheByHash = CacheBuilder.newBuilder().initialCapacity(100000)
			.expireAfterWrite(props.get("org.mos.mcore.handler.store.trie.hashcache.expire.sec", 3600),
					TimeUnit.SECONDS)
			.maximumSize(props.get("org.mos.mcore.handler.store.trie.hashcache.maxsize", 1000000))
			.concurrencyLevel(Runtime.getRuntime().availableProcessors()).build();
	// public static AtomicLong cacheHashHitCounter = new AtomicLong(0);
	// public static AtomicLong cacheHashMissCounter = new AtomicLong(0);

	// public static AtomicInteger maxDeep = new AtomicInteger(0);
	// public static AtomicInteger codeDeep = new AtomicInteger(0);
	// public static AtomicInteger codeCC = new AtomicInteger(0);
	// public static AtomicInteger decodeCC = new AtomicInteger(0);
	// public static AtomicInteger parrelCC0 = new AtomicInteger(0);
	public static AtomicInteger bufferAlloc = new AtomicInteger(0);
	// public static AtomicInteger parrelCC1 = new AtomicInteger(0);

	public boolean isNullNode() {
		return this == NULL_NODE;
	}

	public boolean isNotNullNode() {
		return this != NULL_NODE;
	}

	public String toString() {
		return "ETNODE64[]";
	}

	@Data
	public static class Stat {
		long size;
		int deep;
		long dataSize;
		int count;

		Stat() {
			this.size = 0;
			this.deep = 0;
			this.dataSize = 0;
		}
	}

	void appendJsonString(StringBuilder sb, Stat stat) {
		if (isNullNode()) {
			sb.append("{\"NULL_NODE\":true}");
		} else {
			sb.append("{");
			long size = CHILDREN_MASK_BYTES + key.length;
			long dataSize = key.length;
			sb.append("\"key\":\"").append(Hex.toHexString(key)).append("\"");
			if (v != null) {
				size += v.length;
				dataSize += v.length;
				sb.append(",\"v\":\"").append(Hex.toHexString(v)).append("\"");
			}
			if (hash != null) {
				size += hash.length;
				sb.append(",\"org.mos.mcore.crypto.hash\":\"").append(Hex.toHexString(hash)).append("\"");
			}
			String format = ",\"node_%02d\":";
			int subDeep = 0;
			long subSize = 0;
			long subDataSize = 0;
			int subCount = 0;
			for (int i = 0; i < TREE_RADIX + 1; i++) {
				if (children[i] != null) {
					sb.append(String.format(format, i));
					Stat st = new Stat();
					children[i].appendJsonString(sb, st);
					if (st.getDeep() > subDeep) {
						subDeep = st.getDeep();
					}
					subCount += st.getCount();
					subSize += st.getSize();
					subSize += childrenHashs[i].length;
					subDataSize += st.getDataSize();
				}
			}
			sb.append("}");

			stat.setCount(stat.getCount() + 1 + subCount);
			stat.setDeep(stat.getDeep() + 1 + subDeep);
			stat.setSize(stat.getSize() + size + subSize);
			stat.setDataSize(stat.getDataSize() + dataSize + subDataSize);
		}
	}

	public String toJsonString() {
		StringBuilder sb = new StringBuilder();
		Stat stat = new Stat();
		appendJsonString(sb, stat);
		// log.info("tree size: {} ,data size: {}, maxDeep: {} , extend rate: {}
		// %",
		// stat.getSize(), stat.getDataSize(), stat.getDeep(), (stat.getSize() -
		// stat.getDataSize()) * 100.0 / stat.getDataSize());
		return sb.toString();
	}

	public Stat getStat() {
		StringBuilder sb = new StringBuilder();
		Stat stat = new Stat();
		appendJsonString(sb, stat);
		return stat;
	}

	// 加载当前节点和子节点，递归调用
	public StateTrieNode deepLoad(IStateTrieStorage storage) {
		for (int i = 0; i < TREE_RADIX + 1; i++) {
			if (childrenHashs[i] != null) {
				if (children[i] == null) {
					// 从KV数据库中获取Value
					// byte bb[] = storage.esGet(childrenHashs[i]);
					// if (bb == null) {
					// throw new RuntimeException("org.mos.mcore.crypto.hash not found:" +
					// childrenHashs[i]);
					// }
					// 反序列化
					children[i] = fromBytes(childrenHashs[i], storage);
					// 递归获取子Node
					children[i].deepLoad(storage);
				}
			}
		}
		return this;
	}

	public StateTrieNode iterateNodes(IStateTrieStorage storage, StateTrieScanner scanner) {
		scanner.foundNode(this);
		for (int i = 0; i < TREE_RADIX + 1; i++) {
			if (childrenHashs[i] != null) {
				// 从KV数据库中获取Value
				// byte bb[] = storage.esGet(childrenHashs[i]);
				// if (bb == null) {
				// throw new RuntimeException("org.mos.mcore.crypto.hash not found:" +
				// childrenHashs[i]);
				// }
				// // 反序列化
				children[i] = fromBytes(childrenHashs[i], storage);
				// 递归获取子Node
				children[i].iterateNodes(storage, scanner);
				children[i] = null;
			}
		}

		return this;
	}

	public byte[] eencode(IStateTrieStorage storage, long blocknumber) {
		// nodeCount.set(0);
		byte[] ret = _encode(storage, blocknumber, 0);
		return ret;
	}

	public static void resetCounter() {
		// codeDeep.set(0);
		// parrelCC0.set(0);
		// parrelCC1.set(0);
		// bufferAlloc.set(0);
		// codeCC.set(0);
		// decodeCC.set(0);
	}

	static int fastHash = new PropHelper(null).get("org.mos.mcore.handler.state.fasthash", 0);
	static int layer2Parral = new PropHelper(null).get("org.mos.mcore.handler.state.layer2.parral", 0);

	byte[] _encode(IStateTrieStorage storage, long blocknumber, int deep) {
		// if (maxDeep.get() < deep) {
		// maxDeep.set(deep);
		// }
		// 如果已经计算过hash，则直接返回
		if (hash != null && !dirty) {
			return hash;
		}
		// if (codeDeep.get() < deep) {
		// codeDeep.set(deep);
		// }
		// codeCC.incrementAndGet();
		if (hash != null) {
			// invalidate old org.mos.mcore.crypto.hash
			storage.esRemove(hash);
			cacheByHash.invalidate(hashBS);
		}
		// 序列化
		contentData = toBytes(storage, blocknumber, deep);
		// len = 64
		if (contentData.length < NOHASH_LENGTH && contentData.length != 32) {
			hash = contentData;
		} else {
			hash = storage.sha3(contentData);
		}

		hash[0] = (byte) ((blocknumber / 4) % 256);
		hashBS = ByteString.copyFrom(hash);
		cacheByHash.put(hashBS, this);
		// cacheByAddress.put(keyStr, this);
		dirty = false;
		if (storage != null && hash != contentData) {
			storage.esPut(hash, contentData);
		}
		return hash;
	}

	/**
	 * 获取子节点
	 *
	 * @param idx
	 *            子节点索引
	 * @param storage
	 *            存储
	 * @return 子节点
	 */
	public StateTrieNode getChild(int idx, IStateTrieStorage storage) {
		if (idx > TREE_RADIX) {
			throw new RuntimeException("ETNode child index invalid:" + idx);
		}
		if (children[idx] != null) {
			return children[idx];
		} else if (childrenHashs[idx] != null && storage != null) {
			String hashStr = Hex.toHexString(childrenHashs[idx]);
			children[idx] = cacheByHash.getIfPresent(hashStr);
			if (children[idx] != null) {
				// cacheHashHitCounter.incrementAndGet();
				cacheByAddress.put(children[idx].keyBS, children[idx]);
			} else {
				children[idx] = fromBytes(childrenHashs[idx], storage);
				// cacheHashMissCounter.incrementAndGet();
			}
		}
		return children[idx];
	}

	/**
	 * 获取子节点
	 *
	 * @param key
	 *            子节点的Key
	 * @param storage
	 *            存储
	 * @return 子节点
	 */
	public StateTrieNode getByKey(byte[] key, IStateTrieStorage storage) {
		StateTrieNode node = getByKey(key, storage, 0);
		return node;
	}

	public static int keySize(byte[] key) {
		int r = (key.length * 8) % 6;
		return key.length * 8 / 6 + (r == 0 ? 0 : 1);
	}

	public static int keyIndexAt(byte[] key, int deep) {
		int start = deep / 4 * 3;
		int offset = deep % 4;
		int remainder = key.length % 3;
		if (start <= key.length - remainder - 3) {
			switch (offset) {
			case 0:
				return (key[start] & 0xff) >> 2;
			case 1:
				return ((key[start] & 0x03) << 4) + ((key[start + 1] & 0xf0) >> 4);
			case 2:
				return ((key[start + 1] & 0x0f) << 2) + ((key[start + 2] & 0xff) >> 6);
			case 3:
				return (key[start + 2] & 0x03f);
			}
		} else if (start <= key.length - remainder) {
			if (remainder == 1) {
				switch (offset) {
				case 0:
					return (key[start] & 0xff) >> 2;
				case 1:
					return ((key[start] & 0x03) << 4);
				default:
					throw new RuntimeException("deep invalid");
				}
			} else if (remainder == 2) {
				switch (offset) {
				case 0:
					return (key[start] & 0xff) >> 2;
				case 1:
					return ((key[start] & 0x03) << 4) + ((key[start + 1] & 0xf0) >> 4);
				case 2:
					return ((key[start + 1] & 0x0f) << 2);
				default:
					throw new RuntimeException("deep invalid");
				}
			} else {
				throw new RuntimeException("deep invalid");
			}
		} else {
			throw new RuntimeException("deep invalid");
		}
		return 0;
	}

	public StateTrieNode getByKey(byte[] key, IStateTrieStorage storage, int deep) {
		if (this.key != null && Arrays.equals(this.key, key)) {
			return this;
		}
		String hexkey = Hex.toHexString(key);
		StateTrieNode child = cacheByAddress.getIfPresent(hexkey);
		if (child != null) {
			// cacheAddrHitCounter.incrementAndGet();
			return child;
		}

		child = NULL_NODE;

		int keySize = keySize(key);

		if (deep < keySize) {
			int idx = keyIndexAt(key, deep);
			if (children[idx] != null && children[idx] != NULL_NODE) {
				child = children[idx];
			} else if (childrenHashs[idx] != null) {
				child = cacheByHash.getIfPresent(Hex.encode(childrenHashs[idx]));
				if (child != null) {
					children[idx] = child;
					// cacheHashHitCounter.incrementAndGet();
				} else {
					child = fromBytes(childrenHashs[idx], storage);
					children[idx] = child;
				}
			} else {
				// child = NULL_NODE;
			}
		} else {
			child = children[TREE_RADIX];
			if (child == null && storage != null && childrenHashs[TREE_RADIX] != null) {
				child = cacheByHash.getIfPresent(Hex.encode(childrenHashs[TREE_RADIX]));
				if (child != null) {
					children[TREE_RADIX] = child;
					// cacheHashHitCounter.incrementAndGet();
				} else {
					child = fromBytes(childrenHashs[TREE_RADIX], storage);
					children[TREE_RADIX] = child;
				}
			}
		}

		if (child != null && child != NULL_NODE) {
			return child.getByKey(key, storage, deep + 1);
		}
		return NULL_NODE;
	}

	public void appendChildNode(StateTrieNode node, int idx) {
		this.children[idx] = node;
		this.children[idx].setDirty(true);
		this.dirty = true;
		cacheByAddress.put(node.keyBS, node);
	}

	public void overrideChildNode(int idx, byte[] v) {
		this.children[idx].setV(v);
		this.children[idx].setDirty(true);
		// this.children[idx] = node;
		this.dirty = true;
	}

	public void appendLeafNode(StateTrieNode node) {
		this.children[TREE_RADIX] = node;
		cacheByAddress.put(node.keyBS, node);
		this.dirty = true;
		node.dirty = true;
	}

	public void writeShort(byte bb[], OutputStream out) throws IOException {
		out.write(bb.length);
		out.write(bb);
	}

	public static byte[] readShort(InputStream in) throws IOException {
		int len = in.read();
		byte bb[] = new byte[len];
		in.read(bb);
		return bb;
	}

	public static byte[] readMask(InputStream in) throws IOException {
		byte[] mask = new byte[CHILDREN_MASK_BYTES];
		int readBytes = in.read(mask);
		if (readBytes != CHILDREN_MASK_BYTES) {
			log.error("date format error, read children mask bits error,need:{}, real:{}", CHILDREN_MASK_BYTES,
					readBytes);
		}
		return mask;
	}

	static byte[] bitTestTable = new byte[] { (byte) ((1 << 0) & 0xff), (byte) ((1 << 1) & 0xff),
			(byte) ((1 << 2) & 0xff), (byte) ((1 << 3) & 0xff), (byte) ((1 << 4) & 0xff), (byte) ((1 << 5) & 0xff),
			(byte) ((1 << 6) & 0xff), (byte) ((1 << 7) & 0xff) };

	public static boolean bitTest(byte[] d, int index) {
		if (index >= d.length * 8) {
			return false;
		}
		int start = index / 8;
		int offset = index % 8;
		return (d[start] & bitTestTable[offset]) != 0;
	}

	public static void bitSet(byte[] d, int index) {
		if (index >= d.length * 8) {
			return;
		}
		int start = index / 8;
		int offset = index % 8;
		d[start] |= bitTestTable[offset];
	}

	static ReusefulLoopPool<ByteBuffer> bbPools = new ReusefulLoopPool<>();
	static int BUFFER_LIMIT = 65536;

	public static ByteBuffer borrowBuffer(int size) {
		if (size > BUFFER_LIMIT) {// 800k,65536
			log.error("alloc big buffer:" + size);
			return ByteBuffer.allocate(size);
		}
		ByteBuffer bb = bbPools.borrow();
		if (bb == null) {
			bufferAlloc.incrementAndGet();
			bb = ByteBuffer.allocateDirect(BUFFER_LIMIT);
		} else {
			bb.clear();
		}
		return bb;
	}

	static {
		for (int i = 0; i < 128; i++) {
			bbPools.retobj(borrowBuffer(BUFFER_LIMIT));
		}
	}

	public static void returnBuffer(ByteBuffer bb) {
		if (bb.limit() <= BUFFER_LIMIT && bbPools.getActiveObjs().size() < 1024) {
			bbPools.retobj(bb);
		} else {
			log.error("drop return buffer:" + bbPools.getActiveObjs().size());
		}

	}

	public byte[] toBytes(IStateTrieStorage storage, long blocknumber, int deep) {

		Future<byte[]>[] datas = new Future[TREE_RADIX + 1];

		// if (deep == 0) {
		for (int i = 0; i < TREE_RADIX + 1; i++) {
			if (children[i] != null && (children[i].isDirty() || children[i].hash == null)) {
				final StateTrieNode node = children[i];
				if (deep == 0) {
					// if (threadRunnerCounter.incrementAndGet() < MAX_PARALLEL_RUNNER) {
					datas[i] = executor.submit(new Callable<byte[]>() {
						@Override
						public byte[] call() throws Exception {
							// parrelCC0.incrementAndGet();
							// try {
							return node._encode(storage, blocknumber, deep + 1);
							// } finally {
							// threadRunnerCounter.decrementAndGet();
							// }
						}
					});
					// } else {
					// threadRunnerCounter.decrementAndGet();
					// }
				} else {
					if (threadRunnerCounter.incrementAndGet() < MAX_PARALLEL_RUNNER) {
						datas[i] = executor2.submit(new Callable<byte[]>() {
							@Override
							public byte[] call() throws Exception {
								// parrelCC0.incrementAndGet();
								try {
									return node._encode(storage, blocknumber, deep + 1);
								} finally {
									threadRunnerCounter.decrementAndGet();
								}
							}
						});
					} else {
						threadRunnerCounter.decrementAndGet();
						childrenHashs[i] = children[i]._encode(storage, blocknumber, deep + 1);
					}
				}
			}
		}

		// }
		// }
		int totalLen = key.length + 1 + CHILDREN_MASK_BYTES;
		byte[] mask = new byte[CHILDREN_MASK_BYTES];

		int childcount = 0;

		for (int i = 0; i < TREE_RADIX + 1; i++) {
			if (children[i] != null || childrenHashs[i] != null) {
				bitSet(mask, i);
				childcount++;
			}
			if (children[i] != null) {
				if (datas[i] != null) {
					try {
						childrenHashs[i] = datas[i].get(60, TimeUnit.SECONDS);
					} catch (Exception e) {
						log.error("get org.mos.mcore.crypto.hash error:" + i + ",deep=" + deep, e);
						childrenHashs[i] = children[i]._encode(storage, blocknumber, deep + 1);
					}
					// } else {
					// childrenHashs[i] = children[i]._encode(storage, blocknumber, deep + 1);
				}
				totalLen += 65;
			}
		}

		if (childcount == 0) {
			int testLen = key.length;
			if (v != null) {
				testLen += v.length;
			}
			if (testLen < NOHASH_LENGTH - 2 && testLen != 30) {
				byte retbb[] = new byte[testLen + 2];
				retbb[1] = (byte) key.length;
				System.arraycopy(key, 0, retbb, 2, key.length);
				if (v != null) {
					System.arraycopy(v, 0, retbb, key.length + 2, v.length);
				}
				return retbb;
			}
		}
		if (v != null) {
			totalLen += v.length;
		}
		totalLen += 64;// for test

		ByteBuffer bb = null;
		// int freeM = (int)(Runtime.getRuntime().freeMemory()/1024/1024);
		try {

			bb = borrowBuffer(totalLen);
			bb.put((byte) key.length);
			bb.put(key);
			bb.put(mask);
			for (int i = 0; i < TREE_RADIX + 1; i++) {
				if (childrenHashs[i] != null) {
					bb.put((byte) childrenHashs[i].length);
					bb.put(childrenHashs[i]);
				}
				if (children[i] != null && (deep >= MCoreConfig.STATETRIE_BUFFER_DEEP)
						&& cacheByAddress.getIfPresent(children[i].keyBS) == null
						&& cacheByHash.getIfPresent(children[i].hashBS) == null) {
					children[i] = null;
				}
			}
			if (v != null) {
				bb.put(v);
			}
			byte retbb[] = new byte[bb.position()];
			bb.rewind();
			bb.get(retbb);
			return retbb;
		} catch (Exception e) {
			log.error("eto bytes error::", e);
			return null;
		} finally {
			if (bb != null) {
				returnBuffer(bb);
			}
		}

	}

	public static int NOHASH_LENGTH = 40;

	public static StateTrieNode fromBytes(byte[] hash, IStateTrieStorage storage) {
		if (hash.length < NOHASH_LENGTH && hash.length != 32) {
			int keylen = hash[1] & 0xff;
			if (keylen > 0) {
				byte key[] = new byte[keylen];
				System.arraycopy(hash, 2, key, 0, keylen);
				byte bbv[] = null;
				if (keylen + 2 < hash.length) {
					bbv = new byte[hash.length - keylen - 2];
					System.arraycopy(hash, 2 + keylen, bbv, 0, bbv.length);
				}
				StateTrieNode node = new StateTrieNode(hash, key, ByteString.copyFrom(key), bbv,
						new byte[TREE_RADIX + 1][]);
				cacheByHash.put(node.hashBS, node);
				cacheByAddress.put(node.keyBS, node);
				// cacheAddrMissCounter.incrementAndGet();
				// cacheHashMissCounter.incrementAndGet();
				return node;

			}
		}
		byte[] bb = storage.esGet(hash);
		if (bb == null) {
			return null;
		}
		try {
			// decodeCC.incrementAndGet();
			ByteBuffer buffer = ByteBuffer.wrap(bb);
			int len = buffer.get();
			byte key[] = new byte[len];
			buffer.get(key);

			byte[] mask = new byte[CHILDREN_MASK_BYTES];
			buffer.get(mask);

			byte[][] childrenHash = new byte[TREE_RADIX + 1][];
			for (int i = 0; i < TREE_RADIX + 1; i++) {
				if (bitTest(mask, i)) {
					len = buffer.get();
					childrenHash[i] = new byte[len];
					buffer.get(childrenHash[i]);
				}
			}
			byte[] bbv = null;
			if (buffer.remaining() > 0) {
				bbv = new byte[buffer.remaining()];
				buffer.get(bbv);
			}
			StateTrieNode node = new StateTrieNode(hash, key, ByteString.copyFrom(key), bbv, childrenHash);
			cacheByHash.put(node.hashBS, node);
			cacheByAddress.put(node.keyBS, node);
			// cacheAddrMissCounter.incrementAndGet();
			// cacheHashMissCounter.incrementAndGet();
			return node;
		} catch (Exception e) {
			throw new RuntimeException("parse etnode error:" + bb.length, e);
		} finally {
		}
	}

	public StateTrieNode(byte[] key, ByteString keyBS, byte[] v) {
		this.key = key;
		this.v = v;
		this.keyBS = keyBS;

	}

	public void flushMemory(int deep) {
		// if (deep < 0) {
		// for (int i = 0; i < TREE_RADIX + 1; i++) {
		// if (children[i] != null) {
		// children[i].flushMemory(deep + 1);
		// }
		// }
		// } else {
		// for (int i = 0; i < TREE_RADIX + 1; i++) {
		// children[i] = null;
		// }
		// }
	}

	public StateTrieNode(byte[] hash) {
		this.key = ROOT_KEY;
		this.hash = hash;
		if (hash != null) {
			hashBS = ByteString.copyFrom(hash);
		}
		if (key != null) {
			keyBS = ROOT_KEYBS;
		}
	}

	public StateTrieNode delete(byte[] key, IStateTrieStorage storage) {
		StateTrieNode node = getByKey(key, storage, 0);
		node.setDeleted(true);
		return node;
	}

	public StateTrieNode(byte[] hash, byte[] key, ByteString keyBS, byte[] v, byte[] childrenHash[]) {
		this.hash = hash;
		if (hash != null) {
			hashBS = ByteString.copyFrom(hash);
		}
		this.key = key;
		this.keyBS = keyBS;
		this.v = v;
		this.childrenHashs = childrenHash;
		this.dirty = false;
	}

}
