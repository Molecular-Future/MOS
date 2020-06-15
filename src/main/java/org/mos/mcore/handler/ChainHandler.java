package org.mos.mcore.handler;

import com.google.common.io.ByteStreams;
import com.google.protobuf.ByteString;
import com.googlecode.protobuf.format.JsonFormat;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import onight.osgi.annotation.NActorProvider;
import onight.tfw.ntrans.api.ActorService;
import onight.tfw.ntrans.api.annotation.ActorRequire;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.StringUtils;
import org.apache.felix.ipojo.annotations.Instantiate;
import org.apache.felix.ipojo.annotations.Provides;
import org.mos.mcore.api.BlockSubscriber;
import org.mos.mcore.api.ICryptoHandler;
import org.mos.mcore.bean.BlockMessage;
import org.mos.mcore.bean.BlockMessage.BlockMessageStatusEnum;
import org.mos.mcore.bean.BlockMessageMark;
import org.mos.mcore.bean.BlockMessageMark.BlockMessageMarkEnum;
import org.mos.mcore.bean.TransactionMessage;
import org.mos.mcore.concurrent.ChainConfigWrapper;
import org.mos.mcore.datasource.BlockChainDataAccess;
import org.mos.mcore.datasource.TransactionDataAccess;
import org.mos.mcore.exception.BlockNotFoundInBufferException;
import org.mos.mcore.exception.BlockRollbackNotAllowException;
import org.mos.mcore.model.Account.AccountInfo;
import org.mos.mcore.model.Block.BlockHeader;
import org.mos.mcore.model.Block.BlockInfo;
import org.mos.mcore.model.GenesisBlockOuterClass.GenesisBlock;
import org.mos.mcore.model.Transaction.TransactionBody;
import org.mos.mcore.model.Transaction.TransactionInfo;
import org.mos.mcore.service.BlockMessageBuffer;
import org.mos.mcore.service.ChainConfig;
import org.mos.mcore.service.StateTrie;
import org.mos.mcore.tools.bytes.BytesComparisons;
import org.mos.mcore.tools.bytes.BytesHelper;
import org.mos.mcore.trie.StorageTrie;
import org.fc.zippo.dispatcher.IActorDispatcher;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

@NActorProvider
@Provides(specifications = { ActorService.class }, strategy = "SINGLETON")
@Instantiate(name = "bc_chain")
@Slf4j
@Data
public class ChainHandler implements ActorService {
	@ActorRequire(name = "blockchain_da", scope = "global")
	BlockChainDataAccess blockChainDA;
	@ActorRequire(name = "bc_account", scope = "global")
	AccountHandler accountHandler;
	// @ActorRequire(name = "common_da", scope = "global")
	// CommonDataAccess commonDA;
	@ActorRequire(name = "bc_block_buffer", scope = "global")
	BlockMessageBuffer blockMessageBuffer;
	@ActorRequire(name = "MCoreServices", scope = "global")
	MCoreServices mcore;

	@ActorRequire(name = "bc_block")
	BlockHandler blockHandler;

	@ActorRequire(name = "bc_crypto", scope = "global")
	ICryptoHandler crypto;
	@ActorRequire(name = "bc_chainconfig", scope = "global")
	ChainConfig chainConfig;
	@ActorRequire(name = "bc_statetrie", scope = "global")
	StateTrie stateTrie;
	@ActorRequire(name = "transaction_data_access", scope = "global")
	TransactionDataAccess transactionDataAccess;

	@ActorRequire(name = "bc_transaction")
	TransactionHandler transactionHandler;

	@ActorRequire(name = "zippo.ddc", scope = "global")
	IActorDispatcher dispatcher = null;

	@ActorRequire(name = "bc_block_subscriber", scope = "global")
	BlockSubscriber blkSubscriber = null;

	private long maxReceiveHeight = -1;
	private long maxConnectHeight = -1;
	private long maxStableHeight = -1;
	private long lastConnectHeight = -1;
	private BlockInfo maxReceiveBlock = null;
	private BlockInfo maxConnectBlock = null;
	private BlockInfo maxStableBlock = null;
	private BlockInfo lastConnectBlock = null;

