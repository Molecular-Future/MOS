package org.mos.mcore.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

@Data
@Slf4j
public class StatRunner implements Runnable {
	public static long processTxCount = 0;
	public static long blockInterval = 0;
	public static AtomicLong createTxCounter = new AtomicLong(0);
	public static AtomicLong receiveTxCounter = new AtomicLong(0);
//	public static AtomicLong processTxCounter = new AtomicLong(0);
	public static AtomicLong totalTxComfirnTimeCounter = new AtomicLong(0);
	public static AtomicLong totalTxSizeCounter = new AtomicLong(0);
	public static double txReceiveTps = 0.0;
	public static double txProcessTps = 0.0;
	public static double maxReceiveTps = 0.0;
	public static double maxProcessTps = 0.0;
//	public static int maxConfirmInterval = 0;
//	public static int averageConfirmInterval = 0;
	public static int txConfirmQueueSize = 0;
	public static int txMessageQueueSize = 0;
	public static int averageTxSize = 0;

	long lastUpdateTime = System.currentTimeMillis();
	boolean running = true;

	private long lastReceiveTxCount = 0;

	@Override
	public void run() {
		while (running) {
			try {
				long curAcceptTxCount = receiveTxCounter.get() + createTxCounter.get();
				long now = System.currentTimeMillis();
				long timeDistance = now - lastUpdateTime;
				txReceiveTps = (curAcceptTxCount - lastReceiveTxCount) * 1000.f / (timeDistance + 1);
				lastReceiveTxCount = curAcceptTxCount;
				if (maxReceiveTps < txReceiveTps) {
					maxReceiveTps = txReceiveTps;
				}

				if (blockInterval != 0) {
					txProcessTps = processTxCount * 1000.f / blockInterval;
				} else {
					txProcessTps = 0.0;
				}

				if (maxProcessTps < txProcessTps) {
					maxProcessTps = txProcessTps;
				}

//				averageConfirmInterval = new Long(totalTxComfirnTimeCounter.get() / mcs.getChainConfig().getConfigAccount().getBalance().longValue()).intValue();
//				if (maxConfirmInterval < averageConfirmInterval) {
//					maxConfirmInterval = averageConfirmInterval;
//				}

				if (totalTxSizeCounter.get() != 0) {
					averageTxSize = (int) (totalTxSizeCounter.get() / (curAcceptTxCount - lastReceiveTxCount));
					totalTxSizeCounter.set(0);
				}

				lastUpdateTime = System.currentTimeMillis();

			} catch (Throwable t) {

			}

			try {
//				log.error("monitor {} {} {} {} {} {} {} {} {} {} {} {} {}", blockInterval, txMessageQueueSize,
//						txConfirmQueueSize, txReceiveTps, maxReceiveTps, txProcessTps, maxProcessTps, createTxCounter,
//						receiveTxCounter, (createTxCounter.get() + receiveTxCounter.get()), processTxCounter,
//						averageConfirmInterval, maxConfirmInterval);
				Thread.sleep(5000);
			} catch (InterruptedException e) {
			}
		}
	}
}
