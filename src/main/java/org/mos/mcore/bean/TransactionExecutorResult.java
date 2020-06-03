package org.mos.mcore.bean;

import com.google.protobuf.ByteString;
import org.mos.mcore.model.Block.BlockInfo;
import org.mos.mcore.model.Transaction.TransactionInfo;
import org.mos.mcore.model.Transaction.TransactionInfoOrBuilder;
import org.mos.mcore.model.Transaction.TransactionStatus;
import org.mos.mcore.tools.bytes.BytesHelper;

public class TransactionExecutorResult {
	public static boolean isDone(TransactionInfoOrBuilder mtx) {
		return BytesHelper.SUCCESS_BYTESTRING.equals(mtx.getStatus().getStatus());
	}

	public static boolean isError(TransactionInfoOrBuilder mtx) {
		return BytesHelper.ERROR_BYTESTRING.equals(mtx.getStatus().getStatus());
	}

	public static boolean isProccessed(TransactionInfoOrBuilder mtx) {
		return isDone(mtx) || isError(mtx);
	}

	public static void setDone(TransactionInfo.Builder mtx, BlockInfo block) {
		setDone(mtx, block, ByteString.EMPTY);
	}

	public static void setDone(TransactionInfo.Builder mtx, BlockInfo block, ByteString result) {
		TransactionStatus.Builder oTransactionStatus = TransactionStatus.newBuilder();
		oTransactionStatus.setResult(result);
		oTransactionStatus.setStatus(BytesHelper.SUCCESS_BYTESTRING);
		oTransactionStatus.setHeight(block.getHeader().getHeight());
		oTransactionStatus.setHash(block.getHeader().getHash());
		oTransactionStatus.setTimestamp(block.getHeader().getTimestamp());
		mtx.setStatus(oTransactionStatus);
	}

	public static void setError(TransactionInfo.Builder mtx, BlockInfo block) {
		setError(mtx, block, ByteString.EMPTY);
	}

	public static void setError(TransactionInfo.Builder mtx, BlockInfo block, ByteString result) {
		TransactionStatus.Builder oTransactionStatus = TransactionStatus.newBuilder();
		oTransactionStatus.setResult(result);
		oTransactionStatus.setStatus(BytesHelper.ERROR_BYTESTRING);
		oTransactionStatus.setHeight(block.getHeader().getHeight());
		oTransactionStatus.setHash(block.getHeader().getHash());
		oTransactionStatus.setTimestamp(block.getHeader().getTimestamp());
		mtx.setStatus(oTransactionStatus);
	}
}
