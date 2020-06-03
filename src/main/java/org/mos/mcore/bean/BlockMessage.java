package org.mos.mcore.bean;

import lombok.Data;
import org.mos.mcore.model.Block.BlockInfo;

@Data
public class BlockMessage {
	byte[] hash;
	byte[] parentHash;
	long height;
	BlockMessageStatusEnum status = BlockMessageStatusEnum.UNKNOWN;
	BlockInfo block;

	public enum BlockMessageStatusEnum {
		CONNECT, STABLE, UNKNOWN
	}

	public BlockMessage(byte[] hash, byte[] parentHash, long height, BlockInfo block) {
		this.hash = hash;
		this.parentHash = parentHash;
		this.height = height;
		this.block = block;
	}
}
