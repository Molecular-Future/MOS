package org.mos.mcore.handler;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import onight.osgi.annotation.NActorProvider;
import onight.tfw.mservice.ThreadContext;
import onight.tfw.ntrans.api.ActorService;
import onight.tfw.ntrans.api.annotation.ActorRequire;
import onight.tfw.otransio.api.PacketHelper;
import onight.tfw.otransio.api.beans.FramePacket;
import onight.tfw.outils.conf.PropHelper;
import onight.tfw.outils.pool.ReusefulLoopPool;
import org.apache.commons.codec.binary.Hex;
import org.apache.felix.ipojo.annotations.Instantiate;
import org.apache.felix.ipojo.annotations.Provides;
import org.mos.mcore.bean.*;
import org.mos.mcore.bean.BlockMessageMark.BlockMessageMarkEnum;
import org.mos.mcore.bean.BlockSyncMessage.BlockSyncCodeEnum;
import org.mos.mcore.bean.TransactionInfoWrapper.PreDefineRunner;
import org.mos.mcore.concurrent.AccountInfoWrapper;
import org.mos.mcore.concurrent.ChainConfigWrapper;
import org.mos.mcore.config.StatRunner;
import org.mos.mcore.exception.BlockNotFoundInBufferException;
import org.mos.mcore.model.Account.AccountInfo;
import org.mos.mcore.model.Block.BlockHeader;
import org.mos.mcore.model.Block.BlockInfo;
import org.mos.mcore.model.Block.BlockMiner;
import org.mos.mcore.model.GenesisBlockOuterClass.GenesisBlock;
import org.mos.mcore.model.Transaction.TransactionInfo;
import org.mos.mcore.tools.bytes.BytesComparisons;
import org.mos.mcore.tools.bytes.BytesHelper;
import org.mos.mcore.trie.NullRootNodeException;
import org.mos.mcore.trie.StateTrieNode;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@NActorProvider
@Provides(specifications = { ActorService.class }, strategy = "SINGLETON")
@Instantiate(name = "bc_block")
@Slf4j
@Data
public class BlockHandler implements ActorService {

	@ActorRequire(name = "MCoreServices", scope = "global")
	MCoreServices mcore;

	private TransactionExecutorSeparator oTransactionExecutorSeparator = new TransactionExecutorSeparator();

	PropHelper prop = new PropHelper(null);
	int BLK_NUM_SPLIT = prop.get("org.mos.mcore.backend.block.split.num", 60);

	public BlockHandler() {
	}

	// public BlockInfo createBlock(int containTxCount, int minConfirm, byte[]
	// extraData, String term, String bits) {
	// List<TransactionInfo> txs =
	// mcore.transactionHandler.getWaitBlockTx(containTxCount, minConfirm);
	// if (txs.size() != 0) {
	// txs.sort((t1, t2) -> {
	// if (t1.getBody().getFeeHi() > t2.getBody().getFeeHi()) {
	// return 1;
	// }
	// if (t1.getBody().getFeeHi() < t2.getBody().getFeeHi()) {
	// return -1;
	// }
	// if (t1.getBody().getFeeLow() > t2.getBody().getFeeLow()) {
	// return 1;
	// }
	// if (t1.getBody().getFeeLow() < t2.getBody().getFeeLow()) {
	// return -1;
	// }
	// int n = t1.getBody().getNonce() - t2.getBody().getNonce();
	// if (n != 0) {
	// return n;
	// }
	// if (t1.getBody().getTimestamp() > t2.getBody().getTimestamp()) {
	// return -1;
	// }
	// return 1;
	// });
	// }
	// return createBlock(txs, extraData, term, bits);
	// }

	FramePacket fp = PacketHelper.genSyncPack("createblock", "sys.blocks", "");

	public AtomicBoolean lockCreateBlock = new AtomicBoolean(false);

	public GenesisBlock gensis;

	public static void main(String[] args) {
		List<Integer> txs = new ArrayList<>();
		txs.add(1);
		txs.add(3);
		txs.add(2);
		txs.sort((t1, t2) -> {
			int n = t1 - t2;
			if (n > 0) {
				return 1;
			}
			if (n < 0) {
				return -1;
			}
			return 0;
		});
		System.out.println("txss="+txs);
		
	}

