package org.mos.mcore.handler;

import com.google.protobuf.ByteString;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import onight.osgi.annotation.NActorProvider;
import onight.tfw.ntrans.api.ActorService;
import onight.tfw.ntrans.api.annotation.ActorRequire;
import onight.tfw.outils.pool.ReusefulLoopPool;
import org.apache.felix.ipojo.annotations.Instantiate;
import org.apache.felix.ipojo.annotations.Provides;
import org.mos.mcore.concurrent.AccountInfoWrapper;
import org.mos.mcore.datasource.AccountDataAccess;
import org.mos.mcore.datasource.ContractDataAccess;
import org.mos.mcore.model.Account.AccountInfo;
import org.mos.mcore.model.Account.AccountInfo.Builder;
import org.mos.mcore.model.Account.CryptoValue;
import org.mos.mcore.model.Account.TokenValue;
import org.mos.mcore.model.Chain.ChainKey;
import org.mos.mcore.service.ChainConfig;
import org.mos.mcore.tools.bytes.BytesHelper;
import org.mos.mcore.trie.StorageTrie;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

@NActorProvider
@Provides(specifications = { ActorService.class }, strategy = "SINGLETON")
@Instantiate(name = "bc_account")
@Slf4j
@Data
public class AccountHandler implements ActorService {

	@ActorRequire(name = "MCoreServices", scope = "global")
	MCoreServices mcore;

	@ActorRequire(name = "contract_da", scope = "global")
	ContractDataAccess contractDA;

	@ActorRequire(name = "account_da", scope = "global")
	AccountDataAccess accountDA;

	@ActorRequire(name = "bc_chainconfig", scope = "global")
	ChainConfig chainConfig;
	
	public AccountInfo.Builder createAccount(ByteString address) {
		return createAccount(address, null, BytesHelper.ZERO_BYTE_STRING, null, null);
	}

	public AccountInfo.Builder createAccount(ByteString address, ByteString name) {
		return createAccount(address, name, BytesHelper.ZERO_BYTE_STRING, null, null);
	}

	public AccountInfo.Builder createAccount(ByteString address, ByteString name, ByteString balance, ByteString code,
			ByteString exdata) {
		AccountInfo.Builder account = AccountInfo.newBuilder();
		if (name != null && !name.equals(ByteString.EMPTY)) {
			account.setName(name);
		}
		account.setBalance(balance);
		StorageTrie storage = new StorageTrie(mcore.stateTrie);
//		if (addresses != null && addresses.size() > 0) {
//			UnionAccount.Builder ua = UnionAccount.newBuilder();
//			ua.addAllUnionCoaddress(addresses);
//			storage.put(AddressHelper.ACCOUNT_UNION_ADDR_BYTE, ua.build().toByteArray());
//		}
		account.setNonce(0);
		if (code != null) {
			byte codeBB[] = code.toByteArray();
			byte[] codehash = mcore.crypto.sha3(codeBB);
			storage.put(codehash, codeBB);
		}

		if (exdata != null) {
			account.setExtData(exdata);
		}
		account.setAddress(address);
		return account;
	}

	public AccountInfo.Builder getAccount(ByteString addr) {
		try {
			if (mcore.stateTrie != null) {
				return getAccount(addr.toByteArray());
			}
		} catch (Exception e) {
			log.error("account not found::" + mcore.crypto.bytesToHexStr(addr.toByteArray()), e);
		}
		return null;
	}

	public AccountInfo.Builder getAccount(byte[] addr) {
		try {
			byte[] valueHash = mcore.stateTrie.get(addr);
			if (valueHash != null) {

				AccountInfo.Builder oAccountInfo = AccountInfo.newBuilder().mergeFrom(valueHash);
				return oAccountInfo;
			}
		} catch (Exception e) {
			log.error("account not found::" + mcore.crypto.bytesToHexStr(addr), e);
		}
		return null;
	}

