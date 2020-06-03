package org.mos.mcore.bean;

import com.google.protobuf.ByteString;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.mos.mcore.concurrent.AccountInfoWrapper;
import org.mos.mcore.model.Block.BlockInfo;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

@Data
@AllArgsConstructor
public class ApplyBlockContext {
	BlockInfo.Builder currentBlock;
	BlockInfo blockInfo;
	ConcurrentHashMap<ByteString, AccountInfoWrapper> accounts;
	byte[][] results;
	CountDownLatch cdl;
	byte[][] txkeys;
	byte[][] txvalues;
}