	public BlockInfo createBlock(List<TransactionInfo> txs, byte[] extraData, String term, String bits) {
		Thread.currentThread().setName("bc-block-create");
		try {
			long start = System.currentTimeMillis();
			if (txs.size() != 0) {
				txs.sort((t1, t2) -> {
					int n = t1.getBody().getNonce() - t2.getBody().getNonce();
					if (n > 0) {
						return 1;
					} else if (n < 0) {
						return -1;
					}

					if (t1.getBody().getFeeHi() > t2.getBody().getFeeHi()) {
						return -1;
					}
					if (t1.getBody().getFeeHi() < t2.getBody().getFeeHi()) {
						return 1;
					}
					if (t1.getBody().getFeeLow() > t2.getBody().getFeeLow()) {
						return -1;
					}
					if (t1.getBody().getFeeLow() < t2.getBody().getFeeLow()) {
						return 1;
					}

					if (t1.getBody().getTimestamp() > t2.getBody().getTimestamp()) {
						return -1;
					}
					return 0;
				});
			}
			long sortedEnd = System.currentTimeMillis();
			log.error("block create sort time=" + (sortedEnd - start));
			// lockCreateBLock.lock();// 同时只有一个在创建块
			while (!lockCreateBlock.compareAndSet(false, true)) {
				Thread.sleep(10);
			}

			BlockInfo.Builder oBlockInfo = BlockInfo.newBuilder();
			BlockHeader.Builder oBlockHeader = oBlockInfo.getHeaderBuilder();
			BlockMiner.Builder oBlockMiner = oBlockInfo.getMinerBuilder();

			BlockInfo oBestBlockEntity = mcore.chainHandler.getLastConnectedBlock();
			if (oBestBlockEntity == null) {
				oBestBlockEntity = mcore.chainHandler.getLastStableBlock();
			}
			long currentTimestamp = System.currentTimeMillis();
			if (oBestBlockEntity == null && gensis != null) {
				oBestBlockEntity = BlockInfo.newBuilder()
						.setHeader(BlockHeader.newBuilder()
								.setHash(ByteString.copyFrom(Hex.decodeHex(gensis.getBody().getHash())))
								.setTimestamp(gensis.getBody().getTimestamp()).setHeight(0)
								.setStateRoot(ByteString.copyFrom(Hex.decodeHex(gensis.getBody().getParentHash()))))
						.build();
				oBlockHeader.setTimestamp(gensis.getBody().getTimestamp());
				oBlockMiner.setNid("0000000000000000000000000000000000000000");
				oBlockMiner.setAddress(ByteString.copyFrom(Hex.decodeHex("0000000000000000000000000000000000000000")));
				oBlockHeader.setHeight(0);
			} else {
				oBlockHeader.setTimestamp(System.currentTimeMillis() == oBestBlockEntity.getHeader().getTimestamp()
						? oBestBlockEntity.getHeader().getTimestamp() + 1
						: currentTimestamp);
				oBlockMiner.setNid(mcore.chainConfig.getNodeId());
				oBlockMiner.setAddress(mcore.chainConfig.getMiner_account_address_bs());
				oBlockHeader.setHeight(oBestBlockEntity.getHeader().getHeight() + 1);
			}
			oBlockHeader.setParentHash(oBestBlockEntity.getHeader().getHash());

			if (extraData != null) {
				oBlockHeader.setExtraData(ByteString.copyFrom(extraData));
			}

			txs.sort(new Comparator<TransactionInfo>() {
				@Override
				public int compare(TransactionInfo o1, TransactionInfo o2) {
					return Integer.compare(o1.getBody().getNonce(), o2.getBody().getNonce());
				}
			});

			// int syncpos = oTransactionExecutorSeparator.getBucketSize();
			oTransactionExecutorSeparator.reset();
			for (int i = 0; i < txs.size(); i++) {
				// oBlockHeader.addTxexecbulkindex(syncpos);

				int bulkindx = oTransactionExecutorSeparator.clearTransaction(txs.get(i).getBody().getAddress());
				oBlockHeader.addTxexecbulkindex(bulkindx);
				oBlockHeader.addTxHashs(txs.get(i).getHash());
			}
			// oBlockMiner.setReward(ByteString.copyFrom(BytesHelper.intToBytes(mcore.chainConfig.getBlock_reward())));
			oBlockMiner.setTerm(term);
			oBlockMiner.setBits(bits);

			oBlockInfo.setHeader(oBlockHeader);
			oBlockInfo.setMiner(oBlockMiner);
			oBlockInfo.setVersion(mcore.chainConfig.getBlockVersion());

			// 异步处理打块，立即把线程交给dpos广播这个区块了
			final BlockInfo finalBestBlockEntity = oBestBlockEntity;
			if (MCoreConfig.FAST_APPLY) {
				oBlockInfo.getHeaderBuilder().clearHash();
			}
			Runnable subRunner = new Runnable() {
				public void run() {
					try {
						// lockCreateBLock.lock();// 同时只有一个在创建块
						processBlock(oBlockInfo, finalBestBlockEntity, txs);
						if (applyPostRunner != null) {
							mcore.dispatcher.getExecutorService("blockproc").execute(applyPostRunner);
						}
						byte[] blockContent = BytesHelper.appendBytes(
								oBlockInfo.getHeaderBuilder().clearHash().build().toByteArray(),
								oBlockInfo.getMiner().toByteArray());
						byte[] originhash = mcore.crypto.sha256(blockContent);
//						byte asignhash[] = new byte[originhash.length + 1];
//						asignhash[0] = (byte) ((oBlockInfo.getHeader().getHeight() / BLK_NUM_SPLIT) % 256);
//						System.arraycopy(originhash, 0, asignhash, 1, originhash.length);

//						oBlockInfo.getHeaderBuilder().setHash(ByteString.copyFrom(asignhash));
						oBlockInfo.getHeaderBuilder().setHash(ByteString.copyFrom(originhash));
						final BlockInfo newBlock = oBlockInfo.build();

						BlockMessageMark addMark = mcore.chainHandler.addBlock(newBlock);
						if (addMark.getMark() == BlockMessageMarkEnum.APPLY) {
							BlockMessageMark connectMark = mcore.chainHandler.tryConnectBlock(newBlock);

							log.error(
									"new block  ===>   org.mos.mcore.crypto.hash[{}->{}] height[{}] stateroot=[{}],txcount[{}] status[{}]",
									mcore.crypto.bytesToHexStr(oBlockInfo.getHeader().getHash().toByteArray()),
									mcore.crypto.bytesToHexStr(oBlockInfo.getHeader().getParentHash().toByteArray()),
									oBlockInfo.getHeader().getHeight(),
									mcore.crypto.bytesToHexStr(oBlockInfo.getHeader().getStateRoot().toByteArray()),
									oBlockInfo.getHeader().getTxHashsCount(), connectMark.getMark());

							if (connectMark.getMark().equals(BlockMessageMarkEnum.DONE)) {
								// return newBlock;
							} else {
								log.error("new block error,cannot connect:" + connectMark.getMark());
								// return null;
							}
						} else {
							log.error("new block error,cannot add:" + addMark.getMark());
							// return null;
						}
					} catch (Throwable e) {
						log.error("new block error。org.mos.mcore.crypto.hash[{}] height[{}] txcount[{}]",
								mcore.crypto.bytesToHexStr((oBlockHeader == null || oBlockHeader.getHash() == null)
										? BytesHelper.ZERO_BYTE_ARRAY
										: oBlockHeader.getHash().toByteArray()),
								finalBestBlockEntity.getHeader().getHeight() + 1, (txs == null ? 0 : txs.size()), e);

					} finally {
						// lockCreateBLock.unlock();// 创建成功才释放锁
						lockCreateBlock.set(false);
					}
				}
			};
			if (MCoreConfig.FAST_APPLY && oBlockInfo.getHeader().getHeight() > 5) {
				mcore.dispatcher.executeNow(fp, subRunner);
			} else {
				subRunner.run();
			}

			return oBlockInfo.build();

		} catch (Exception e) {
			log.error("new block error,term=" + term, e);
		} finally {
			// lockCreateBLock.unlock();// 同时只有一个在创建块
		}
		return null;
	}

	public BlockSyncMessage syncBlock(ByteString bs) {
		return syncBlock(bs, false);
	}

