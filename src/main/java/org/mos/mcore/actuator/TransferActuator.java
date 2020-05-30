package org.mos.mcore.actuator;

import com.google.protobuf.ByteString;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.mos.mcore.actuator.exception.TransactionParameterInvalidException;
import org.mos.mcore.bean.ApplyBlockContext;
import org.mos.mcore.bean.TransactionInfoWrapper;
import org.mos.mcore.concurrent.AccountInfoWrapper;
import org.mos.mcore.handler.MCoreServices;
import org.mos.mcore.model.Transaction.TransactionBody;
import org.mos.mcore.model.Transaction.TransactionOutput;
import org.mos.mcore.tools.bytes.BytesHelper;

import java.math.BigInteger;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Data
public class TransferActuator implements IActuator {

	MCoreServices mcore;

	public TransferActuator(MCoreServices mcore) {
		this.mcore = mcore;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.mos.mcore.actuator.IActuator#needSignature()
	 */
	@Override
	public boolean needSignature() {
		return true;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.mos.mcore.actuator.IActuator#execute(org.mos.mcore.concurrent
	 * .AccountInfoWrapper, org.mos.mcore.model.Transaction.TransactionInfo,
	 * org.mos.mcore.bean.ApplyBlockContext)
	 */
	@Override
	public ByteString execute(AccountInfoWrapper sender, TransactionInfoWrapper transactionInfo,
			ApplyBlockContext blockContext) throws Exception {
		TransactionBody txbody = transactionInfo.getTxinfo().getBody();
		if (txbody.getOutputsCount() == 0) {
			throw new TransactionParameterInvalidException("parameter invalid, outputs must not be null");
		}
		BigInteger totalAmount = BigInteger.ZERO;
		BigInteger amounts[] = new BigInteger[txbody.getOutputsCount()];
		int cc = 0;
		for (TransactionOutput oOutput : txbody.getOutputsList()) {
			// 主币
			BigInteger outputAmount = BytesHelper.bytesToBigInteger(oOutput.getAmount().toByteArray());
			if (outputAmount.compareTo(BigInteger.ZERO) < 0) {
				throw new TransactionParameterInvalidException("parameter invalid, amount must large than 0");
			}
			amounts[cc] = outputAmount;
			totalAmount = totalAmount.add(outputAmount);
			cc++;
		}
		if (sender.zeroSubCheckAndGet(totalAmount).signum() < 0) {
			//
			throw new TransactionParameterInvalidException("parameter invalid, balance of the sender is not enough");
		}
		cc=0;
		for (TransactionOutput oTransactionOutput : txbody.getOutputsList()) {
			AccountInfoWrapper receiver = blockContext.getAccounts().get(oTransactionOutput.getAddress());
			// 处理amount
//			BigInteger outputAmount = BytesHelper.bytesToBigInteger(oTransactionOutput.getAmount().toByteArray());
			receiver.addAndGet(amounts[cc]);
			cc++;
		}
		// 发送方移除主币余额
		return ByteString.EMPTY;
	}

	@Override
	public void onVerifySignature(TransactionInfoWrapper transactionInfo) throws Exception {
		mcore.getActuactorHandler().verifySignature(transactionInfo.getTxinfo());
	}

	@Override
	public int getType() {
		return 0;
	}

	public void prepareExecute(AccountInfoWrapper sender, TransactionInfoWrapper transactionInfo) throws Exception {
		// 判断发送方账户的nonce
		// int nonce = sender.getNonce();
		// if (nonce > transactionInfo.getBody().getNonce()) {
		// throw new TransactionParameterInvalidException(
		// "parameter invalid, sender nonce is large than transaction nonce");
		// }

		BigInteger txFee = BigInteger.ZERO;
		// BytesHelper.bytesToBigInteger(transactionInfo.getBody().getFee().toByteArray());
		if (txFee.compareTo(BigInteger.ZERO) < 0) {
			throw new TransactionParameterInvalidException("parameter invalid, fee must large than 0");
		}
	}

	@Override
	public void preloadAccounts(TransactionInfoWrapper oTransactionInfo,
			ConcurrentHashMap<ByteString, AccountInfoWrapper> accounts) {
	}

}
