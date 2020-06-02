package org.mos.mcore.api;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import onight.osgi.annotation.NActorProvider;
import onight.tfw.ntrans.api.ActorService;
import org.apache.felix.ipojo.annotations.Instantiate;
import org.apache.felix.ipojo.annotations.Provides;
import org.mos.mcore.model.Block.BlockInfo;

import java.util.ArrayList;
import java.util.List;

@NActorProvider
@Provides(specifications = { ActorService.class }, strategy = "SINGLETON")
@Instantiate(name = "bc_block_subscriber")
@Slf4j
@Data
public class BlockSubscriber implements ActorService {
	List<IBlockObserver> observers = new ArrayList<>();

	/**
	 * 增加观察者
	 * 
	 * @param observer
	 */
	public void attach(IBlockObserver observer) {
		observers.add(observer);
	}

	/**
	 * 移除观察者
	 * 
	 * @param observer
	 */
	public void detach(IBlockObserver observer) {
		observers.remove(observer);
	}

	/**
	 * 向观察者发出通知
	 * 
	 * @param block
	 */
	public void notifyAll(BlockInfo block) {
		observers.stream().forEach(observer -> {
			try {
				observer.onNotify(block);
			} catch (Throwable t) {

			}
		});
	}
}
