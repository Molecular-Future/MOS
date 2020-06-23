package org.mos.mcore.tools.queue;

import com.google.protobuf.ByteString;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import onight.tfw.ntrans.api.ActorService;
import onight.tfw.otransio.api.PacketHelper;
import onight.tfw.otransio.api.beans.FramePacket;
import onight.tfw.outils.conf.PropHelper;
import org.fc.zippo.dispatcher.IActorDispatcher;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Data
public abstract class FilePendingQueue<T extends IStorable>
		implements ActorService, Runnable, IPendingQueue<IStorable> {
	PropHelper prop = new PropHelper(null);
	String qname = "fpq";

	ConcurrentHashMap<ByteString, IStorable> txByHash = new ConcurrentHashMap<>();
	LinkedBlockingQueue<IStorable> pendingQueue = new LinkedBlockingQueue<>();
	LinkedBlockingQueue<IStorable> bufferQueue = new LinkedBlockingQueue<>();

	int maxMemoryElement = prop.get("org.bc.account.store.fpq.maxmemorysize", 200000);
	int minMemoryElement = prop.get("org.bc.account.store.fpq.minmemorysize", 10000);

	int writeBufferSize = prop.get("org.bc.account.store.fpq.writebuffersize", 8 * 1024 * 1024);// 8M
	int maxFileSize = prop.get("org.bc.account.store.fpq.maxfilesize", 1024 * 1024 * 8);// 64M

	public FilePendingQueue(String qname) {
		this.qname = qname;
	}

	@Override
	public boolean hasMoreElement() {
		return (getCounter().getPtr_sent().get() < getCounter().getPtr_pending().get());
	}

	FilePendingQueueCounter counter = new FilePendingQueueCounter();
	String COUNTER_DIR = "." + File.separator + "db" + File.separator + "fqueue" + File.separator + "tx"
			+ File.separator;
	IActorDispatcher ddc = null;

	public void init(IActorDispatcher ddc) {
		try {
			maxMemoryElement = prop.get("org.bc.account.store." + qname + ".maxmemorysize", 500000);
			minMemoryElement = prop.get("org.bc.account.store." + qname + ".minmemorysize", 100000);

			writeBufferSize = prop.get("org.bc.account.store." + qname + ".writebuffersize", 8 * 1024 * 1024);// 8M
			maxFileSize = prop.get("org.bc.account.store." + qname + ".maxfilesize", 1024 * 1024 * 8);// 64M

			COUNTER_DIR = "." + File.separator + "db" + File.separator + "fqueue" + File.separator + qname
					+ File.separator;

			counter = loadCounter();
			counter.getPtr_buffered().set(counter.getPtr_sent().get());
			loadBuffer();

			log.debug("FilePendingQueue init " + qname);
		} catch (Exception e) {
			log.error("TxLDBPendingQueue-Init Errror:", e);
		}
		this.ddc = ddc;
		ddc.scheduleWithFixedDelay(this, 3, prop.get("org.bc.account.store.txpend.tick", 3), TimeUnit.SECONDS);

	}

	AtomicBoolean loading = new AtomicBoolean(false);
	AtomicBoolean notifyLoading = new AtomicBoolean(false);

	public void run() {
		if (loading.compareAndSet(false, true)) {
			try {
				syncToDB(true);
			} catch (Throwable e) {
				log.error("error in sync to db:" + e, e);
			}
			try {
				loadBuffer();
			} catch (Throwable e) {
				log.error("error in load from db:" + e, e);
			} finally {
				loading.set(false);
			}
		}
		notifyLoading.set(false);
	}

	long lastSync = 0;

	public void saveCounter() {
		if (counter.isDirty() && (System.currentTimeMillis() - lastSync) > 3000) {
//			log.error("saveCounter:" + qname + ",loadcount=" + loadCount.get() + "/" + loadSize.get());
			File index = new File(COUNTER_DIR, "index");
			index.getParentFile().mkdirs();
			try (FileOutputStream fout = new FileOutputStream(index)) {
				counter.toTxt(fout);
				// fout.write(writeBuffer.array(), 0, writeBuffer.position());
			} catch (Throwable t) {
				log.error("error in read counter file:" + index.getAbsolutePath(), t);
			} finally {
			}
			counter.setDirty(false);
			loadSize.set(0);
			loadCount.set(0);
			lastSync = System.currentTimeMillis();
		}
	}

	public FilePendingQueueCounter loadCounter() {
		FilePendingQueueCounter counter = new FilePendingQueueCounter();
		File index = new File(COUNTER_DIR, "index");
		if (index.exists()) {
			try (FileInputStream fin = new FileInputStream(index)) {
				int rsize = fin.available();
				byte bb[] = new byte[rsize];
				fin.read(bb);
				String str = new String(bb);
				log.debug("load count=" + str);
				counter.fromText(str);
			} catch (Throwable t) {
				log.error("error in read counter file:" + index.getAbsolutePath(), t);
			}
		}
		counter.setDirty(false);
		return counter;
	}

	boolean overFlowMemory = false;

	public void addElement(IStorable hp) {
		if (txByHash.size() >= maxMemoryElement || txByHash.putIfAbsent(hp.getStorableKey(), hp) == null) {
			pendingQueue.add(hp);
			counter.ptr_pending.incrementAndGet();
			if (bufferQueue.size() < maxMemoryElement && !overFlowMemory) {
				bufferQueue.add(hp);
				counter.ptr_buffered.incrementAndGet();
			} else {
				overFlowMemory = true;
//				long start = System.currentTimeMillis();
				// log.info("wait for sync to db");

				if (notifyLoading.compareAndSet(false, true)) {
					// ddc.executeNow(fp, this);
					this.run();
				}
				// log.info("waitup for sync to db,cost=" + (System.currentTimeMillis() -
				// start));
			}
		}
	}

	ByteBuffer writeBuffer = ByteBuffer.allocate(writeBufferSize);
	ByteBuffer readBuffer = ByteBuffer.allocate(writeBufferSize);

	public void flushToFile() {
		int cursize = writeBuffer.position();
		if (cursize > 0) {
			File file = new File(COUNTER_DIR, "data." + counter.fid_pending.get());
			file.getParentFile().mkdirs();

			while (file.exists() && file.length() + cursize >= maxFileSize) {
				log.debug("new pendingfile:file=" + file.length() + ",cursize=" + cursize + ",maxFilesize="
						+ maxFileSize);
				file = new File(COUNTER_DIR, "data." + counter.fid_pending.incrementAndGet());
			}
			try (FileOutputStream fout = new FileOutputStream(file, true)) {
				fout.write(writeBuffer.array(), 0, cursize);
			} catch (Throwable t) {
				log.error("error in write file:" + file.getAbsolutePath(), t);
			} finally {
				writeBuffer.rewind();
				counter.setDirty(true);
			}
		}
	}

	public abstract IStorable newStoreObject();

	AtomicLong loadCount = new AtomicLong();
	AtomicLong loadSize = new AtomicLong();

	public synchronized void loadBuffer() {
		long tryid = counter.fid_buffer.get();
		while (bufferQueue.size() < maxMemoryElement && tryid <= counter.fid_pending.get()) {
			File file = new File(COUNTER_DIR, "data." + tryid);
			if (file.exists()) {
				loadCount.incrementAndGet();
				try (FileInputStream fin = new FileInputStream(file); DataInputStream din = new DataInputStream(fin)) {
					boolean fileSizeValid = true;
					while (fileSizeValid && din.available() >= 8 + 4) {
						long saveid = din.readLong();
						int totalsize = din.readInt();
						loadSize.addAndGet(totalsize);
						if (totalsize <= 0) {
							log.error("totalsize is zero:" + totalsize + ",file=" + file.getAbsolutePath());
						} else if (saveid <= counter.ptr_buffered.get()) {
							din.skip(totalsize);
						} else {
							// log.debug("load and put id=" + saveid + ",buffer_ptr=" +
							// counter.ptr_buffered.get());
							IStorable hp = newStoreObject();
							ByteBuffer _sureBuffer = readBuffer;
							if (totalsize > readBuffer.capacity() - 4) {
								log.error("too large elementsize=" + (totalsize + 4) + ",limit=" + readBuffer.capacity()
										+ ",file=" + file.getAbsolutePath());
								_sureBuffer = ByteBuffer.allocate(totalsize + 100);
							}
							_sureBuffer.clear();
							_sureBuffer.putInt(totalsize);
							if (din.available() >= totalsize) {
								int offset = 4;
								int rsize = 0;
								while ((rsize = din.read(_sureBuffer.array(), offset, totalsize + 4 - offset)) > 0) {
									offset += rsize;
								}
								if (offset < totalsize + 4) {
									log.error("read file error.totalsize=" + (totalsize + 4) + ",readsize="
											+ _sureBuffer.position() + ",file=" + file.getAbsolutePath());
								} else {
									_sureBuffer.rewind();
									_sureBuffer.limit(totalsize + 4);
									hp.fromBytes(_sureBuffer);
									bufferQueue.add(hp);
									counter.ptr_buffered.incrementAndGet();
									if (bufferQueue.size() >= maxMemoryElement) {
										break;
									}
								}
							} else {
								log.error("file input not enough=" + (din.available() + 4) + ",totalsize=" + totalsize
										+ ",limit=" + _sureBuffer.capacity() + ",file=" + file.getAbsolutePath());
								fileSizeValid = false;
								break;
							}

						}
					}

				} catch (Throwable t) {
					log.error("error in reading file:" + file.getAbsolutePath(), t);
				}
			}
			if (bufferQueue.size() < maxMemoryElement) {
				tryid++;
			}
		}
		// sync to db.
		counter.fid_buffer.set(Math.min(tryid, counter.fid_pending.get()));
	}

	public synchronized void syncToDB(boolean force) {
		int delta = 100000;
		if (force) {
			delta = 0;
		}
		if (counter.ptr_saved.get() + delta < counter.ptr_pending.get()) {
			IStorable hp;
			// log.debug("syncToDB");
			while ((hp = pendingQueue.poll()) != null) {
				txByHash.remove(hp.getStorableKey());
				long saveid = counter.ptr_saved.incrementAndGet();
				try {
					long calcsize = hp.calcSize();
					if (writeBuffer.position() + calcsize >= writeBuffer.capacity() - 64) {
						flushToFile();
					}
					writeBuffer.putLong(saveid);
					hp.toBytes(writeBuffer);
				} catch (Exception e) {
					log.error("eto bytes error::", e);
				}
			}
			flushToFile();
		}
		saveCounter();

	}

	public void addLast(IStorable hp) {
		addElement(hp);
	}

	public int size() {
		return (int) (counter.ptr_pending.get() - counter.ptr_sent.get());
	}

	FramePacket fp = PacketHelper.genSyncPack("txpend", "sys", "");

	public IStorable pollFirst() {
		List<IStorable> ret = poll(1);
		if (ret != null && ret.size() > 0) {
			return ret.get(0);
		}

		return null;
	}

	public List<IStorable> poll(int size) {
		List<IStorable> ret = new ArrayList<>();
		boolean setDirty = false;
		boolean preload = false;
		if (bufferQueue.size() < minMemoryElement && counter.ptr_buffered.get() < counter.ptr_pending.get()) {// preload
			if (notifyLoading.compareAndSet(false, true)) {
				preload = true;
				ddc.executeNow(fp, this);
			}
		}
		try {
			for (int i = 0; i < size && counter.ptr_sent.get() < counter.ptr_buffered.get(); i++) {
				IStorable hp = null;
				if (preload) {
					preload = false;
					hp = bufferQueue.poll(10, TimeUnit.MILLISECONDS);
				} else {
					hp = bufferQueue.poll();
				}
				if (hp == null) {
					log.info("get null tx from file-pending-queue");
					break;
				} else {
					counter.ptr_sent.incrementAndGet();
					setDirty = true;
					ret.add(hp);
				}
			}
		} catch (Exception e) {
			log.error("error on poll " + e);
		}
		if (setDirty) {
			counter.setDirty(true);
		}
		if (counter.ptr_buffered.get() < counter.ptr_pending.get() && (bufferQueue.size() < minMemoryElement)) {
			if (notifyLoading.compareAndSet(false, true)) {
				ddc.executeNow(fp, this);
			}
		}

		return ret;
	}

	public String getStatInfo() {
		return " counter::p=" + counter.getPtr_pending().get() + ",s=" + counter.getPtr_sent().get() + ",buff="
				+ counter.ptr_buffered.get() + ",db=" + counter.getPtr_saved().get() //
				+ ",buff.q=" + bufferQueue.size() + ",size=" + size();// +",counter="+counter.getPtr_sent().hashCode()+"/"+counter.getPtr_buffered().hashCode();
	}
}
