package org.mos.mcore.handler;

import lombok.Data;
import onight.osgi.annotation.NActorProvider;
import onight.tfw.ntrans.api.ActorService;
import onight.tfw.ntrans.api.annotation.ActorRequire;
import onight.tfw.outils.conf.PropHelper;
import org.apache.felix.ipojo.annotations.Instantiate;
import org.apache.felix.ipojo.annotations.Provides;
import org.mos.mcore.api.ICryptoHandler;
import org.mos.mcore.service.ChainConfig;
import org.mos.mcore.service.StateTrie;
import org.mos.mcore.service.StorageTrieCache;
import org.fc.zippo.dispatcher.IActorDispatcher;

import java.math.BigInteger;

@NActorProvider
@Provides(specifications = { ActorService.class }, strategy = "SINGLETON")
@Instantiate(name = "MCoreServices")
@Data
public class MCoreServices implements ActorService {

	@ActorRequire(name = "bc_account")
	AccountHandler accountHandler;

	@ActorRequire(name = "bc_actuactor")
	ActuactorHandler actuactorHandler;

	@ActorRequire(name = "bc_transaction")
	TransactionHandler transactionHandler;

	@ActorRequire(name = "bc_chain")
	ChainHandler chainHandler;

	@ActorRequire(name = "bc_block")
	BlockHandler blockHandler;

	/**
	 * services
	 */

	@ActorRequire(name = "bc_statetrie")
	StateTrie stateTrie;

	@ActorRequire(name = "bc_chainconfig")
	ChainConfig chainConfig;

	@ActorRequire(name = "bc_crypto", scope = "global")
	ICryptoHandler crypto;

	@ActorRequire(name = "bc_storagetriecache", scope = "global")
	StorageTrieCache storageTrieCache;

	@ActorRequire(name = "zippo.ddc", scope = "global")
	IActorDispatcher dispatcher = null;

	public IActorDispatcher getDispatcher() {
		return dispatcher;
	}

	public void setDispatcher(IActorDispatcher dispatcher) {
		this.dispatcher = dispatcher;
	}

	public MCoreServices() {
	}

	public String toString() {
		return "@MCoreServices";
	}
	
	public int getBlockEpochMS() {
		return chainConfig.getConfigAccount().getChainConfig().getBlockMineEpochMs();
	}
	public int getBlockMineTimeoutMs() {
		return chainConfig.getConfigAccount().getChainConfig().getBlockMineTimeoutMs();
	}
	
	public int getBlockMineMaxContinue() {
		return chainConfig.getConfigAccount().getChainConfig().getBlockMineMaxContinue();
	}
	
	public BigInteger getBlock() {
		return chainConfig.getConfigAccount().getBlockRewards();
	}
	
	
	public static PropHelper props = new PropHelper(null);
	
	public static int STATETRIE_BUFFER_DEEP = props.get("org.mos.mcore.statetrie.buffer.deep", 3);

	public static int STATETRIE_BUFFER_MEMORY_MIN_MB = props.get("org.mos.mcore.statetrie.buffer.memory.min.mb", 256);
}
