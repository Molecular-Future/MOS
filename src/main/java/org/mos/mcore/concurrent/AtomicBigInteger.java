package org.mos.mcore.concurrent;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicReference;

public class AtomicBigInteger {

	private final AtomicReference<BigInteger> balance;

	public AtomicBigInteger(BigInteger balance) {
		this.balance = new AtomicReference<>(balance);
	}

	public BigInteger addAndGet(BigInteger bi) {
		return balance.accumulateAndGet(bi, (previous, x) -> previous.add(x));
	}

	public BigInteger zeroSubCheckAndGet(BigInteger abi) {
		for (;;) {
			BigInteger org = this.balance.get();
			BigInteger expect = org.subtract(abi);
			if (org.compareTo(abi) < 0) {
				return expect;
			}
			if (balance.compareAndSet(org, expect)) {
				return expect;
			}
		}
	}

	public BigInteger incrementAndGet() {
		return balance.accumulateAndGet(BigInteger.ONE, (previous, x) -> previous.add(x));
	}

	public BigInteger get() {
		return balance.get();
	}

//	public static void main(String[] args) {
//		final AtomicBigInteger ai = new AtomicBigInteger(BigInteger.valueOf(100 * 10000));
//
//		BigInteger sub = BigInteger.valueOf(10);
//		int threadcount = 100;
//		CountDownLatch cdl = new CountDownLatch(threadcount);
//		int cc = 100000;
//		System.out.println("ai=="+ai.get().toString(10));
//		AtomicLong dc=new AtomicLong(0);
//		for (int th = 0; th < threadcount; th++) {
//			new Thread(new Runnable() {
//				@Override
//				public void run() {
//					// TODO Auto-generated method stub
//					try {
//						for (int i = 0; i < cc; i++) {
//							if (ai.zeroSubCheckAndGet(sub).signum() == -1) {
//								break;
//							}
//							dc.incrementAndGet();
//							try {
//								Thread.sleep(10);
//							} catch (InterruptedException e) {
//								// TODO Auto-generated catch block
//								e.printStackTrace();
//							}
//							
//						}
//						
//					} finally {
//						cdl.countDown();
//					}
//				}
//			}).start();
//
//		}
//		try {
//			cdl.await(1000, TimeUnit.SECONDS);
//		} catch (InterruptedException e) {
//			e.printStackTrace();
//		}
//
//		System.out.println("final==>" + ai.get().toString(10)+",dc="+dc);
//
//	}

}
