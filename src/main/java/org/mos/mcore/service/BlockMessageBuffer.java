package org.mos.mcore.service;

import lombok.Data;
import onight.osgi.annotation.NActorProvider;
import onight.tfw.ntrans.api.ActorService;
import org.apache.felix.ipojo.annotations.Instantiate;
import org.apache.felix.ipojo.annotations.Provides;
import org.mos.mcore.bean.BlockMessage;
import org.mos.mcore.bean.BlockMessage.BlockMessageStatusEnum;
import org.mos.mcore.tools.bytes.BytesComparisons;
import org.mos.mcore.tools.bytes.BytesHashMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

@NActorProvider
@Provides(specifications = { ActorService.class }, strategy = "SINGLETON")
@Instantiate(name = "bc_block_buffer")
@Data
public class BlockMessageBuffer implements ActorService {
	BytesHashMap<BlockMessage> hashStorage = new BytesHashMap<>();
	HashMap<Long, List<BlockMessage>> heightStorage = new HashMap<>();

	public boolean containsHash(byte[] hash) {
		return hashStorage.containsKey(hash);
	}

	public boolean containsHeight(Long height) {
		return heightStorage.containsKey(height);
	}

	public int getHashCount() {
		return hashStorage.size();
	}
	
	public int getHeightCount() {
		return heightStorage.size();
	}

	public BlockMessage get(byte[] hash) {
		return hashStorage.get(hash);
	}

	public List<BlockMessage> get(long height) {
		return heightStorage.get(height);
	}

	public List<BlockMessage> get(long height, boolean connected) {
		List<BlockMessage> all = heightStorage.get(height);
		List<BlockMessage> ret = new ArrayList<>();
		if (all == null) {
			return ret;
		}
		for (BlockMessage bm : all) {
			if (!connected || (connected && bm.getStatus().equals(BlockMessageStatusEnum.CONNECT)) ) {
				ret.add(bm);
			}
		}
		return ret;
	}

	public void put(BlockMessage bm) {
		hashStorage.put(bm.getHash(), bm);
		if (heightStorage.containsKey(bm.getHeight())) {
			heightStorage.get(bm.getHeight()).add(bm);
		} else {
			List<BlockMessage> ls = new ArrayList<>();
			ls.add(bm);
			heightStorage.put(Long.parseLong(String.valueOf(bm.getHeight())), ls);
		}
	}

	public void remove(byte[] hash) {
		if (hashStorage.containsKey(hash)) {
			BlockMessage bm = hashStorage.get(hash);
			hashStorage.remove(hash);
			Iterator<BlockMessage> it = heightStorage.get(bm.getHeight()).iterator();
			while (it.hasNext()) {
				BlockMessage itBM = it.next();
				if (BytesComparisons.equal(itBM.getHash(), hash)) {
					it.remove();
					break;
				}
			}
		}
	}

//	public void remove(long height) {
//		if (heightStorage.containsKey(height)) {
//			List<BlockMessage> list = heightStorage.get(height);
//			heightStorage.remove(height);
//			for (BlockMessage bm : list) {
//				hashStorage.remove(bm.getHash());
//			}
//		}
//	}

	public void clear() {
		heightStorage.clear();
		hashStorage.clear();
	}
}
