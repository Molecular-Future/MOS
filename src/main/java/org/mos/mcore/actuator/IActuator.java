package org.mos.mcore.actuator;

import com.google.protobuf.ByteString;
import org.mos.mcore.bean.ApplyBlockContext;
import org.mos.mcore.bean.TransactionInfoWrapper;
import org.mos.mcore.concurrent.AccountInfoWrapper;

import java.util.concurrent.ConcurrentHashMap;

public interface IActuator {

	/**
	 * 是否需要签名
	 * @return
	 */
	boolean needSignature();

	/**
	 * 前置执行【并行】
	 * @param sender
	 * @param transactionInfo
	 * @throws Exception
	 */
	public void prepareExecute(AccountInfoWrapper sender, TransactionInfoWrapper transactionInfo) throws Exception;

	/**
	 * 执行合约 【并行】
	 * @param sender
	 * @param transactionInfo
	 * @param blockContext
	 * @return
	 * @throws Exception
	 */
	ByteString execute(AccountInfoWrapper sender, TransactionInfoWrapper transactionInfo, ApplyBlockContext blockContext)
			throws Exception;

	/**
	 * 合约校验
	 * @param transactionInfo
	 * @throws Exception
	 */
	void onVerifySignature(TransactionInfoWrapper transactionInfo) throws Exception;

	/**
	 * 加载关联账户，用于并行执行时重复加载
	 * @param oTransactionInfo
	 * @param accounts
	 */
	void preloadAccounts(TransactionInfoWrapper oTransactionInfo, ConcurrentHashMap<ByteString, AccountInfoWrapper> accounts);

	/**
	 * 执行器类型
	 * @return
	 */
	int getType();

}