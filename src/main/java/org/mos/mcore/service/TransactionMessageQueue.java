package org.mos.mcore.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import onight.osgi.annotation.NActorProvider;
import onight.tfw.mservice.NodeHelper;
import onight.tfw.ntrans.api.ActorService;
import onight.tfw.ntrans.api.annotation.ActorRequire;
import org.apache.felix.ipojo.annotations.Instantiate;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Validate;
import org.mos.mcore.bean.TransactionMessage;
import org.mos.mcore.tools.queue.FilePendingQueue;
import org.mos.mcore.tools.queue.IPendingQueue;
import org.mos.mcore.tools.queue.IStorable;
import org.fc.zippo.dispatcher.IActorDispatcher;

@NActorProvider
@Provides(specifications = { ActorService.class }, strategy = "SINGLETON")
@Instantiate(name = "bc_transaction_message_queue")
@Slf4j
@Data
public class TransactionMessageQueue extends FilePendingQueue<TransactionMessage>
		implements ActorService, IPendingQueue<IStorable>, Runnable {

	@ActorRequire(name = "zippo.ddc", scope = "global")
	IActorDispatcher iddc = null;

	public TransactionMessageQueue() {
		this("tx_message_queue" + (NodeHelper.getCurrNodeListenOutPort() - 5100));
		
	}

	public TransactionMessageQueue(String qname) {
		super(qname);
	}

	
	@Validate
	public void startup() {
		new Thread(new Runnable() {
			@Override
			public void run() {
				while (iddc == null) {
					try {
						Thread.sleep(1000);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
				log.debug("bc_transaction_message_queue startup");

				init(iddc);
			}
		}).start();

	}

	@Override
	public void shutdown() {

	}

	@Override
	public IStorable newStoreObject() {
		return new TransactionMessage(null, null);
	}

}