	public synchronized void startBlockChain(String vId, String vAddress, String vName) {
		try {
			// TODO vAddress 不是hex
			if (StringUtils.isBlank(vAddress)) {
				;
				byte[] nodeAccountAddress = crypto.hexStrToBytes(MCoreConfig.ACCOUNT_COINBASE_ADDRESS);
				if (nodeAccountAddress == null || nodeAccountAddress.length == 0) {
					throw new Exception("cannot find node account");
				}
				if (log.isDebugEnabled()) {
					log.debug("node init   ===>   set node account [{}]", crypto.bytesToHexStr(nodeAccountAddress));
				}
				chainConfig.miner_account_address = nodeAccountAddress;

			} else {
				chainConfig.miner_account_address = crypto.hexStrToBytes(vAddress.replaceFirst("0x", ""));
			}
			chainConfig.miner_account_address_bs = ByteString.copyFrom(chainConfig.miner_account_address);

			BlockInfo maxStabledBlock = blockChainDA.loadMaxStableBlock();
			if (maxStabledBlock == null) {
				// 不可能找不到稳定状态的块，一旦找不到直接抛出异常
				// throw new
				// BlockChainCannotStartException("没有找到稳定状态的区块，区块链无法启动。");
				loadGenesisDb(MCoreConfig.ENVIRONMENT_GENENSIS_FILE_PATH);
				maxStabledBlock = maxStableBlock;
			} else {
				// 每次重启往前推n块
				BlockInfo[] prevMaxStabledBlocks = blockChainDA
						.getBlockByHeight(maxStabledBlock.getHeader().getHeight() - MCoreConfig.PREV_BLOCK_COUNT);
				if (prevMaxStabledBlocks.length > 0) {
					maxStabledBlock = prevMaxStabledBlocks[0];
				}
			}

			maxStableHeight = maxStabledBlock.getHeader().getHeight();

			log.info(String.format(
					"node init   ===>   stable block: height[{%-10s}] org.mos.mcore.crypto.hash[{%s}]",
					maxStableHeight, crypto.bytesToHexStr(maxStabledBlock.getHeader().getHash().toByteArray())));

			BlockInfo maxConnectedBlock = blockChainDA.loadMaxConnectBlock();
			if (maxConnectedBlock == null) {
				log.info("node init   ===>   not found max connect block, use stable block");
				// 如果没有找到连接状态的块，则可能的情况是区块链第一次启动，只存在创世块。或者因为意外重启导致的数据丢失
				maxConnectedBlock = maxStabledBlock;
			} else {
				// 往前推移几块，避免出现异步存储造成的数据错误
				BlockInfo[] blks = blockChainDA
						.getBlockByHeight(maxConnectedBlock.getHeader().getHeight() - MCoreConfig.PREV_BLOCK_COUNT);
				log.info(String.format(
						"node init   ===>   current max connect block [{%s}], load max connect block [{%s}]",
						maxConnectedBlock.getHeader().getHeight(), maxConnectedBlock.getHeader().getHeight() - 5));

				if (blks.length == 0) {
					maxConnectedBlock = maxStabledBlock;
				} else {
					maxConnectedBlock = blks[0];
				}
			}
			maxConnectBlock = maxConnectedBlock;
			maxConnectHeight = maxConnectedBlock.getHeader().getHeight();

			// 从最大的连接块开始，还原所有的连接状态的区块到buffer中，直到稳定块结束
			long maxBlockHeight = maxConnectedBlock.getHeader().getHeight();
			long minBlockHeight = maxBlockHeight - MCoreConfig.BLOCK_STABLE_COUNT;
			if (minBlockHeight < 0) {
				minBlockHeight = 0;
			}
			if (minBlockHeight > maxStabledBlock.getHeader().getHeight()) {
				minBlockHeight = maxStabledBlock.getHeader().getHeight();
			}

			if (maxBlockHeight == 0 && minBlockHeight == 0) {
				// 节点第一次启动，只存在创世块的情况
				maxStableHeight = maxStabledBlock.getHeader().getHeight();
				maxStableBlock = maxStabledBlock;
			} else {
				while (maxBlockHeight >= minBlockHeight) {
					BlockInfo[] blocks = blockChainDA.getBlockByHeight(maxBlockHeight);
					for (BlockInfo block : blocks) {
						BlockMessage bm = new BlockMessage(block.getHeader().getHash().toByteArray(),
								block.getHeader().getParentHash().toByteArray(), block.getHeader().getHeight(), block);
						bm.setStatus(BlockMessageStatusEnum.CONNECT);
						blockMessageBuffer.put(bm);

						if (log.isDebugEnabled()) {
							log.debug(String.format(
									"node init   ===>   connect block. height[{%-10s}] org.mos.mcore.crypto.hash[{%s}]",
									bm.getHeight(), crypto.bytesToHexStr(bm.getHash())));
						}
					}
					maxBlockHeight -= 1;
				}
			}
			chainConfig.setNodeId(vId);
			chainConfig.setNodeStart(true);
			try {
				stateTrie.setRoot(maxConnectBlock.getHeader().getStateRoot().toByteArray());
				if (chainConfig.getConfigAccount() == null) {
					try (InputStream genesisJsonStream = new FileInputStream(
							new File(MCoreConfig.ENVIRONMENT_GENENSIS_FILE_PATH))) {
						String json = new String(ByteStreams.toByteArray(genesisJsonStream));

						GenesisBlock.Builder oGenesisBlock = GenesisBlock.newBuilder();
						JsonFormat oJsonFormat = new JsonFormat();
						oJsonFormat.merge(new ByteArrayInputStream(json.getBytes()), oGenesisBlock);

						chainConfig.setConfigAccount(new ChainConfigWrapper(
								accountHandler.getAccount(oGenesisBlock.getChainconfig().getAddress())));

					}

				}

			} catch (Exception e) {
				// e.printStackTrace();
				log.error("get error in set root,height = " + maxConnectBlock.getHeader().getHeight() + ",root="
						+ crypto.bytesToHexStr(maxConnectBlock.getHeader().getStateRoot().toByteArray()));
			}
			// dispatcher.getExecutorService("default").execute(new StatRunner());

			log.debug(String.format(
					"node init   ===>   max block org.mos.mcore.crypto.hash[%s] height[%-10s] stateroot[{}]",
					crypto.bytesToHexStr(maxConnectBlock.getHeader().getHash().toByteArray()),
					maxConnectBlock.getHeader().getHeight(),
					crypto.bytesToHexStr(maxConnectBlock.getHeader().getStateRoot().toByteArray())));
			log.debug("node init   ===>   complete");

		} catch (Exception e) {
			log.error("error on node start", e);
		}
	}

