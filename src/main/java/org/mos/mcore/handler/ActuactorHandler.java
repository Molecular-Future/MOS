package org.mos.mcore.handler;

import com.google.protobuf.ByteString;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import onight.osgi.annotation.NActorProvider;
import onight.tfw.ntrans.api.ActorService;
import onight.tfw.ntrans.api.annotation.ActorRequire;
import org.apache.felix.ipojo.annotations.Instantiate;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Validate;
import org.mos.mcore.actuator.IActuator;
import org.mos.mcore.actuator.TransferActuator;
import org.mos.mcore.actuator.exception.TransactionVerifyException;
import org.mos.mcore.model.Transaction.TransactionBody;
import org.mos.mcore.model.Transaction.TransactionInfo;
import org.mos.mcore.tools.bytes.BytesComparisons;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Data
@NActorProvider
@Provides(specifications = { ActorService.class }, strategy = "SINGLETON")
@Instantiate(name = "bc_actuactor")
public class ActuactorHandler implements ActorService {

	@ActorRequire(name = "MCoreServices", scope = "global")
	MCoreServices mcore;

	ConcurrentHashMap<Integer, IActuator> actByType = new ConcurrentHashMap<>();

	@Validate
	public void startup() {

		new Thread(new Runnable() {

			@Override
			public void run() {
				while (mcore == null) {
					try {
						Thread.sleep(1000);
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
				actByType.put(0, new TransferActuator(mcore));
				log.debug("create default transferacutator,mcore="+(mcore==null));
			}
		}).start();

	}

	public ByteString onExecute(TransactionInfo transactionInfo) throws Exception {
		return ByteString.EMPTY;
	}

	public synchronized boolean registerActutor(IActuator act) {
		if (actByType.containsKey(act.getType())) {
			return false;
		}
		actByType.put(act.getType(), act);
		return true;
	}

	public IActuator getActuator(int type) {
		return actByType.get(type);
	}

	public void verifySignature(TransactionInfo transactionInfo) throws Exception {
		TransactionInfo.Builder signatureTx = transactionInfo.toBuilder();
		TransactionBody.Builder txBody = signatureTx.getBodyBuilder();
		byte[] oMultiTransactionEncode = txBody.build().toByteArray();
		byte[] hexPubKey = mcore.getCrypto().signatureToKey(oMultiTransactionEncode,
				transactionInfo.getSignature().toByteArray());

		byte[] address = mcore.getCrypto().signatureToAddress(oMultiTransactionEncode,
				transactionInfo.getSignature().toByteArray());

		try {
			if (!mcore.getCrypto().verify(hexPubKey, oMultiTransactionEncode,
					transactionInfo.getSignature().toByteArray())) {
				throw new TransactionVerifyException("signature verify fail with pubkey");
			} else if (!BytesComparisons.equal(address, txBody.getAddress().toByteArray())) {
				throw new TransactionVerifyException("invalid transaction sender");
			}
		} catch (Exception e) {
			log.error("verify exception tx=" + mcore.getCrypto().bytesToHexStr(oMultiTransactionEncode) + " sign="
					+ mcore.getCrypto().bytesToHexStr(transactionInfo.getSignature().toByteArray()) + " pub="
					+ mcore.getCrypto().bytesToHexStr(hexPubKey));
			throw e;
		}
	}

}
