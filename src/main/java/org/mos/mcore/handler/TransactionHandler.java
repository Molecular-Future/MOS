package org.mos.mcore.handler;

import com.google.protobuf.ByteString;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import onight.osgi.annotation.NActorProvider;
import onight.tfw.mservice.ThreadContext;
import onight.tfw.ntrans.api.ActorService;
import onight.tfw.ntrans.api.annotation.ActorRequire;
import onight.tfw.otransio.api.PacketHelper;
import onight.tfw.otransio.api.beans.FramePacket;
import onight.tfw.outils.conf.PropHelper;
import org.apache.felix.ipojo.annotations.Instantiate;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Validate;
import org.mos.mcore.actuator.IActuator;
import org.mos.mcore.api.ICryptoHandler;
import org.mos.mcore.bean.TransactionExecutorResult;
import org.mos.mcore.bean.TransactionInfoWrapper;
import org.mos.mcore.bean.TransactionMessage;
import org.mos.mcore.concurrent.AccountInfoWrapper;
import org.mos.mcore.config.StatRunner;
import org.mos.mcore.datasource.TransactionDataAccess;
import org.mos.mcore.exception.DposNodeNotReadyException;
import org.mos.mcore.model.Block.BlockInfo;
import org.mos.mcore.model.Transaction.*;
import org.mos.mcore.service.ChainConfig;
import org.mos.mcore.service.TransactionConfirmQueue;
import org.mos.mcore.tools.bytes.BytesComparisons;
import org.mos.mcore.tools.queue.IPendingQueue;
import org.fc.zippo.dispatcher.IActorDispatcher;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@NActorProvider
@Provides(specifications = { ActorService.class }, strategy = "SINGLETON")
@Instantiate(name = "bc_transaction")
@Slf4j
@Data
public class TransactionHandler implements ActorService, Runnable {
	@ActorRequire(name = "bc_chain", scope = "global")
	ChainHandler blockChain;
	@ActorRequire(name = "bc_transaction_message_queue", scope = "global")
	IPendingQueue<TransactionMessage> tmMessageQueue;
	@ActorRequire(name = "bc_transaction_confirm_queue", scope = "global")
	TransactionConfirmQueue tmConfirmQueue;
	@ActorRequire(name = "bc_account", scope = "global")
	AccountHandler accountHelper;
	@ActorRequire(name = "transaction_data_access", scope = "global")
	TransactionDataAccess transactionDataAccess;
	@ActorRequire(name = "bc_crypto", scope = "global")
	ICryptoHandler crypto;
	@ActorRequire(name = "bc_chainconfig", scope = "global")
	ChainConfig chainConfig;

	@ActorRequire(name = "bc_actuactor", scope = "global")
	ActuactorHandler actuactorHandler;

	@ActorRequire(name = "zippo.ddc", scope = "global")
	IActorDispatcher dispatcher = null;

	public IActorDispatcher getDispatcher() {
		return dispatcher;
	}

	public void setDispatcher(IActorDispatcher dispatcher) {
		log.debug("set dispatcher: {}", dispatcher);
		this.dispatcher = dispatcher;
	}

	FramePacket fp = PacketHelper.genSyncPack("mtx", "sys.trans", "");

	PropHelper props = new PropHelper(null);

	int TX_TIME_SPLIT = props.get("org.mos.mcore.backend.tx.timesplitms", 500);
	int LIMIT_BROARCASTTX = props.get("org.mos.mcore.handler.transaction.limit.broadcast", 1000000);

	LinkedBlockingQueue<TransactionMessage> pendingTx = new LinkedBlockingQueue<>();
	int BATCH_SIZE = 1000;

	AtomicInteger syncingThreadCount = new AtomicInteger(0);