	public BlockSyncMessage syncBlock(ByteString bs, boolean fastSync) {
		BlockInfo.Builder block;
		try {
			block = BlockInfo.newBuilder().mergeFrom(bs);
			return syncBlock(block, false);
		} catch (InvalidProtocolBufferException e) {
			e.printStackTrace();
		}
		return new BlockSyncMessage();
	}

	public BlockSyncMessage syncBlock(BlockInfo.Builder block) {
		return syncBlock(block, false);
	}

	BlockSyncMessage EMPTY_BSM = new BlockSyncMessage();

	public BlockSyncMessage syncBlock(BlockInfo.Builder block, boolean fastSync) {
		try {
			while (!lockCreateBlock.compareAndSet(false, true)) {
				Thread.sleep(100);
			}
			return syncBlock(block, fastSync, 0);
		} catch (Throwable t) {
			log.error("get error in sync block:", t);
			return EMPTY_BSM;
		} finally {
			lockCreateBlock.set(false);

		}

	}

	FramePacket fp_clean = PacketHelper.genSyncPack("clean", "sys.block", "");
	FramePacket fp_processbock = PacketHelper.genSyncPack("process", "sys.block", "");

	public BlockSyncMessage syncBlock(BlockInfo.Builder block, boolean fastSync, int level) {

		log.error(String.format(
				"start sync block   ===>   height[%-10s] org.mos.mcore.crypto.hash[%s] txcount[%-5s] pHash[%s] miner[%s]]",
				block.getHeader().getHeight(), mcore.crypto.bytesToHexStr(block.getHeader().getHash().toByteArray()),
				block.getHeader().getTxHashsCount(),
				mcore.crypto.bytesToHexStr(block.getHeader().getParentHash().toByteArray()),
				mcore.crypto.bytesToHexStr(block.getMiner().getAddress().toByteArray())));
		BlockSyncMessage bsm = new BlockSyncMessage();

		try {

			BlockInfo.Builder applyBlock = block.clone();

			BlockHeader.Builder oBlockHeader = applyBlock.getHeader().toBuilder().clone();
			oBlockHeader.clearHash();

			byte[] blockContent = BytesHelper.appendBytes(oBlockHeader.build().toByteArray(),
					applyBlock.getMiner().toByteArray());
			byte[] originhash = mcore.crypto.sha256(blockContent);
//			byte asignhash[] = new byte[originhash.length + 1];
//			asignhash[0] = (byte) ((applyBlock.getHeader().getHeight() / BLK_NUM_SPLIT) % 256);
//			System.arraycopy(originhash, 0, asignhash, 1, originhash.length);

			if (!MCoreConfig.FAST_APPLY && applyBlock.getHeader().getHeight() != 0
					&& !BytesComparisons.equal(block.getHeader().getHash().toByteArray(), originhash)) {
				log.error(String.format(
						"sync block   ===>   invalid block org.mos.mcore.crypto.hash. height[%-10s] origin org.mos.mcore.crypto.hash[{%s}] current org.mos.mcore.crypto.hash[{%s}] ",
						block.getHeader().getHeight(),
						mcore.crypto.bytesToHexStr(block.getHeader().getHash().toByteArray()),
						mcore.crypto.bytesToHexStr(mcore.crypto.sha256(blockContent))));

			} else {
				BlockMessageMark bmm = mcore.chainHandler.addBlock(block.build());
				while (bmm.getMark() != BlockMessageMarkEnum.DONE) {
					switch (bmm.getMark()) {
					case DROP:
						// log.warn(String.format("同步区块 ===> 高度[%-10s]
						// org.mos.mcore.crypto.hash[%s]
						// 状态[%s]",
						// block.getHeader().getHeight(),
						// mcore.crypto.bytesToHexStr(block.getHeader().getHash().toByteArray()),
						// bmm.getMark()));
						log.error("height=" + block.getHeader().getHeight() + " " + bmm.getMark());
						bmm.setMark(BlockMessageMarkEnum.DONE);

						// 返回失败
						bsm.setSyncCode(BlockSyncCodeEnum.ER);
						break;
					case CONNECTED:
						// 已经存在了，如果是connect状态，返回成功，
						log.error("height=" + block.getHeader().getHeight() + " " + bmm.getMark());
						//
						tempRootHash = block.getHeader().getStateRoot().toByteArray();
						mcore.stateTrie.setRoot(tempRootHash);
						bmm.setMark(BlockMessageMarkEnum.DONE);
						bsm.setSyncCode(BlockSyncCodeEnum.SS);
						break;
					case EXISTS_PREV:
						// log.warn(String.format("同步区块 ===> 高度[%-10s]
						// org.mos.mcore.crypto.hash[%s]
						// 状态[%s]",
						// block.getHeader().getHeight(),
						// mcore.crypto.bytesToHexStr(block.getHeader().getHash().toByteArray()),
						// bmm.getMark()));
						log.error("height=" + block.getHeader().getHeight() + " " + bmm.getMark());
						try {

							long rollBackNumber = block.getHeader().getHeight() - 2;
							mcore.chainHandler.rollBackTo(rollBackNumber);
							bsm.setSyncCode(BlockSyncCodeEnum.LB);
							bsm.setCurrentHeight(rollBackNumber);
							bsm.setWantHeight(rollBackNumber);
							bmm.setMark(BlockMessageMarkEnum.DONE);
						} catch (Exception e1) {
							log.error(String.format("sync block   ===>   error on resync block, height[%-10s]",
									block.getHeader().getHeight()), e1);
							bmm.setMark(BlockMessageMarkEnum.ERROR);
						}
						break;
					case CACHE:
						log.error("height=" + block.getHeader().getHeight() + " " + bmm.getMark());

						bsm.setWantHeight(block.getHeader().getHeight() - 1);
						bsm.setSyncCode(BlockSyncCodeEnum.LB);
						bmm.setMark(BlockMessageMarkEnum.DONE);
						break;
					case APPLY:
						log.error("height=" + block.getHeader().getHeight() + " " + bmm.getMark() + ",parent="
								+ Hex.encodeHexString(block.getHeader().getParentHash().toByteArray()));
						BlockInfo parentBlock = mcore.chainHandler
								.getBlockByHash(block.getHeader().getParentHash().toByteArray());

						if (parentBlock == null) {
							throw new BlockNotFoundInBufferException(
									"cannot find block with org.mos.mcore.crypto.hash " + mcore.crypto
											.bytesToHexStr(block.getHeader().getParentHash().toByteArray()));
						}

						if (fastSync && block.getBody().getTxsCount() > 0) {

							// for (TransactionInfo tx : block.getBody().getTxsList()) {
							mcore.transactionHandler.syncTransactionBatch(block.getBody().getTxsList(), false,
									BigInteger.ZERO);
							// }
						}
						BlockSyncMessage processMessage = processBlock(applyBlock, parentBlock, null);
						boolean isValidateBlock = true;

						if (processMessage.getSyncTxHash() != null && processMessage.getSyncTxHash().size() > 0) {
							bsm.setSyncCode(BlockSyncCodeEnum.LT);
							bsm.setSyncTxHash(processMessage.getSyncTxHash());
							bsm.setWantHeight(block.getHeader().getHeight());
							log.error("need tx body for block:" + block.getHeader().getHeight() + ",need count="
									+ processMessage.getSyncTxHash().size());
							bmm.setMark(BlockMessageMarkEnum.ERROR);

							break;
						} else if (!processMessage.getSyncCode().equals(BlockSyncCodeEnum.SS)) {
							isValidateBlock = false;
						} else {
							if (MCoreConfig.FAST_APPLY) {
								// if (cacheTrieRewriteByHeight.getIfPresent(applyBlock.getHeader().getHeight())
								// != null) {
								// cacheTrieRewriteByHeight.put(applyBlock.getHeader().getHeight(),
								// applyBlock.getHeader().getHeight());
								// 重新计算hash
								byte[] _blockContent = BytesHelper.appendBytes(
										applyBlock.getHeaderBuilder().clearHash().build().toByteArray(),
										applyBlock.getMiner().toByteArray());
								originhash = mcore.crypto.sha256(_blockContent);
//								asignhash = new byte[originhash.length + 1];
//								asignhash[0] = (byte) ((applyBlock.getHeader().getHeight() / BLK_NUM_SPLIT) % 256);
//								System.arraycopy(originhash, 0, asignhash, 1, originhash.length);

								applyBlock.getHeaderBuilder().setHash(ByteString.copyFrom(originhash));

								// applyBlock.getHeaderBuilder()
								// .setHash(ByteString.copyFrom(mcore.crypto.sha256(_blockContent)));

								log.error("re-save block:height=" + applyBlock.getHeader().getHeight() + ",stateroot="
										+ mcore.crypto
												.bytesToHexStr(applyBlock.getHeader().getStateRoot().toByteArray()));
								log.error(
										"resave block  ===>   org.mos.mcore.crypto.hash[{}->{}] height[{}] stateroot=[{}],txcount[{}] status[{}]",
										mcore.crypto.bytesToHexStr(applyBlock.getHeader().getHash().toByteArray()),
										mcore.crypto
												.bytesToHexStr(applyBlock.getHeader().getParentHash().toByteArray()),
										applyBlock.getHeader().getHeight(),
										mcore.crypto.bytesToHexStr(applyBlock.getHeader().getStateRoot().toByteArray()),
										applyBlock.getHeader().getTxHashsCount(), bmm.getMark());
								mcore.chainHandler.addBlock(applyBlock.build(), true);
								mcore.getDispatcher().getExecutorService("postrunner").submit(applyPostRunner);
								applyPostRunner = null;
								// }
								bsm.setSyncCode(BlockSyncCodeEnum.SS);
							} else {
								if (!BytesComparisons.equal(block.getHeader().getStateRoot().toByteArray(),
										applyBlock.getHeader().getStateRoot().toByteArray())) {
									log.error("sync block   ===>   invalid state root [{}]!=[{}]",
											mcore.crypto.bytesToHexStr(block.getHeader().getStateRoot().toByteArray()),
											mcore.crypto.bytesToHexStr(
													applyBlock.getHeader().getStateRoot().toByteArray()));
									isValidateBlock = false;
								} else if (!BytesComparisons.equal(block.getHeader().getReceiptRoot().toByteArray(),
										applyBlock.getHeader().getReceiptRoot().toByteArray())) {
									log.error("sync block   ===>   invalid receipt root [{}]!=[{}]",
											mcore.crypto
													.bytesToHexStr(block.getHeader().getReceiptRoot().toByteArray()),
											mcore.crypto.bytesToHexStr(
													applyBlock.getHeader().getReceiptRoot().toByteArray()));
									// FIXME 暂时不校验receipt trie
									// isValidateBlock = false;
								}
							}
						}

						// 不做判断
						if (!isValidateBlock) {
							final BlockHeader.Builder bbh = block.getHeader().toBuilder();
							BlockInfo rollbackBlock = null;
							long cc = applyBlock.getHeader().getHeight();
							do {
								cc = cc - 2;
								if (cc >= 0) {
									rollbackBlock = mcore.chainHandler.rollBackTo(cc);
								}
							} while (rollbackBlock == null && cc >= 0);
							tempRootHash = rollbackBlock.getHeader().getStateRoot().toByteArray();
							mcore.stateTrie.setRoot(tempRootHash);
							lastBlockHeight = rollbackBlock.getHeader().getHeight();
							bsm.setSyncCode(BlockSyncCodeEnum.ER);
							bsm.setWantHeight(rollbackBlock.getHeader().getHeight());

							if (bbh.getTxHashsCount() > 0) {
								mcore.dispatcher.executeNow(fp_clean, new Runnable() {
									@Override
									public void run() {
										for (ByteString txHash : bbh.getTxHashsList()) {
											mcore.transactionHandler.getTmConfirmQueue()
													.revalidate(txHash.toByteArray());
										}
									}
								});
							}
							bmm.setMark(BlockMessageMarkEnum.ERROR);
						} else {
							if (applyPostRunner != null) {
								mcore.dispatcher.getExecutorService("postrunner").execute(applyPostRunner);// .start();
								applyPostRunner = null;
							}
							bmm = mcore.chainHandler.tryConnectBlock(applyBlock.build());
							if (bmm.getMark().equals(BlockMessageMarkEnum.APPLY_CHILD)
									&& level > MCoreConfig.BLOCK_APPLY_LEVEL) {
								// 限制递归层级
								bmm.setMark(BlockMessageMarkEnum.DONE);
								bsm.setSyncCode(BlockSyncCodeEnum.SS);
							}
							bsm.setSyncCode(BlockSyncCodeEnum.SS);
							mcore.dispatcher.executeNow(fp_clean, new Runnable() {

								@Override
								public void run() {
									mcore.transactionHandler.getTmConfirmQueue().clear();
								}
							});
						}
						break;
					case APPLY_CHILD:
						log.error("height=" + block.getHeader().getHeight() + " " + bmm.getMark());
						for (

						BlockMessage bm : bmm.getChildBlock()) {
							applyBlock = bm.getBlock().toBuilder();
							syncBlock(applyBlock, false, level + 1);
						}
						bmm.setMark(BlockMessageMarkEnum.DONE);
						bsm.setSyncCode(BlockSyncCodeEnum.SS);
						break;
					case ERROR:
						log.error("height=" + block.getHeader().getHeight() + " " + bmm.getMark() + ",level=" + level);
						bmm.setMark(BlockMessageMarkEnum.DONE);
						bsm.setSyncCode(BlockSyncCodeEnum.ER);
						break;
					default:
						log.error("apply.default.done=" + block.getHeader().getHeight() + " " + bmm.getMark());
						break;
					}
				}
			}
		} catch (Exception e1) {
			if (block.getHeader().getHeight() > 0) {
				log.error("error in process block:" + block.getHeader().getHeight(), e1);
			}
		}

		if (bsm.getCurrentHeight() == 0) {
			bsm.setCurrentHeight(mcore.chainHandler.getLastConnectedBlockHeight());
		}

		if (bsm.getWantHeight() == 0 || bsm.getWantHeight() > bsm.getCurrentHeight()) {
			bsm.setWantHeight(bsm.getCurrentHeight());
		}
		log.error(String.format(
				"end sync block   ===>   height[%-10s] org.mos.mcore.crypto.hash[%s] stateroot[%s] needtx[%s] needblock[%s] currentblock[%s]",
				block.getHeader().getHeight(), mcore.crypto.bytesToHexStr(block.getHeader().getHash().toByteArray()),
				bsm.getSyncCode(), (bsm.getSyncTxHash() == null ? 0 : bsm.getSyncTxHash().size()), bsm.getWantHeight(),
				bsm.getCurrentHeight()));

		return bsm;
	}