	private void loadGenesisDb(String path) throws Exception {
		try (InputStream genesisJsonStream = new FileInputStream(new File(path))) {
			String json = new String(ByteStreams.toByteArray(genesisJsonStream));

			GenesisBlock.Builder oGenesisBlock = GenesisBlock.newBuilder();
			JsonFormat oJsonFormat = new JsonFormat();
			oJsonFormat.merge(new ByteArrayInputStream(json.getBytes()), oGenesisBlock);

			byte[] pubKey = crypto.signatureToKey(oGenesisBlock.getBody().toByteArray(),
					crypto.hexStrToBytes(oGenesisBlock.getSignature()));
			if (!crypto.verify(pubKey, oGenesisBlock.getBody().toByteArray(),
					crypto.hexStrToBytes(oGenesisBlock.getSignature()))) {
				throw new Exception("unknown genesis file");
			} else {
				byte[] addr = crypto.signatureToAddress(oGenesisBlock.getBody().toByteArray(),
						crypto.hexStrToBytes(oGenesisBlock.getSignature()));
				// TODO coinbase
				if (!crypto.bytesToHexStr(addr).equals("3c1ea4aa4974d92e0eabd5d024772af3762720a0")) {
					throw new Exception("unknown genesis file generator");
				}
			}

			BlockInfo.Builder oBlockInfo = BlockInfo.newBuilder();
			BlockHeader.Builder oBlockHeader = oBlockInfo.getHeaderBuilder();

			oBlockHeader.setHeight(oGenesisBlock.getBody().getHeight());
			oBlockHeader.setParentHash(ByteString.copyFrom(BytesHelper.EMPTY_BYTE_ARRAY));
			oBlockHeader.setTimestamp(oGenesisBlock.getBody().getTimestamp());
			oBlockHeader.setHeight(oGenesisBlock.getBody().getHeight());
			oBlockHeader.setExtraData(ByteString.copyFrom(oGenesisBlock.getBody().getExtraData().getBytes()));
			this.stateTrie.clear();

			oGenesisBlock.getBody().getAccountsMap().forEach((key, value) -> {
				AccountInfo.Builder oAccount = accountHandler.createAccount(
						ByteString.copyFrom(crypto.hexStrToBytes(key)),
						(value.getName() == null ? ByteString.EMPTY : ByteString.copyFromUtf8(value.getName())),
						ByteString.copyFrom(BytesHelper.bigIntegerToBytes(new BigInteger(value.getBalance()))), null,
						null);
				if (value.getNonce() > 0) {
					oAccount.setNonce(value.getNonce());
				}
				log.error("add default v=" + oAccount);
				if (value.getStoragesMap() != null && value.getStoragesMap().size() > 0) {
					StorageTrie storage = new StorageTrie(mcore.stateTrie);
					value.getStoragesMap().forEach((storageKey, storageValue) -> {
						storage.put(crypto.hexStrToBytes(storageKey), crypto.hexStrToBytes(storageValue));
					});
					oAccount.setStorageTrieRoot(ByteString.copyFrom(storage.getRootHash(oBlockHeader.getHeight())));
				}
				this.stateTrie.put(oAccount.getAddress().toByteArray(), oAccount.build().toByteArray());
			});
			ByteString chainConfigAddr = ByteString
					.copyFrom(crypto.hexStrToBytes("0000000000000000000000000000000000000001"));
			if (!oGenesisBlock.getChainconfig().getAddress().isEmpty()) {
				chainConfigAddr = oGenesisBlock.getChainconfig().getAddress();
			}
			List<TransactionInfo> txs = new ArrayList<>();

			// new Thread(new Runnable() {
			//
			// @Override
			// public void run() {
			// while(!chainConfig.isNodeStart()) {
			// try {
			// Thread.sleep(100);
			// } catch (InterruptedException e) {
			// e.printStackTrace();
			// }
			// }
			oGenesisBlock.getBody().getTxsMap().forEach((key, value) -> {
				try {
					TransactionBody body = TransactionBody.newBuilder()
							.setAddress(ByteString.copyFrom(crypto.hexStrToBytes(value.getAddress())))
							.setNonce(value.getNonce())
							.setCodeData(ByteString.copyFrom(Hex.decodeHex(value.getCodeData().replaceFirst("0x", ""))))
							.setInnerCodetype(value.getInnerCodetype()).setTimestamp(value.getTimestamp()).build();
					TransactionInfo.Builder txinfo = TransactionInfo.newBuilder().setBody(body);

					txinfo.setHash(ByteString.copyFrom(Hex.decodeHex(key.replaceFirst("0x", ""))));
					txinfo.setSignature(ByteString.copyFrom(crypto.hexStrToBytes(value.getSign())));
					TransactionMessage tm = transactionHandler.createInitTransaction(txinfo);
					// oBlockInfo.getBodyBuilder().addTxs(txinfo);
					txs.add(txinfo.build());
					log.info("create init tx:hash=" + Hex.encodeHexString(tm.getTx().getHash().toByteArray()));
				} catch (Exception e) {
					log.error("error in init ex", e);
				}
			});

			AccountInfo.Builder configAccount = accountHandler.createAccount(chainConfigAddr);
			chainConfig.setConfigAccount(new ChainConfigWrapper(configAccount, oGenesisBlock.getChainconfig()));
			this.stateTrie.put(chainConfig.getConfigAddress().toByteArray(),
					chainConfig.getConfigAccount().build(0).toByteArray());

			oBlockHeader.setStateRoot(ByteString.copyFrom(this.stateTrie.getRootHash(0)));
			((StateTrie) stateTrie).flushPutCache();

			oBlockHeader.setReceiptRoot(ByteString.copyFrom(BytesHelper.EMPTY_BYTE_ARRAY));
			// oBlockHeader.setHash(ByteString.copyFrom(crypto.hexStrToBytes(genesisJson.getHash())));
			oBlockHeader
					.setParentHash(ByteString.copyFrom(crypto.hexStrToBytes(oGenesisBlock.getBody().getParentHash())));
			blockHandler.gensis = oGenesisBlock.build();
			BlockInfo genblockInfo = blockHandler.createBlock(txs, null, "", "");

			addBlock(genblockInfo);
			tryConnectBlock(genblockInfo);
			tryStableBlock(genblockInfo);
			// addBlock(oBlockInfo.build());
			// tryConnectBlock(oBlockInfo.build());
			// tryStableBlock(oBlockInfo.build());
		} catch (Exception e) {
			log.error("", e);
			throw e;
		}
	}

