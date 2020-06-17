package org.mos.mcore.service;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.protobuf.ByteString;
import lombok.Data;
import onight.osgi.annotation.NActorProvider;
import onight.tfw.ntrans.api.ActorService;
import org.apache.felix.ipojo.annotations.Instantiate;
import org.apache.felix.ipojo.annotations.Provides;
import org.mos.mcore.handler.MCoreConfig;
import org.mos.mcore.trie.StorageTrie;

import java.util.concurrent.TimeUnit;

@NActorProvider
@Provides(specifications = { ActorService.class }, strategy = "SINGLETON")
@Instantiate(name = "bc_storagetriecache")
@Data
public class StorageTrieCache implements ActorService {
	protected LoadingCache<ByteString, StorageTrie> storageTrieCache;

	public StorageTrieCache() {
		storageTrieCache = CacheBuilder.newBuilder().expireAfterAccess(5, TimeUnit.MINUTES).maximumSize(MCoreConfig.STATETRIE_BUFFER_MEMORY_MIN_MB)
				.build(new CacheLoader<ByteString, StorageTrie>() {
					@Override
					public StorageTrie load(ByteString key) throws Exception {
						return null;
					}
				});
	}

	public void put(ByteString key, StorageTrie val) {
		if (val == null) {
			delete(key);
		} else {
			storageTrieCache.put(key, val);
		}
	}

	public StorageTrie get(ByteString key) {
		try {
			return this.storageTrieCache.getIfPresent(key);
		} catch (Exception e) {
			return null;
		}
	}

	public void delete(ByteString key) {
		storageTrieCache.invalidate(key);
	}
}
