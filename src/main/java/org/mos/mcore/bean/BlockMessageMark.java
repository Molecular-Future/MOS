package org.mos.mcore.bean;

import lombok.Data;
import org.mos.mcore.model.Block.BlockInfo;
import org.mos.mcore.tools.bytes.BytesComparisons;

import java.util.ArrayList;
import java.util.List;

@Data
public class BlockMessageMark {
	private BlockMessageMarkEnum mark;
	private BlockInfo block;
	private List<BlockMessage> childBlock = new ArrayList<>();

	public enum BlockMessageMarkEnum {
		DROP, CONNECTED, EXISTS_PREV, CACHE, APPLY, APPLY_CHILD, DONE, ERROR, NEED_TRANSACTION
	}

	public void addChildBlock(BlockMessage child) {
		for (BlockMessage bm : childBlock) {
			if (BytesComparisons.equal(bm.getHash(), child.getHash())) {
				return;
			}
		}
		childBlock.add(child);
	}
}