	public BlockInfo getLastConnectedBlock() {
		return maxConnectBlock;
	}

	public long getLastConnectedBlockHeight() {
		return maxConnectHeight;
	}

	public BlockInfo getLastStableBlock() {
		return maxStableBlock;
	}

	public BlockInfo getLastReceiveBlock() {
		return maxReceiveBlock;
	}

	public BlockInfo getBlockByHash(byte[] hash) {
		try {
			return blockChainDA.getBlockByHash(hash);
		} catch (Exception e) {
			log.error("根据hash获取区块异常 org.mos.mcore.crypto.hash[{}]", crypto.bytesToHexStr(hash), e);
		}
		return null;
	}

	public BlockInfo getBlockByHeight(long height) {
		BlockInfo[] bi = listBlockByHeight(height);
		if (bi == null) {
			return null;
		} else if (bi.length == 0) {
			return null;
		}
		{
			return bi[0];
		}
	}

	public BlockInfo[] listConnectBlocksByHeight(long height) {
		List<BlockMessage> list = blockMessageBuffer.get(height, true);
		if (list == null || list.size() == 0) {
			return new BlockInfo[] {};
		}
		BlockInfo[] ret = new BlockInfo[list.size()];
		for (int i = 0; i < list.size(); i++) {
			ret[i] = list.get(i).getBlock();
		}
		return ret;
	}

