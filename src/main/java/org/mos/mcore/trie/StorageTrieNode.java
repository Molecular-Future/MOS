package org.mos.mcore.trie;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import onight.tfw.outils.conf.PropHelper;
import onight.tfw.outils.pool.ReusefulLoopPool;
import org.mos.mcore.api.IStateTrieStorage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Slf4j
public class StorageTrieNode {
	private static int CHILDREN_MASK_BYTES = 16;
	private static int TREE_RADIX = 64;
	private static byte[] ROOT_KEY = "ROOT".getBytes();
	private static byte[] NULL_KEY = new byte[0];
	static PropHelper props = new PropHelper(null);
	public static StorageTrieNode NULL_NODE = new StorageTrieNode(NULL_KEY, null);

	private byte[] childrenHashs[] = new byte[TREE_RADIX + 1][];
	private byte[] key = ROOT_KEY;
	private byte v[];
	private boolean dirty = false;
	private boolean deleted = false;
	private byte[] hash = null;
	private byte[] contentData = null;
	private byte[][] contentSplitData = null;
	private boolean leafNode = false;
	private StorageTrieNode children[] = new StorageTrieNode[TREE_RADIX + 1];
	public static ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
	public static AtomicLong cacheHashHitCounter = new AtomicLong(0);
	public static AtomicLong cacheHashMissCounter = new AtomicLong(0);

	public String toString() {
		return "ETNODE64[]";
	}

	public byte[] eencode(IStateTrieStorage storage, long blocknumber) {
		// nodeCount.set(0);
		byte[] ret = _encode(storage, blocknumber, 0);
		return ret;
	}

	private byte[] _encode(IStateTrieStorage storage, long blocknumber, int deep) {
		// 如果已经计算过hash，则直接返回
		if (hash != null && !dirty) {
			return hash;
		}
		if (hash != null) {
			// invalidate old org.mos.mcore.crypto.hash
			storage.esRemove(hash);
		}
		// 序列化
		contentData = toBytes(storage, blocknumber, deep);
		// len = 64

		// org.mos.mcore.crypto.hash = new byte[NOHASH_LENGTH];
		hash = storage.sha3(contentData);
		hash[0] = (byte) ((blocknumber / 32) % 256);
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
	public StorageTrieNode getChild(int idx, IStateTrieStorage storage) {
		if (idx > TREE_RADIX) {
			throw new RuntimeException("ETNode child index invalid:" + idx);
		}
		if (children[idx] != null) {
			return children[idx];
		} else if (childrenHashs[idx] != null && storage != null) {
			children[idx] = fromBytes(childrenHashs[idx], storage);
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
	public StorageTrieNode getByKey(byte[] key, IStateTrieStorage storage) {
		StorageTrieNode node = getByKey(key, storage, 0);
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

	public StorageTrieNode getByKey(byte[] key, IStateTrieStorage storage, int deep) {
		if (this.key != null && Arrays.equals(this.key, key)) {
			return this;
		}

		int keySize = keySize(key);

		if (deep < keySize) {
			int idx = keyIndexAt(key, deep);
			if (children[idx] == null && childrenHashs[idx] != null) {
				children[idx] = fromBytes(childrenHashs[idx], storage);
			}

			if (children[idx] != null) {
				return children[idx].getByKey(key, storage, deep + 1);
			}
		} else {
			if (children[TREE_RADIX] == null && childrenHashs[TREE_RADIX] != null) {
				children[TREE_RADIX] = fromBytes(childrenHashs[TREE_RADIX], storage);
			}
			return children[TREE_RADIX];
		}

		return null;
	}

	public void appendChildNode(StorageTrieNode node, int idx) {
		this.children[idx] = node;
		this.children[idx].setDirty(true);
		this.dirty = true;
	}

	public void overrideChildNode(int idx, byte[] v) {
		this.children[idx].setV(v);
		this.children[idx].setDirty(true);
		this.dirty = true;
	}

	public void appendLeafNode(StorageTrieNode node) {
		this.children[TREE_RADIX] = node;
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

	private static byte[] bitTestTable = new byte[] { (byte) ((1 << 0) & 0xff), (byte) ((1 << 1) & 0xff),
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

		if (deep == 0) {
			for (int i = 0; i < TREE_RADIX + 1; i++) {
				if (children[i] != null && (children[i].isDirty() || children[i].hash == null)) {
					final StorageTrieNode node = children[i];
					datas[i] = executor.submit(new Callable<byte[]>() {
						@Override
						public byte[] call() throws Exception {
							return node._encode(storage, blocknumber, deep + 1);
						}
					});
				}
			}
		}
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
						log.error("get org.mos.mcore.crypto.hash error:", e);
						childrenHashs[i] = children[i]._encode(storage, blocknumber, deep + 1);
					}
				} else {
					childrenHashs[i] = children[i]._encode(storage, blocknumber, deep + 1);
				}
				totalLen += 65;
			}
		}
		if (v != null) {
			totalLen += v.length;
		}
		totalLen += 64;// for test

		ByteBuffer bb = null;
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
				if (children[i] != null && deep >= 3) {
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

	public static StorageTrieNode fromBytes(byte[] hash, IStateTrieStorage storage) {
		byte[] bb = storage.esGet(hash);
		if (bb == null) {
			return null;
		}
		try {
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
			StorageTrieNode node = new StorageTrieNode(hash, key, bbv, childrenHash);

			return node;
		} catch (Exception e) {
			throw new RuntimeException("parse etnode error:" + bb.length, e);
		} finally {
		}
	}

	public StorageTrieNode(byte[] key, byte[] v) {
		this.key = key;
		this.v = v;
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

	public StorageTrieNode(byte[] hash) {
		this.key = ROOT_KEY;
		this.hash = hash;
	}

	public StorageTrieNode delete(byte[] key, IStateTrieStorage storage) {
		StorageTrieNode node = getByKey(key, storage, 0);
		node.setDeleted(true);
		return node;
	}

	public StorageTrieNode(byte[] hash, byte[] key, byte[] v, byte[] childrenHash[]) {
		this.hash = hash;
		this.key = key;
		this.v = v;
		this.childrenHashs = childrenHash;
		this.dirty = false;
	}

}
