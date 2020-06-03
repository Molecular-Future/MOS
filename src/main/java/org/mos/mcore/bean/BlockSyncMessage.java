package org.mos.mcore.bean;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BlockSyncMessage {
	long currentHeight;
	long wantHeight;
	private List<byte[]> syncTxHash = new ArrayList<>();

	public enum BlockSyncCodeEnum {
		SS, ER, LB, LT
	}

	BlockSyncCodeEnum syncCode;
}