	public BlockInfo[] listBlockByHeight(long height) {
		try {
			return blockChainDA.getBlockByHeight(height);
		} catch (Exception e) {
			log.error("error on get block by height [{}]", height, e);
		}
		return null;
	}

	public synchronized BlockMessageMark addBlock(BlockInfo block) throws Exception {
		return addBlock(block, false);
	}

	public synchronized BlockMessageMark addBlock(BlockInfo block, boolean overrided) throws Exception {
		BlockMessageMark oBlockMessageMark = new BlockMessageMark();
		log.error("blockheight:" + block.getHeader().getHeight() + ";addBlock() start--maxReceiveHeight:"
				+ maxReceiveHeight + ";maxConnectHeight:" + maxConnectHeight + ";maxStableHeight:" + maxStableHeight
				+ ";lastConnectHeight:" + lastConnectHeight);

		if (maxReceiveHeight < block.getHeader().getHeight()) {
			maxReceiveHeight = block.getHeader().getHeight();
			maxReceiveBlock = block;
		}
		byte[] hash = block.getHeader().getHash().toByteArray();
		byte[] parentHash = block.getHeader().getParentHash().toByteArray();
		long height = block.getHeader().getHeight();
		BlockMessage bm = null;
		if (hash != null && hash.length >= 0) {
			bm = blockMessageBuffer.get(hash);
		}
		if (bm != null && bm.getStatus().equals(BlockMessageStatusEnum.CONNECT) && !overrided) {
			log.error("blockheight:" + block.getHeader().getHeight() + ";is CONNECT");
			oBlockMessageMark.setMark(BlockMessageMarkEnum.CONNECTED);
			if (!overrided) {
				return oBlockMessageMark;
			}
		}

		if (bm == null || overrided) {
			bm = new BlockMessage(hash, parentHash, height, block);
		}

		if (hash.length > 0) {
			blockMessageBuffer.put(bm);
		}
		blockChainDA.saveBlock(block);
		if (bm.getHeight() == 0) {
			// 如果是创世块，不放到buffer中，直接保存db，更新maxStableBlock
			if (maxConnectHeight < block.getHeader().getHeight()) {
				maxConnectHeight = block.getHeader().getHeight();
				maxConnectBlock = block;
			}
			if (maxStableHeight < block.getHeader().getHeight()) {
				maxStableHeight = block.getHeader().getHeight();
				maxStableBlock = block;
			}
			bm.setStatus(BlockMessageStatusEnum.CONNECT);

			oBlockMessageMark.setMark(BlockMessageMarkEnum.APPLY);
		} else if (bm.getHeight() == 1) {
			// 如果是第一块，因为创世块不在buffer里，所以只要上一块是第0块都直接apply
			BlockInfo parentBlock = blockChainDA.getBlockByHash(parentHash);
			if (parentBlock != null && parentBlock.getHeader().getHeight() == 0) {
				oBlockMessageMark.setMark(BlockMessageMarkEnum.APPLY);
				bm.setStatus(BlockMessageStatusEnum.CONNECT);
			} else {
				oBlockMessageMark.setMark(BlockMessageMarkEnum.ERROR);
			}
		} else {
			BlockMessage parentBM = blockMessageBuffer.get(parentHash);
			if (parentBM == null) {
				BlockInfo dbBlockInfo = null;
				if (height < lastConnectHeight) {
					dbBlockInfo = getBlockByHash(parentHash);
				}
				if (dbBlockInfo != null) {
					oBlockMessageMark.setMark(BlockMessageMarkEnum.APPLY);
				} else {
					log.error("blockheight:" + block.getHeader().getHeight() + ";parentBM == null,parentHash="
							+ Hex.encodeHexString(parentHash));
					List<BlockMessage> prevHeightBlock = blockMessageBuffer.get(height - 1, true);
					if (prevHeightBlock.size() > 0) {
						// 如果存在上一个高度的block，并且已经connect，但是hash与parentHash不一致，则继续请求上一个高度的block
						// if (prevHeightBlock.)
						if (prevHeightBlock.stream()
								.filter(prevBM -> prevBM.getStatus().equals(BlockMessageStatusEnum.CONNECT))
								.count() > 0) {
							log.info("blockheight:" + block.getHeader().getHeight()
									+ ";exist CONNECTED prevHeightBlock");
							oBlockMessageMark.setMark(BlockMessageMarkEnum.EXISTS_PREV);
						} else {
							log.info("blockheight:" + block.getHeader().getHeight()
									+ ";exist not CONNECTED prevHeightBlock");
							oBlockMessageMark.setMark(BlockMessageMarkEnum.CACHE);
						}
					} else if (maxStableBlock != null
							&& BytesComparisons.equal(maxStableBlock.getHeader().getHash().toByteArray(),
									block.getHeader().getParentHash().toByteArray())
							&& maxStableBlock.getHeader().getHeight() == block.getHeader().getHeight() - 1) {
						// 如果上一个高度并且hash一致的block不在buffer里，而是稳定块（重启节点可能会发生）,则允许apply
						oBlockMessageMark.setMark(BlockMessageMarkEnum.APPLY);
					} else {
						log.info("blockheight:" + block.getHeader().getHeight() + ";not exist prevHeightBlock");
						// 如果找不到上一个block，则先缓存下来，等待上一个块同步过来后再次尝试apply
						oBlockMessageMark.setMark(BlockMessageMarkEnum.CACHE);
					}
				}
			} else if (parentBM != null && !parentBM.getStatus().equals(BlockMessageStatusEnum.CONNECT)) {
				log.error("blockheight:" + block.getHeader().getHeight() + ";parentBM exist,but not connected!");
				// 如果上一个block存在，但是还没有连接到链上，则继续请求上一个高度的block，等待上一个块连接上之后再尝试apply
				oBlockMessageMark.setMark(BlockMessageMarkEnum.CACHE);
			} else {
				oBlockMessageMark.setMark(BlockMessageMarkEnum.APPLY);
			}
		}

		// log.info("blockheight:" + block.getHeader().getHeight() + ";bm.height:" +
		// bm.getHeight());
		if (hash == null || hash.length == 0) {

			log.error("blockheight:" + block.getHeader().getHeight() + ";addBlock() end--maxReceiveHeight:"
					+ maxReceiveHeight + ";maxConnectHeight:" + maxConnectHeight + ";maxStableHeight:" + maxStableHeight
					+ ";lastConnectHeight:" + lastConnectHeight + ",hashis null." + ",ret="
					+ oBlockMessageMark.toString().replaceAll("\n", ""));
		} else {
			log.error("blockheight:" + block.getHeader().getHeight() + ";addBlock() end--maxReceiveHeight:"
					+ maxReceiveHeight + ";maxConnectHeight:" + maxConnectHeight + ";maxStableHeight:" + maxStableHeight
					+ ";lastConnectHeight:" + lastConnectHeight + ",org.mos.mcore.crypto.hash="
					+ Hex.encodeHexString(hash) + ",ret=" + oBlockMessageMark.toString().replaceAll("\n", ""));

		}

		return oBlockMessageMark;
	}