	private static byte[] tempRootHash = null;
	private static long lastBlockHeight = 0;
	ReusefulLoopPool<TransactionInfoWrapper> wrapperPool = new ReusefulLoopPool<>();

	public TransactionInfoWrapper borrowTX(TransactionInfo tx, int index, ApplyBlockContext blockContext,
			AtomicBoolean justCheck) {
		TransactionInfoWrapper itx = wrapperPool.borrow();
		if (itx == null) {
			itx = new TransactionInfoWrapper(mcore, oTransactionExecutorSeparator);
		}
		itx.reset(tx, index, justCheck, blockContext);
		return itx;
	}

	public void returnTX(TransactionInfoWrapper itx) {
		wrapperPool.retobj(itx);
	}

	private BlockSyncMessage processBlock(BlockInfo.Builder currentBlock, BlockInfo parentBlock,
			List<TransactionInfo> createdtxs) throws Exception {

		log.error("start process block=" + currentBlock.getHeader().getHeight() + " from="
				+ mcore.crypto.bytesToHexStr(currentBlock.getMiner().getAddress().toByteArray()) + ",headtxcount="
				+ currentBlock.getHeader().getTxHashsCount() + ",bodytxcount=" + currentBlock.getBody().getTxsCount());
		long startTimestamp = System.currentTimeMillis();
		BlockSyncMessage oBlockSyncMessage = new BlockSyncMessage();

		// StateTrieTree oReceiptTrie = new StateTrieTree(new MemoryTrieStorage());
		// CacheTrie oCacheTrie = new CacheTrie(this.mcore.crypto);
		int createtxsize = 0;
		if (currentBlock.getHeader().getHeight() >= 1
				&& !BytesComparisons.equal(parentBlock.getHeader().getStateRoot().toByteArray(), tempRootHash)) {
			if (lastBlockHeight >= currentBlock.getHeader().getHeight()) {
				BlockInfo bi = mcore.chainHandler.getBlockByHeight(currentBlock.getHeader().getHeight());
				if (bi != null && BytesComparisons.equal(currentBlock.getHeader().getStateRoot().toByteArray(),
						bi.getHeader().getStateRoot().toByteArray())) {
					oBlockSyncMessage.setCurrentHeight(currentBlock.getHeader().getHeight());
					oBlockSyncMessage.setSyncCode(BlockSyncCodeEnum.SS);
					log.error("already exist block height same stateroot= " + currentBlock.getHeader().getHeight()
							+ ",state_root_hash:"
							+ mcore.crypto.bytesToHexStr(currentBlock.getHeader().getStateRoot().toByteArray()));
					return oBlockSyncMessage;
				}
				bi = mcore.chainHandler.getBlockByHash(currentBlock.getHeader().getHash().toByteArray());
			}

			log.error(" roll back block reset trie root. height=" + currentBlock.getHeader().getHeight() + ",cur.hash="
					+ mcore.crypto.bytesToHexStr(currentBlock.getHeader().getHash().toByteArray()) + ",lastBlockHeight="
					+ lastBlockHeight + ",connect height()=" + mcore.chainHandler.getMaxConnectHeight());
			try {
				tempRootHash = parentBlock.getHeader().getStateRoot().toByteArray();
				this.mcore.stateTrie.setRoot(parentBlock.getHeader().getStateRoot().toByteArray());
			} catch (NullRootNodeException e) {
				if (currentBlock.getHeader().getHeight() > 1) {
					log.error("roll back failed  org.mos.mcore.crypto.hash not found:height="
							+ currentBlock.getHeader().getHeight() + ",parentroot = "
							+ mcore.crypto.bytesToHexStr(parentBlock.getHeader().getStateRoot().toByteArray()));
					oBlockSyncMessage.setCurrentHeight(currentBlock.getHeader().getHeight() - 2);
					oBlockSyncMessage.setWantHeight(currentBlock.getHeader().getHeight() - 2);
					oBlockSyncMessage.setSyncCode(BlockSyncCodeEnum.ER);
					mcore.chainHandler.rollBackTo(currentBlock.getHeader().getHeight() - 2);
					lastBlockHeight = currentBlock.getHeader().getHeight() - 2;

					return oBlockSyncMessage;
				}
			}
		}

		TransactionInfoWrapper[] txs = new TransactionInfoWrapper[currentBlock.getHeader().getTxHashsCount()];
		int i = 0;
		ConcurrentHashMap<ByteString, AccountInfoWrapper> accounts = new ConcurrentHashMap<>();
		AccountInfoWrapper minner = mcore.accountHandler.getAccountOrCreate(currentBlock.getMiner().getAddress());
		accounts.put(currentBlock.getMiner().getAddress(), minner);

		ConcurrentLinkedQueue<byte[]> missingHash = new ConcurrentLinkedQueue<>();
		oTransactionExecutorSeparator.reset();
		CountDownLatch cdl = new CountDownLatch(txs.length);
		byte[][] txkeys = new byte[txs.length][];
		byte[][] txvalues = new byte[txs.length][];
		byte results[][] = new byte[txs.length][];
		BlockInfo blockInfo = currentBlock.build();

		ExecutorService executor = mcore.dispatcher.getExecutorService("blockproc");
		PreDefineRunner[] runner = new PreDefineRunner[oTransactionExecutorSeparator.getBucketSize() + 1];
		int syncCount = 0;
		long gettxendtime = System.currentTimeMillis();
		AtomicBoolean justCheck = new AtomicBoolean(false);
		if (createdtxs != null && createdtxs.size() != 0) {
			createtxsize = createdtxs.size();
			for (i = 0; i < oTransactionExecutorSeparator.getBucketSize() + 1; i++) {
				runner[i] = new TransactionInfoWrapper.PreDefineRunner(
						new LinkedBlockingQueue<TransactionInfoWrapper>(), cdl, i, blockInfo.getHeader().getHeight());
			}
			ApplyBlockContext blockContext = new ApplyBlockContext(currentBlock, blockInfo, accounts, results, cdl,
					txkeys, txvalues);

			for (int dstIndex = 0; dstIndex < createtxsize; dstIndex++) {
				TransactionInfoWrapper oTransactionInfo = borrowTX(createdtxs.get(dstIndex), dstIndex, blockContext,
						justCheck);
				txs[dstIndex] = oTransactionInfo;
				oTransactionInfo.setBulkExecIndex(blockInfo.getHeader().getTxexecbulkindex(dstIndex));
			}
			checkParrallAndNonce(txs, accounts, currentBlock.getHeader().getHeight());
			for (int dstIndex = 0; dstIndex < createtxsize; dstIndex++) {
				runner[txs[dstIndex].getBulkExecIndex()].queue.offer(txs[dstIndex]);
			}
			gettxendtime = System.currentTimeMillis();
			for (i = 0; i < oTransactionExecutorSeparator.getBucketSize(); i++) {
				executor.submit(runner[i]);
			}
		} else if (currentBlock.getHeader().getTxHashsCount() > 0) {
			int dstIndex = 0;
			ThreadContext.setContext("__LDB_FILLGET", true);
			byte[][] txhashBytes = new byte[currentBlock.getHeader().getTxHashsCount()][];
			ApplyBlockContext blockContext = new ApplyBlockContext(currentBlock, blockInfo, accounts, results, cdl,
					txkeys, txvalues);

			if (currentBlock.getBody().getTxsCount() > 0
					&& currentBlock.getBody().getTxsCount() == currentBlock.getHeader().getTxHashsCount()) {// 有交易体的
				for (i = 0; i < oTransactionExecutorSeparator.getBucketSize() + 1; i++) {

					runner[i] = new TransactionInfoWrapper.PreDefineRunner(
							new LinkedBlockingQueue<TransactionInfoWrapper>(), cdl, i,
							blockInfo.getHeader().getHeight());
				}
				for (TransactionInfo oTransactionInfo : currentBlock.getBody().getTxsList()) {
					mcore.transactionHandler.getTransactionDataAccess().getTransactionCache().put(
							mcore.crypto.bytesToHexStr(oTransactionInfo.getHash().toByteArray()), oTransactionInfo);
					TransactionInfoWrapper oTransactionInfow = borrowTX(oTransactionInfo, dstIndex, blockContext,
							justCheck);
					txs[dstIndex] = oTransactionInfow;
					oTransactionInfow.setBulkExecIndex(blockInfo.getHeader().getTxexecbulkindex(dstIndex));
					dstIndex++;
				}
			} else {
				dstIndex = 0;
				TransactionInfo txInfos[] = new TransactionInfo[currentBlock.getHeader().getTxHashsCount()];
				cdl = new CountDownLatch(txInfos.length);
				for (ByteString txHash : currentBlock.getHeader().getTxHashsList()) {
					executor.submit(new ParalTransactionLoader(txHash, dstIndex, cdl, txInfos, missingHash, justCheck,
							txhashBytes));
					dstIndex++;
				}
				boolean b = cdl.await(60, TimeUnit.SECONDS);
				if (!b) {
					log.error("exec- load tx timeout!!!" + cdl.getCount());
					while (cdl.getCount() > 0) {
						cdl.countDown();// release
					}
				}
				cdl = new CountDownLatch(txs.length);

				for (i = 0; i < oTransactionExecutorSeparator.getBucketSize() + 1; i++) {
					runner[i] = new TransactionInfoWrapper.PreDefineRunner(
							new LinkedBlockingQueue<TransactionInfoWrapper>(), cdl, i,
							blockInfo.getHeader().getHeight());
				}

				if (!justCheck.get()) {
					for (dstIndex = 0; dstIndex < txs.length; dstIndex++) {
						TransactionInfoWrapper oTransactionInfow = borrowTX(txInfos[dstIndex], dstIndex, blockContext,
								justCheck);
						txs[dstIndex] = oTransactionInfow;
						oTransactionInfow.setBulkExecIndex(blockInfo.getHeader().getTxexecbulkindex(dstIndex));
					}
				}
			}
			ThreadContext.removeContext("__LDB_FILLGET");
			if (!missingHash.isEmpty() || justCheck.get()) {
				oBlockSyncMessage.setSyncCode(BlockSyncCodeEnum.LT);
				byte[] hash = missingHash.poll();

				for (TransactionInfoWrapper itx : txs) {
					try {
						if (itx != null) {
							returnTX((TransactionInfoWrapper) itx);
						}
					} catch (Exception e) {
						log.error("error in returning tx wrapper", e);
					}
				}

				while (hash != null) {
					oBlockSyncMessage.getSyncTxHash().add(hash);
					hash = missingHash.poll();
				}
				return oBlockSyncMessage;
			}

			checkParrallAndNonce(txs, accounts, currentBlock.getHeader().getHeight());
			for (dstIndex = 0; dstIndex < txs.length; dstIndex++) {
				runner[txs[dstIndex].getBulkExecIndex()].queue.offer(txs[dstIndex]);
			}
			dstIndex = 0;
			gettxendtime = System.currentTimeMillis();

			for (i = 0; i < oTransactionExecutorSeparator.getBucketSize(); i++) {
				executor.submit(runner[i]);
			}

			mcore.getDispatcher().getExecutorService("postrunner").submit((new Runnable() {
				@Override
				public void run() {
					// TODO Auto-generated method stub
					long rmtime = System.currentTimeMillis();
					if (currentBlock.getBody().getTxsCount() > 0) {// 有交易体的
						for (TransactionInfo oTransactionInfo : currentBlock.getBody().getTxsList()) {
							try {
								mcore.transactionHandler.removeWaitingBlockTx(oTransactionInfo.getHash().toByteArray(),
										rmtime);// 如果缺少hash就被扔掉了
							} catch (Exception e) {
								e.printStackTrace();
							}
						}
					} else {
						for (int i = 0; i < txs.length; i++) {
							try {
								mcore.transactionHandler.removeWaitingBlockTx(txhashBytes[i], rmtime);// 如果缺少hash就被扔掉了
							} catch (Exception e) {
								e.printStackTrace();
							}
						}
					}
				}
			}));
		}
		if (txs.length > 0) {
			if (runner[runner.length - 1] != null) {
				syncCount = runner[runner.length - 1].queue.size();
				for (i = 0; i < syncCount; i++) {
					cdl.countDown();
				}
			}
			boolean b = cdl.await(60, TimeUnit.SECONDS);
			if (!b) {
				log.error("TransactionExecutor timeout!!!" + cdl.getCount());
				while (cdl.getCount() > 0) {
					cdl.countDown();// release
				}
			}
			if (runner[runner.length - 1] != null) {
				runner[runner.length - 1].clearLeft();
			}
			// mcore.getChainConfig().setConfigAccount(configAccount);

		}
		long endExecTxTimestamp = System.currentTimeMillis();
		// 奖励规则
		// TODO: 奖励截止？
		// (block.timestamp - prevblock.timestamp)/1000 * (reward/s)
		// long rewards = (currentBlock.getHeader().getTimestamp() -
		// parentBlock.getHeader().getTimestamp()) / 1000
		// * mcore.chainConfig.getBlock_reward();

		BigInteger rewards = mcore.chainConfig.getBlockReward();

		accounts.get(currentBlock.getMiner().getAddress()).addAndGet(rewards);

		AccountInfo.Builder config = mcore.accountHandler
				.getAccount(mcore.getChainConfig().getConfigAddress().toByteArray());
		ChainConfigWrapper configAccount;
		if (config != null) {
			configAccount = new ChainConfigWrapper(config);
			mcore.getChainConfig().setConfigAccount(configAccount);
		} else {
			configAccount = mcore.getChainConfig().getConfigAccount();
		}
		accounts.put(mcore.getChainConfig().getConfigAddress(), configAccount);
		// log.error("put accounts")
		configAccount.addAndGet(BigInteger.valueOf(txs.length));

		mcore.accountHandler.batchPutAccounts(currentBlock.getHeader().getHeight(), accounts);

		// for (TransactionInfoWrapper itx : txs) {
		// for (ByteString sortaddr : itx.relationAccount) {
		// AccountInfo.Builder accountValue = accounts.get(sortaddr);
		// this.mcore.stateTrie.put(sortaddr.toByteArray(),
		// accountValue.getValue().toByteArray());
		// }
		// }
		long endPutAccounts = System.currentTimeMillis();

		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		for (int cc = 0; cc < results.length; cc++) {
			if (results[cc] != null) {
				bout.write(BytesHelper.intToBytes(cc));
				bout.write(results[cc]);
			}
		}
		byte receiptHash[] = mcore.crypto.sha3(bout.toByteArray());

		long endReceiptTrie = System.currentTimeMillis();

		currentBlock.getHeaderBuilder().setReceiptRoot(ByteString.copyFrom(receiptHash));
		// oReceiptTrie.encode(currentBlock.getHeader().getHeight()) == null ?
		// BytesHelper.EMPTY_BYTE_ARRAY
		// : oReceiptTrie.encode(currentBlock.getHeader().getHeight())));

		tempRootHash = mcore.stateTrie.getRootHash(currentBlock.getHeader().getHeight());
		currentBlock.getHeaderBuilder().setStateRoot(ByteString.copyFrom(tempRootHash));
		lastBlockHeight = currentBlock.getHeader().getHeight();
		// .setReceiptRoot(ByteString.copyFrom(oCacheTrie.getRootHash()));
		currentBlock.getMinerBuilder().setReward(ByteString.copyFrom(BytesHelper.bigIntegerToBytes(rewards)));

		oBlockSyncMessage.setSyncCode(BlockSyncCodeEnum.SS);

		// StatRunner.processTxCounter.addAndGet(currentBlock.getHeader().getTxHashsCount());
		StatRunner.processTxCount = currentBlock.getHeader().getTxHashsCount();
		StatRunner.blockInterval = currentBlock.getHeader().getTimestamp() - parentBlock.getHeader().getTimestamp();

		long endStateTrie = System.currentTimeMillis();

		// if (wrapperPool.size() < 200000) {
		final BlockInfo.Builder saveBlock = currentBlock.clone();
		applyPostRunner = new Runnable() {

			@Override
			public void run() {
				try {
					mcore.stateTrie.flushPutCache();
					StateTrieNode.resetCounter();
					List<byte[]> keys = new ArrayList<>();
					List<byte[]> values = new ArrayList<>();
					for (byte[] key : txkeys) {
						keys.add(key);
					}
					for (byte[] value : txvalues) {
						values.add(value);
					}
					try {
						saveBlock.getBodyBuilder().clearTxs();
						for (TransactionInfoWrapper itx : txs) {
							saveBlock.getBodyBuilder().addTxs(itx.getTxinfo());
						}
						// chainHandler.getBlockChainDA().saveBlockToFile(saveBlock.build());
						mcore.transactionHandler.getTransactionDataAccess().batchSaveTransaction(keys, values);
					} catch (Exception e1) {
						log.error("error in saving txs", e1);
					}

					if (wrapperPool.size() < 200000) {
						for (Object itx : txs) {
							try {
								if (itx instanceof Future) {
									Future<TransactionInfoWrapper> future = (Future<TransactionInfoWrapper>) itx;
									returnTX(future.get());
								} else {
									returnTX((TransactionInfoWrapper) itx);
								}
							} catch (Exception e) {
								log.error("error in returning tx wrapper", e);
							}
						}
					}

				} catch (Exception e) {
					log.error("error in defer sync tx", e);
				} finally {
					// applyPostRunner = null;
					// lockCreateBlock.set(false);
				}
			}
		};

		// }
		log.error("sep=" + oTransactionExecutorSeparator.getBucketInfo());
		// log.error("monitor block "+currentBlock.getHeader().getHeight());
		// log.error("monitor txs "+currentBlock.getHeader().getTxHashsCount());
		// log.error("monitor total "+(System.currentTimeMillis() - startTimestamp));
		log.error("end exec block " + currentBlock.getHeader().getHeight() + " txs="
				+ currentBlock.getHeader().getTxHashsCount() + " total=" + (System.currentTimeMillis() - startTimestamp)
				+ " statetrie=" + (endStateTrie - endReceiptTrie) + " receipttrie=" + (endReceiptTrie - endPutAccounts)
				+ " gettx=" + (gettxendtime - startTimestamp) + " accounts=" + (endPutAccounts - endExecTxTimestamp)
				+ " exec=" + (endExecTxTimestamp - startTimestamp) + ",stateroot="
				+ mcore.crypto.bytesToHexStr(tempRootHash) + ",rewards=" + rewards.toString(10) + ",createtx="
				+ createtxsize + ",act=" + mcore.getChainConfig().getConfigAccount().getBalance());

		return oBlockSyncMessage;
	}