	public int getNonce(byte[] addr) {
		AccountInfo.Builder account = getAccount(addr);
		if (account == null) {
			return 0;
		} else {
			return account.getNonce();
		}
	}

	/**
	 * 根据地址获取账户。
	 * <p>
	 * 如果地址不存在，就根据地址创建一个新的账户对象
	 * </p>
	 * 
	 * @param addr
	 * @return
	 */
	public AccountInfoWrapper getAccountOrCreate(ByteString addr) {
		// return new AccountInfoWrapper(getAccountOrCreate(addr.toByteArray()));
		try {
			AccountInfo.Builder oAccount = getAccount(addr);
			if (oAccount == null) {
				oAccount = createAccount(addr);
			}
			return new AccountInfoWrapper(oAccount);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	public AccountInfo.Builder getAccountOrCreate(byte[] addr) {
		try {
			AccountInfo.Builder oAccount = getAccount(addr);
			if (oAccount == null) {
				oAccount = createAccount(ByteString.copyFrom(addr));
			}
			return oAccount;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	public static int compareSortable(SortableSet former, SortableSet latter) {
		byte[] formerBytes;
		byte[] latterBytes;
		try {
			formerBytes = former.keypairs.get()[0];
			latterBytes = latter.keypairs.get()[0];
			int cc = 0;
			while (cc < formerBytes.length && cc < latterBytes.length) {
				int result = formerBytes[cc] - latterBytes[cc];
				if (result < 0) {
					return -1;
				} else if (result > 0) {
					return 1;
				}
				cc++;
			}
			return Integer.compare(formerBytes.length, latterBytes.length);
		} catch (Exception e) {
			log.error("error in comparing ", e);
		}
		return 0;

	}

	public void quickSort(SortableSet arr[], int begin, int end) {
		if (begin < end) {
			int partitionIndex = partition(arr, begin, end);

			quickSort(arr, begin, partitionIndex - 1);
			quickSort(arr, partitionIndex + 1, end);
		}
	}

	private int partition(SortableSet arr[], int begin, int end) {
		SortableSet pivot = arr[end];
		int i = (begin - 1);

		for (int j = begin; j < end; j++) {
			if (compareSortable(arr[j], pivot) <= 0) {
				i++;
				SortableSet swapTemp = arr[i];
				arr[i] = arr[j];
				arr[j] = swapTemp;
			}
		}

		SortableSet swapTemp = arr[i + 1];
		arr[i + 1] = arr[end];
		arr[end] = swapTemp;

		return i + 1;
	}

	ReusefulLoopPool<SortableSet> setPool = new ReusefulLoopPool<SortableSet>();

	class SortableSet implements Callable<byte[][]> {
		Future<byte[][]> keypairs;
		ByteString keybs;
		ConcurrentHashMap<ByteString, AccountInfoWrapper> accountValues;
		long blocknumber;

		public SortableSet(ByteString keybs, ConcurrentHashMap<ByteString, AccountInfoWrapper> accountValues,
				long blocknumber) {
			super();
			this.keybs = keybs;
			this.accountValues = accountValues;
			this.blocknumber = blocknumber;
			keypairs = mcore.dispatcher.getExecutorService("blockproc").submit(this);
		}

		public byte[] getKeyByte() throws Exception {
			return keypairs.get()[0];
		}

		public byte[] getValueByte() throws Exception {
			return keypairs.get()[1];
		}

		@Override
		public byte[][] call() throws Exception {
			AccountInfoWrapper value = accountValues.get(keybs);
			byte[][] ret = new byte[2][];
			ret[0] = keybs.toByteArray();
			ret[1] = value.build(blocknumber).toByteArray();
			return ret;
		}

	}

	public SortableSet borrowSet(ByteString keybs, ConcurrentHashMap<ByteString, AccountInfoWrapper> accountValues,
			long blocknumber) {
		SortableSet set = setPool.borrow();
		if (set == null) {
			return new SortableSet(keybs, accountValues, blocknumber);
		} else {
			set.accountValues = accountValues;
			set.keybs = keybs;
			set.blocknumber = blocknumber;
			set.keypairs = mcore.dispatcher.getExecutorService("blockproc").submit(set);
			return set;
		}
	}

	boolean logaccount = false;

	public void batchPutAccounts(long blockheight, ConcurrentHashMap<ByteString, AccountInfoWrapper> accountValues)
			throws Exception {
		// long start = System.currentTimeMillis();
		int size = accountValues.size();
		Set<ByteString> keySets = accountValues.keySet();
		Iterator<ByteString> iterator = keySets.iterator();
		// sort by keys
		SortableSet keys[] = new SortableSet[size];
		int cc = 0;
		while (iterator.hasNext()) {
			ByteString keybs = iterator.next();
			keys[cc] = borrowSet(keybs, accountValues, blockheight);
			cc++;
		}
		quickSort(keys, 0, size - 1);
		// Arrays.sort(keys, SS_COMPARATORKEY);
		for (SortableSet key : keys) {
			if (logaccount && size > 10) {
				log.error("putaccount[" + blockheight + "],size=" + size + ":"
						+ mcore.crypto.bytesToHexStr(key.getKeyByte()) + " => "
						+ mcore.crypto.bytesToHexStr(key.getValueByte()));
			}
			
			mcore.stateTrie.put(key.getKeyByte(), key.getValueByte());
			setPool.retobj(key);
		}
		if (size > 10) {
			logaccount = false;
		}

	}

	public TokenValue getTokenBalance(ByteString accountAddress, ByteString tokenAddress) {
		AccountInfo.Builder tokenAccount = getAccount(tokenAddress);
		byte[] tvBytes = getStorageValue(tokenAccount, accountAddress.toByteArray());
		if (tvBytes != null) {
			try {
				TokenValue oTokenValue = TokenValue.parseFrom(tvBytes);
				return oTokenValue;
			} catch (Exception e) {
				log.error("", e);
			}
		}
		return null;
	}

	public CryptoValue getCryptoToken(Builder account, byte[] tokenhash) {
		byte[] tvBytes = getStorageValue(account, tokenhash);
		if (tvBytes != null) {
			try {
				CryptoValue oTokenValue = CryptoValue.parseFrom(tvBytes);
				return oTokenValue;
			} catch (Exception e) {
				log.error("", e);
			}
		}
		return null;
	}

	public StorageTrie getStorageTrie(AccountInfo.Builder oAccount) {
		StorageTrie oStorage = mcore.storageTrieCache.get(oAccount.getAddress());
		if (oAccount.getStorageTrieRoot() != null) {
			oStorage = StorageTrie.getStorageTrie(oAccount.getStorageTrieRoot().toByteArray(), mcore.stateTrie);
			mcore.storageTrieCache.put(oAccount.getAddress(), oStorage);
		} else {
			oStorage = new StorageTrie(mcore.stateTrie);
		}
		return oStorage;
	}

	public byte[] getStorageValue(AccountInfo.Builder oAccount, byte[] key) {
		StorageTrie oStorage = getStorageTrie(oAccount);
		try {
			return oStorage.get(key);
		} catch (Exception e) {
			log.error(
					"error on get storage value from " + mcore.crypto.bytesToHexStr(oAccount.getAddress().toByteArray())
							+ ". key=" + mcore.crypto.bytesToHexStr(key));
		}
		return null;
	}
	
	public ChainKey getlastChainkey() {
		return chainConfig.getLastChainKey();
	}

	public ChainKey getChainkeyByVersion(String ver) {
		ChainKey ck = chainConfig.getChainKeys().get(ver);
		if(ck == null){
			throw new RuntimeException("no such chainkey on this version:"+ver);
		}
		return ck;
	}
}