	public synchronized BlockMessageMark tryAddBlock(BlockInfo block) {
		BlockMessageMark oBlockMessageMark = new BlockMessageMark();
		byte[] parentHash = block.getHeader().getParentHash().toByteArray();
		long height = block.getHeader().getHeight();

		BlockMessage parentBM = blockMessageBuffer.get(parentHash);
		if (parentBM == null) {
			List<BlockMessage> prevHeightBlock = blockMessageBuffer.get(height - 1, true);
			if (prevHeightBlock.size() > 0) {
				oBlockMessageMark.setMark(BlockMessageMarkEnum.EXISTS_PREV);
			} else {
				oBlockMessageMark.setMark(BlockMessageMarkEnum.DROP);
			}
		} else if (parentBM != null && !parentBM.getStatus().equals(BlockMessageStatusEnum.CONNECT)) {
			// 如果上一个block存在，但是还没有连接到链上，则先缓存当前节点，等待上一个块连接上之后再尝试apply
			oBlockMessageMark.setMark(BlockMessageMarkEnum.CACHE);
		} else if (parentBM != null && parentBM.getStatus().equals(BlockMessageStatusEnum.CONNECT)) {
			// 如果上一个block存在，并且已经链接上，则apply该block
			oBlockMessageMark.setMark(BlockMessageMarkEnum.APPLY);
		} else {
			oBlockMessageMark.setMark(BlockMessageMarkEnum.CACHE);
		}
		return oBlockMessageMark;
	}

