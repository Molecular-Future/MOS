package org.mos.mcore.concurrent;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.mos.mcore.model.Account.AccountInfo;
import org.mos.mcore.model.GenesisBlockOuterClass.ChainConfig;
import org.mos.mcore.tools.bytes.BytesHelper;

import java.math.BigInteger;

@Data
@Slf4j
public class ChainConfigWrapper extends AccountInfoWrapper {

	protected ChainConfig.Builder chainConfig;
	
	protected BigInteger blockRewards;

	public ChainConfigWrapper(AccountInfo.Builder info) {
		super(info);
		if (!info.getExtData().isEmpty()) {
			try {
				chainConfig = ChainConfig.newBuilder().mergeFrom(info.getExtData());
			} catch (InvalidProtocolBufferException e) {
				e.printStackTrace();
			}
		} else {
			chainConfig = ChainConfig.newBuilder();
		}
		
		blockRewards = BytesHelper.bytesToBigInteger(chainConfig.getBlockRewards().toByteArray());
	}

	public ChainConfigWrapper(AccountInfo.Builder info, ChainConfig _chainConfig) {
		super(info);
		chainConfig = _chainConfig.toBuilder();
		setDirty(true);
		info.setExtData(_chainConfig.toByteString());
		blockRewards = BytesHelper.bytesToBigInteger(chainConfig.getBlockRewards().toByteArray());
	}

	@Override
	public synchronized AccountInfo build(long blocknumber) {
		if (isDirty) {
			info.setExtData(chainConfig.build().toByteString());
		}
		return super.build(blocknumber);
	}

	public void increaseBlockedTxCount(int txcount) {
		super.addAndGet(BigInteger.valueOf(txcount));
	}
}