	public TransactionMessage createTransaction(TransactionInfo.Builder oTransactionInfo) throws Exception {
		if (!chainConfig.isNodeStart()) {
			throw new DposNodeNotReadyException("dpos node not ready");
		}
		TransactionNode.Builder oNode = TransactionNode.newBuilder();
		oNode.setAddress(chainConfig.getMiner_account_address_bs());
		oNode.setNid(chainConfig.getNodeId());
		oTransactionInfo.setNode(oNode);
		oTransactionInfo.clearStatus();
		oTransactionInfo.clearHash();
		// 生成交易Hash
		oTransactionInfo.setAccepttimestamp(System.currentTimeMillis());
		byte originhash[] = crypto.sha256(oTransactionInfo.getBody().toByteArray());
		byte asignhash[] = new byte[originhash.length + 1];
		asignhash[0] = (byte) ((oTransactionInfo.getBody().getTimestamp() / TX_TIME_SPLIT / 16) % 256);
		System.arraycopy(originhash, 0, asignhash, 1, originhash.length);

		oTransactionInfo.setHash(ByteString.copyFrom(asignhash));

		if (transactionDataAccess.isExistsTransaction(oTransactionInfo.getHash().toByteArray())) {
			throw new Exception("transaction exists, drop it txhash::"
					+ crypto.bytesToHexStr(oTransactionInfo.getHash().toByteArray()) + " from::"
					+ crypto.bytesToHexStr(oTransactionInfo.getBody().getAddress().toByteArray()));
		}
		ConcurrentHashMap<ByteString, AccountInfoWrapper> accounts = new ConcurrentHashMap<>();
		TransactionInfo tx = oTransactionInfo.build();
//		merageTransactionAccounts(tx, accounts);

		IActuator transactionExecutorHandler = actuactorHandler
				.getActuator(oTransactionInfo.getBody().getInnerCodetype());
		if (transactionExecutorHandler.needSignature()) {
			transactionExecutorHandler.onVerifySignature(new TransactionInfoWrapper(tx));
		}

		// AccountInfoWrapper sender =
		// accountHelper.getAccountOrCreate(oTransactionInfo.getBody().getAddress());
		// transactionExecutorHandler.prepareExecute(sender, oTransactionInfo);

		TransactionMessage rettm = new TransactionMessage(oTransactionInfo.getHash().toByteArray(),
				oTransactionInfo.build(), BigInteger.ZERO, true);
		pendingTx.offer(rettm);

		if (syncingThreadCount.get() <= THREAD_COUNT) {
			dispatcher.getExecutorService("createtx").submit(this);
		}

		return rettm;
	}

	public String toString() {
		return "TransactionHandler";
	}

	public TransactionMessage createInitTransaction(TransactionInfo.Builder oTransactionInfo) throws Exception {
		// if (!chainConfig.isNodeStart()) {
		// throw new DposNodeNotReadyException("dpos node not ready");
		// }
		oTransactionInfo.clearStatus();
		// 生成交易Hash

		if (transactionDataAccess.isExistsTransaction(oTransactionInfo.getHash().toByteArray())) {
			throw new Exception("transaction exists, drop it txhash::"
					+ crypto.bytesToHexStr(oTransactionInfo.getHash().toByteArray()) + " from::"
					+ crypto.bytesToHexStr(oTransactionInfo.getBody().getAddress().toByteArray()));
		}
		TransactionMessage rettm = new TransactionMessage(oTransactionInfo.getHash().toByteArray(),
				oTransactionInfo.build(), BigInteger.ZERO, true);

		return rettm;
	}

	int THREAD_COUNT = Runtime.getRuntime().availableProcessors() / 2;

	public TransactionInfo getTransaction(byte[] txHash) throws Exception {
		return transactionDataAccess.getTransaction(txHash);
	}

	public boolean isExistsTransaction(byte[] txHash) throws Exception {
		return transactionDataAccess.isExistsTransaction(txHash);
	}

	@Validate
	public void init() {
		new Thread(this).start();
	}