	public synchronized BlockMessageMark tryConnectBlock(BlockInfo block) throws Exception {
		BlockMessageMark oBlockMessageMark = new BlockMessageMark();

		byte[] hash = block.getHeader().getHash().toByteArray();
		long height = block.getHeader().getHeight();

		if (height < 1) {
			// 如果block的高度为1，保存block到db中，buffer中设置block状态为已链接
			// blockChainDA.saveBlock(block);

			log.debug("connect block=" + block.getHeader().getHeight() + " org.mos.mcore.crypto.hash="
					+ crypto.bytesToHexStr(block.getHeader().getHash().toByteArray()));

			blockChainDA.connectBlock(block);
			blockMessageBuffer.get(hash).setStatus(BlockMessageStatusEnum.CONNECT);

			if (maxConnectHeight < block.getHeader().getHeight()) {
				maxConnectHeight = block.getHeader().getHeight();
				maxConnectBlock = block;
			}

			lastConnectHeight = block.getHeader().getHeight();
			lastConnectBlock = block;

			oBlockMessageMark.setMark(BlockMessageMarkEnum.DONE);
			return oBlockMessageMark;
		} else {
			if (blockMessageBuffer.containsHash(hash)) {

				log.info("connect block=" + block.getHeader().getHeight() + ",hash="
						+ crypto.bytesToHexStr(block.getHeader().getHash().toByteArray()) + ",buffermessage.hashcount="
						+ blockMessageBuffer.getHashCount() + ",buffermessage.heightcount="
						+ blockMessageBuffer.getHeightCount());

				if (blkSubscriber != null) {
					blkSubscriber.notifyAll(block);
				}

				// 如果buffer中存在该block，则直接链接（如果块已经变的稳定，则会自动移出buffer）
				blockChainDA.connectBlock(block);
				blockMessageBuffer.get(hash).setStatus(BlockMessageStatusEnum.CONNECT);

				if (maxConnectHeight < block.getHeader().getHeight()) {
					maxConnectHeight = block.getHeader().getHeight();
					maxConnectBlock = block;
				}

				lastConnectHeight = block.getHeader().getHeight();
				lastConnectBlock = block;

				// 如果在buffer存在稳定的block，则设置该block为稳定状态，从buffer中移除
				int count = 0;
				BlockMessage stableBM = null;
				BlockMessage parentBM = blockMessageBuffer.get(block.getHeader().getParentHash().toByteArray());
				while (parentBM != null && count < MCoreConfig.BLOCK_STABLE_COUNT
						&& parentBM.getStatus().equals(BlockMessageStatusEnum.CONNECT)) {
					count += 1;
					stableBM = parentBM;
					parentBM = blockMessageBuffer.get(parentBM.getParentHash());
				}
				if (stableBM != null && stableBM.getStatus().equals(BlockMessageStatusEnum.CONNECT)
						&& count >= MCoreConfig.BLOCK_STABLE_COUNT) {
					tryStableBlock(stableBM.getBlock());
				}

				List<BlockMessage> childs = blockMessageBuffer.get(height + 1, false);
				if (childs.size() > 0) {
					// 判断hash
					for (BlockMessage childBM : childs) {
						if (BytesComparisons.equal(childBM.getParentHash(),
								block.getHeader().getHash().toByteArray())) {
							oBlockMessageMark.addChildBlock(childBM);
						}
					}
					if (oBlockMessageMark.getChildBlock().size() > 0) {
						oBlockMessageMark.setMark(BlockMessageMarkEnum.APPLY_CHILD);
					} else {
						oBlockMessageMark.setMark(BlockMessageMarkEnum.DONE);
					}
				} else {
					oBlockMessageMark.setMark(BlockMessageMarkEnum.DONE);
				}
				return oBlockMessageMark;
			} else {

				// 从数据库中抓去
				BlockInfo[] dbblks = listBlockByHeight(block.getHeader().getHeight());
				if (dbblks != null) {
					for (BlockInfo dbblk : dbblks) {
						if (dbblk.getHeader().getHash().equals(block.getHeader().getHash())) {
							oBlockMessageMark.setMark(BlockMessageMarkEnum.DONE);
							return oBlockMessageMark;
						}
					}
				}

				throw new BlockNotFoundInBufferException(String.format(
						"error on connect block. not found target block in block buffer. height[%-10s] org.mos.mcore.crypto.hash[%s]",
						block.getHeader().getHeight(),
						crypto.bytesToHexStr(block.getHeader().getHash().toByteArray())));
			}
		}
	}