	boolean logtx = true;
	Runnable applyPostRunner = null;

	@AllArgsConstructor
	public class ParalTransactionLoader implements Runnable {
		ByteString txHash;
		int dstIndex;
		CountDownLatch cdl;
		TransactionInfo[] txs;
		ConcurrentLinkedQueue<byte[]> missingHash;
		AtomicBoolean justCheck;
		byte[][] txhashBytes;

		@Override
		public void run() {
			try {
				txhashBytes[dstIndex] = txHash.toByteArray();
				TransactionMessage tm = mcore.transactionHandler.getTmConfirmQueue().getStorage()
						.get(txhashBytes[dstIndex]);
				TransactionInfo oTransactionInfo = null;
				if (tm != null) {
					oTransactionInfo = tm.getTx();
				}

				if (oTransactionInfo == null) {
					oTransactionInfo = mcore.transactionHandler.getTransaction(txhashBytes[dstIndex]);
				}
				if (oTransactionInfo == null || oTransactionInfo.getHash() == null) {
					missingHash.add(txhashBytes[dstIndex]);
					justCheck.set(true);
				} else {
					if (!justCheck.get()) {
						txs[dstIndex] = oTransactionInfo;
					}
				}

			} catch (Exception e) {
				log.error("error in loading tx ", e);
			} finally {
				cdl.countDown();
				// Thread.currentThread().setName("statetrie-pool");
			}
		}
	}