	public void run() {
		try {
			List<byte[]> keys = new ArrayList<byte[]>();
			List<byte[]> values = new ArrayList<byte[]>();
			while (syncingThreadCount.get() == 0) {
				syncingThreadCount.incrementAndGet();
				keys.clear();
				values.clear();
				TransactionMessage tm = pendingTx.poll(10000, TimeUnit.MILLISECONDS);
				while (tm != null && keys.size() < BATCH_SIZE) {
					try {
						TransactionNode.Builder oNode = TransactionNode.newBuilder();
						oNode.setAddress(chainConfig.getMiner_account_address_bs());
						oNode.setNid(chainConfig.getNodeId());
						TransactionInfo.Builder oTransactionInfo = tm.getTx().toBuilder();
						oTransactionInfo.setNode(oNode);
						oTransactionInfo.setAccepttimestamp(System.currentTimeMillis());
						// transactionExecutorHandler.onPrepareExecute(oTransactionInfo.build());
						// transactionDataAccess.saveTransaction(tm.getTx());
						keys.add(tm.getTx().getHash().toByteArray());
						values.add(oTransactionInfo.build().toByteArray());

						StatRunner.createTxCounter.incrementAndGet();
						StatRunner.totalTxSizeCounter.addAndGet(oTransactionInfo.build().toByteArray().length);
						tmMessageQueue.addElement(tm);
						StatRunner.txMessageQueueSize = tmMessageQueue.size();
						StatRunner.txConfirmQueueSize = tmConfirmQueue.size();
						if (keys.size() < BATCH_SIZE - 1) {
							tm = pendingTx.poll(10, TimeUnit.MILLISECONDS);
						} else {
							tm = null;
						}
					} catch (Exception e) {
						e.printStackTrace();

						tm = null;
					}
				}
				ThreadContext.removeContext("__LDB_FILLGET");
				if (keys.size() > 0) {
					transactionDataAccess.batchSaveTransactionIfNotExist(keys, values);
				}
				syncingThreadCount.decrementAndGet();
			}
		} catch (Exception e) {
			log.error("create tx error:", e);
		} finally {

		}

	}

	public BroadcastTransactionMsg getWaitSendTx(int count) {
		BroadcastTransactionMsg.Builder oBroadcastTransactionMsg = BroadcastTransactionMsg.newBuilder();
		// int total = 0;
		// 如果confirm队列交易有挤压，则降低交易广播的速度
		int confirmTotal = tmConfirmQueue.getTmConfirmQueue().size();

		int THRESHOLD = LIMIT_BROARCASTTX;
		if (tmConfirmQueue != null && confirmTotal < THRESHOLD) {
			if ((THRESHOLD - confirmTotal) < count) {
				count = THRESHOLD - confirmTotal;
			}
			List<TransactionMessage> tms = tmMessageQueue.poll(count);
			if (tms != null) {
				tms.forEach(tm -> {
					try {
						if (tm != null && tm.getKey() != null && tm.getData() != null) {
							oBroadcastTransactionMsg.addTxHash(ByteString.copyFrom(tm.getKey()));
							oBroadcastTransactionMsg.addTxDatas(ByteString.copyFrom(tm.getData()));

							tmConfirmQueue.put(tm, BigInteger.ONE);
						}
					} catch (Throwable t) {
						log.error("" + t);
					}
				});
			}
			// while (count > 0) {
			// TransactionMessage tm = tmMessageQueue.pollFirst();
			// if (tm == null) {
			// break;
			// }
			// oBroadcastTransactionMsg.addTxHash(ByteString.copyFrom(tm.getKey()));
			// oBroadcastTransactionMsg.addTxDatas(ByteString.copyFrom(tm.getData()));
			//
			// tmConfirmQueue.put(tm, BigInteger.ZERO);
			// count--;
			// total++;
			// }
		}
		return oBroadcastTransactionMsg.build();
	}

	public List<TransactionInfo> getWaitBlockTx(int count, int confirmTimes) {
		return tmConfirmQueue.poll(count, confirmTimes);
	}

	public boolean isExistsWaitBlockTx(byte[] hash) {
		return tmConfirmQueue.containsKey(hash);
	}

	public TransactionMessage removeWaitingBlockTx(byte[] txHash, long rmtime) throws Exception {
		TransactionMessage tmWaitBlock = tmConfirmQueue.invalidate(txHash, rmtime);
		if (tmWaitBlock != null && tmWaitBlock.getTx() != null) {
			return tmWaitBlock;
		} else {
			return null;
		}
	}

	// public void setTransactionDone(TransactionInfo transaction, BlockInfo
	// block, ByteString result) throws Exception {
	// TransactionInfo.Builder oTransaction = transaction.toBuilder();
	// oTransaction.setStatus("D");
	// oTransaction.setResult(result);
	// transactionDA.saveTransaction(oTransaction.build());
	// }

	public boolean isDone(TransactionInfo transaction) {
		return "D".equals(transaction.getStatus().getStatus().toStringUtf8());
	}