	public synchronized BlockMessageMark tryStableBlock(BlockInfo block) throws Exception {
		BlockMessageMark oBlockMessageMark = new BlockMessageMark();
		if (block.getHeader().getHeight() <= maxStableHeight + 1) {
			blockChainDA.stableBlock(block);
			log.error("stable block " + block.getHeader().getHeight() + " hash="
					+ crypto.bytesToHexStr(block.getHeader().getHash().toByteArray()) + ",maxStableHeight="
					+ maxStableHeight + ",maxConnectHeight=" + maxConnectHeight);
			blockMessageBuffer.remove(block.getHeader().getHash().toByteArray());

			if (maxConnectHeight < block.getHeader().getHeight()) {
				maxConnectHeight = block.getHeader().getHeight();
				maxConnectBlock = block;
			}
			if (maxStableHeight < block.getHeader().getHeight()) {
				maxStableHeight = block.getHeader().getHeight();
				maxStableBlock = block;
			}
			oBlockMessageMark.setMark(BlockMessageMarkEnum.DONE);
		} else {
			log.error("cache stable block " + block.getHeader().getHeight() + " hash="
					+ crypto.bytesToHexStr(block.getHeader().getHash().toByteArray()) + ",maxStableHeight="
					+ maxStableHeight + ",maxConnectHeight=" + maxConnectHeight);
			oBlockMessageMark.setMark(BlockMessageMarkEnum.CACHE);
		}
		return oBlockMessageMark;
	}

	/**
	 * 回滚区块链到指定的高度
	 * <p>
	 * 不允许回滚到安全块以内的高度
	 * 
	 * @param height
	 * @return
	 * @throws BlockRollbackNotAllowException
	 */
	public BlockInfo rollBackTo(long targetHeight) throws Exception {
		if (log.isInfoEnabled()) {
			log.info("roll back to " + targetHeight);
		}
		if (targetHeight >= maxConnectHeight) {
			return maxConnectBlock;
		} else if (targetHeight < maxConnectHeight && targetHeight >= 0) {
			BlockMessage bm = null;
			log.debug("maxConnectHeight=" + maxConnectHeight);
			for (long i = maxConnectHeight; i >= targetHeight; i--) {
				if (i >= 0) {
					if (!blockMessageBuffer.containsHeight(i)) {
						BlockInfo[] blocks = blockChainDA.getBlockByHeight(i);
						log.debug("load from db height=" + i + " size=" + blocks.length);
						for (int j = 0; j < blocks.length; j++) {
							BlockMessage newBM = new BlockMessage(blocks[j].getHeader().getHash().toByteArray(),
									blocks[j].getHeader().getParentHash().toByteArray(),
									blocks[j].getHeader().getHeight(), blocks[j]);
							newBM.setStatus(BlockMessageStatusEnum.UNKNOWN);
							blockMessageBuffer.put(newBM);

							bm = newBM;
						}
					} else {
						List<BlockMessage> blocks = blockMessageBuffer.get(i);
						for (BlockMessage blockInBuffer : blocks) {
							if (blockInBuffer.getStatus().equals(BlockMessageStatusEnum.CONNECT)) {
								if (blockInBuffer.getHeight() != targetHeight) {
									blockInBuffer.setStatus(BlockMessageStatusEnum.UNKNOWN);
									//
									// StatRunner.processTxCounter
									// .addAndGet(-blockInBuffer.getBlock().getHeader().getTxHashsCount());
								}
							}
							bm = blockInBuffer;
						}
					}
				} else {
					break;
				}
			}

			if (bm != null) {
				maxConnectHeight = bm.getHeight();
				maxConnectBlock = bm.getBlock();
				tryConnectBlock(maxConnectBlock);
				// blockChainDA.connectBlock(maxConnectBlock);
				if (maxConnectHeight - MCoreConfig.BLOCK_STABLE_COUNT <= 0) {
					blockChainDA.stableBlock(blockChainDA.getBlockByHeight(0)[0]);
				} else {
					blockChainDA.stableBlock(
							blockChainDA.getBlockByHeight(maxConnectHeight - MCoreConfig.BLOCK_STABLE_COUNT)[0]);
				}
				return bm.getBlock();
			} else {
				return null;
			}
		} else {
			throw new BlockRollbackNotAllowException(
					String.format("cannot roll back to targe height. current[%-10s] stable[%-10s] rollback[%-10s]",
							maxConnectHeight, maxStableHeight, targetHeight));
		}
	}
}