	@AllArgsConstructor
	class RunIndexAndNonce {
		AtomicInteger nonce;
		boolean runningParrall;

	}

	// 检查nonce是否连续。。。。
	public void checkParrallAndNonce(TransactionInfoWrapper[] txs,
			ConcurrentHashMap<ByteString, AccountInfoWrapper> accounts, long blockheight) throws InterruptedException {
		CountDownLatch cdl = new CountDownLatch(txs.length);
		for (TransactionInfoWrapper tx : txs) {
			tx.setRunningCDL(cdl);
			mcore.getDispatcher().getExecutorService("blockproc").submit(tx);
		}
		boolean b = cdl.await(60, TimeUnit.SECONDS);
		if (!b) {
			log.error("exec- load tx timeout!!!" + cdl.getCount());
			while (cdl.getCount() > 0) {
				cdl.countDown();// release
			}
		}
		ConcurrentHashMap<ByteString, RunIndexAndNonce> accountsNonces = new ConcurrentHashMap<>();
		int dropcc = 0;
		int syncruncc = 0;
		for (TransactionInfoWrapper tx : txs) {
			ByteString addr = tx.getTxinfo().getBody().getAddress();
			RunIndexAndNonce seq_nonce = accountsNonces.get(addr);
			// if (logtx) {
			// // log.error("gettx.hash=" +
			// // mcore.crypto.bytesToHexStr(tx.getTxinfo().getHash().toByteArray()) +
			// ",addr="
			// // +
			// //
			// mcore.crypto.bytesToHexStr(tx.getTxinfo().getBody().getAddress().toByteArray()));
			// }
			if (seq_nonce == null) {
				if (accounts.containsKey(addr)) {
					seq_nonce = new RunIndexAndNonce(new AtomicInteger(accounts.get(addr).getNonce()), true);

				} else {
					seq_nonce = new RunIndexAndNonce(new AtomicInteger(0), true);
				}
				accountsNonces.put(addr, seq_nonce);
			}
			if (!seq_nonce.nonce.compareAndSet(tx.getTxinfo().getBody().getNonce(),
					tx.getTxinfo().getBody().getNonce() + 1)) {
				// log.error("drop tx nonce error:dbnonce=" + seq_nonce.get() + ",tx.nonce="
				// + tx.getTxinfo().getBody().getNonce() + ",txhash="
				// + mcore.crypto.bytesToHexStr(tx.getTxinfo().getHash().toByteArray()));
				tx.setNonceTruncate(true);
				dropcc++;
			}
			if (!seq_nonce.runningParrall || !tx.isParrallelExec()) {
				// 非并行执行的话，需要把这个地址之后的都放到并行队列中
				tx.setBulkExecIndex(oTransactionExecutorSeparator.getBucketSize());
				seq_nonce.runningParrall = false;
				syncruncc++;
			}
		}
		log.error("checknonce:" + blockheight + ",drop txcount=" + dropcc + ",txcount=" + txs.length + ",syncruncc="
				+ syncruncc);

		// if (txs.length > 5) {
		// logtx = false;
		// }

	}

}
