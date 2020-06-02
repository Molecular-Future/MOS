package org.mos.mcore.api;

import org.mos.mcore.model.Block.BlockInfo;

public interface IBlockObserver {
	void onNotify(BlockInfo bi);
}