	// public void setTransactionError(TransactionInfo transaction, BlockInfo
	// block, ByteString result) throws Exception {
	// TransactionInfo.Builder oTransaction = transaction.toBuilder();
	// oTransaction.setStatus("E");
	// oTransaction.setResult(result);
	// transactionDA.saveTransaction(oTransaction.build());
	// }

	public void syncTransaction(TransactionInfo transaction) {
		syncTransaction(transaction, true);
	}

	public void syncTransaction(TransactionInfo transaction, boolean isFromOther) {
		syncTransaction(transaction, true, new BigInteger("0"));
	}

	public void syncTransaction(TransactionInfo transaction, boolean isFromOther, BigInteger bits) {
		try {
			if (transactionDataAccess.isExistsTransaction(transaction.getHash().toByteArray())) {
				log.warn("transaction " + crypto.bytesToHexStr(transaction.getHash().toByteArray())
						+ "exists in DB, drop it");
			} else {
				// BytesHashMap<AccountInfo.Builder> accounts =
				// getTransactionAccounts(transaction);
				// ITransactionExecutorHandler transactionExecutorHandler =
				// transactionActuator.getActuator(accountHandler,
				// transactionHandler, crypto, transaction,
				// blockChain.getLastConnectedBlock());
				// if (transactionExecutorHandler.needSignature()) {
				// transactionExecutorHandler.onVerifySignature(transaction);
				// }
				if (!BytesComparisons.equal(transaction.getHash().toByteArray(),
						crypto.sha3(transaction.getBody().toByteArray()))) {
					log.error("fail to sync transaction::" + crypto.bytesToHexStr(transaction.getHash().toByteArray())
							+ ", content invalid");
				} else {
					IActuator transactionExecutorHandler = actuactorHandler
							.getActuator(transaction.getBody().getInnerCodetype());
					if (transactionExecutorHandler != null) {
						if (transactionExecutorHandler.needSignature()) {
							transactionExecutorHandler.onVerifySignature(new TransactionInfoWrapper(transaction));
						}
						TransactionMessage tm = new TransactionMessage(transaction.getHash().toByteArray(), transaction,
								bits, !isFromOther);
						transactionDataAccess.saveTransaction(tm.getTx());
						StatRunner.receiveTxCounter.incrementAndGet();
						StatRunner.totalTxSizeCounter.addAndGet(tm.getTx().toByteArray().length);
					} else {
						log.debug("cannot find actuactor for type:"+transaction.getBody().getInnerCodetype());
					}
				}
			}
		} catch (Exception e) {
			log.error("fail to sync transaction::" + crypto.bytesToHexStr(transaction.getHash().toByteArray())
					+ " error::" + e, e);
		}
	}

	public void syncTransactionConfirm(byte[] hash, BigInteger bits) {
		try {
			// 如果交易已经确认过了，则直接返回，不再记录节点的确认次数
			TransactionInfo ti = getTransaction(hash);
			if (ti != null && ti.getBody() != null) {
				if (TransactionExecutorResult.isDone(ti)) {
					return;
				}
			}
			tmConfirmQueue.increaseConfirm(hash, bits);
		} catch (Exception e) {
			log.error("error on syncTransactionConfirm", e);
		}
	}

	public void syncTransactionBatch(List<TransactionInfo> transactionInfos, boolean isFromOther, BigInteger bits) {
		if (transactionInfos.size() > 0) {
			// List<byte[]> keys = new ArrayList<>();
			// List<byte[]> values = new ArrayList<>();
			// ThreadContext.setContext("__LDB_FILLGET", "true");
			ThreadContext.removeContext("__LDB_FILLGET");
			List<byte[]> batchputKeys = new ArrayList<>();
			List<byte[]> batchputTxs = new ArrayList<>();
			for (TransactionInfo transaction : transactionInfos) {
				try {
					byte hashBytes[] = transaction.getHash().toByteArray();
					if (!transactionDataAccess.isExistsTransaction(hashBytes)) {
						// 验证交易签名
						IActuator transactionExecutorHandler = actuactorHandler
								.getActuator(transaction.getBody().getInnerCodetype());
						if (transactionExecutorHandler != null) {
							if (transactionExecutorHandler.needSignature()) {
								transactionExecutorHandler.onVerifySignature(new TransactionInfoWrapper(transaction));
							}
							if (isFromOther) {
								byte hash[] = hashBytes;
								byte body[] = transaction.toByteArray();
								batchputKeys.add(hash);
								batchputTxs.add(body);
							}

							TransactionMessage tm = new TransactionMessage(hashBytes, transaction, bits, false);
							if (isFromOther) {
								tmConfirmQueue.put(tm, bits);
								tmConfirmQueue.increaseConfirm(hashBytes, bits);
							}
							// transactionDataAccess.saveTransaction(tm.getTx());
							// keys.add(transaction.getHash().toByteArray());
							// values.add(transaction.toByteArray());
							StatRunner.receiveTxCounter.incrementAndGet();
							StatRunner.totalTxSizeCounter.addAndGet(tm.getTx().toByteArray().length);
							// } else {
							// tmConfirmQueue.increaseConfirm(hashBytes, bits);
						}
					}
					tmConfirmQueue.increaseConfirm(hashBytes, bits);
				} catch (Exception e) {
					log.error("fail to sync transaction::" + transactionInfos.size() + " error::" + e, e);
				}
			}
			if (isFromOther) {
				try {
					transactionDataAccess.batchSaveTransactionNotCheck(batchputKeys, batchputTxs, false);
				} catch (Exception e) {
					log.error("fail to sync transaction::" + transactionInfos.size() + " error::" + e, e);
				}
			}
			// ThreadContext.removeContext("__LDB_FILLGET");
			// try {
			// transactionDataAccess.batchSaveTransaction(keys, values);
			// } catch (Exception e) {
			// log.error("fail to sync transaction::" + transactionInfos.size()
			// + " error::" + e, e);
			// }
		}

	}

	public byte[] getOriginalTransaction(TransactionInfo oTransaction) {
		TransactionInfo.Builder newTx = TransactionInfo.newBuilder();
		newTx.setBody(oTransaction.getBody());
		newTx.setHash(oTransaction.getHash());
		newTx.setNode(oTransaction.getNode());
		return newTx.build().toByteArray();
	}

	public void merageTransactionAccounts(TransactionInfo oTransactionInfo,
			ConcurrentHashMap<ByteString, AccountInfoWrapper> accounts) {
		TransactionBody oBody = oTransactionInfo.getBody();
		if (!accounts.containsKey(oBody.getAddress())) {
			accounts.put(oBody.getAddress(), accountHelper.getAccountOrCreate(oBody.getAddress()));
		}

		for (TransactionOutput oOutput : oBody.getOutputsList()) {
			if (!accounts.containsKey(oOutput.getAddress())) {
				accounts.put(oOutput.getAddress(), accountHelper.getAccountOrCreate(oOutput.getAddress()));
			}
		}
	}

	public List<ByteString> getRelationAccount(TransactionInfo tx, List<ByteString> list) {
		// while(lock.get());
		// List<ByteString> list = new ArrayList<>();
		if (tx.getBody().getOutputsList() != null) {
			for (TransactionOutput output : tx.getBody().getOutputsList()) {
				list.add(output.getAddress());
				if (output.getSymbol() != null && output.getSymbol() != ByteString.EMPTY) {
					list.add(output.getSymbol());
				}
				if (output.getToken() != null && output.getToken() != ByteString.EMPTY) {
					list.add(output.getToken());
				}
			}
		}

		list.add(tx.getBody().getAddress());
		return list;
	}

	private static final int UNSIGNED_BYTE_MASK = 0xFF;

	private static int toInt(byte value) {
		return value & UNSIGNED_BYTE_MASK;
	}

	public void setTransactionDone(TransactionInfo tx, BlockInfo block, ByteString result) throws Exception {
		TransactionInfo.Builder oTransaction = tx.toBuilder();
		TransactionExecutorResult.setDone(oTransaction, block, result);
		transactionDataAccess.saveTransaction(oTransaction.build());
	}

	public void setTransactionError(TransactionInfo tx, BlockInfo block, ByteString result) throws Exception {
		TransactionInfo.Builder oTransaction = tx.toBuilder();
		TransactionExecutorResult.setError(oTransaction, block, result);
		transactionDataAccess.saveTransaction(oTransaction.build());
	}

	public TransactionInfo getTransactionByHash(byte[] hash) throws Exception {
		return transactionDataAccess.getTransaction(hash);
	}
}
