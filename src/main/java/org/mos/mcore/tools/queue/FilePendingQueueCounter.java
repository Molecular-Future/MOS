package org.mos.mcore.tools.queue;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.OutputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.Formatter;
import java.util.concurrent.atomic.AtomicLong;

@Data
@NoArgsConstructor
public class FilePendingQueueCounter implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	public AtomicLong ptr_pending = new AtomicLong(0);
	public AtomicLong ptr_sent = new AtomicLong(0);
	public AtomicLong ptr_buffered = new AtomicLong(0);
	public AtomicLong ptr_saved = new AtomicLong(0);
	public AtomicLong fid_buffer = new AtomicLong(0);
	public AtomicLong fid_pending = new AtomicLong(0);

	boolean dirty = false;

	public void toBytes(ByteBuffer buff) {
		buff.putLong(ptr_pending.get());
		buff.putLong(ptr_sent.get());
		buff.putLong(ptr_buffered.get());
		buff.putLong(ptr_saved.get());
		buff.putLong(fid_buffer.get());
		buff.putLong(fid_pending.get());

	}

	public void fromBytes(ByteBuffer buff) {
		this.ptr_pending.set(buff.getLong());
		this.ptr_sent.set(buff.getLong());
		this.ptr_buffered.set(buff.getLong());
		this.ptr_saved.set(buff.getLong());
		this.fid_buffer.set(buff.getLong());
		this.fid_pending.set(buff.getLong());

	}

	public void toTxt(OutputStream out) {
		try (Formatter formatter = new Formatter(out)) {
			formatter.format("%d,%d,%d,%d,%d,%d,%d\n", ptr_pending.get(), ptr_sent.get(), ptr_buffered.get(),
					ptr_saved.get(), fid_buffer.get(), fid_pending.get(), System.currentTimeMillis());
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void fromText(String str) {
		String strarr[]=str.split(",");
		if(strarr.length==7) {
			int cc=0;
			this.ptr_pending.set(Long.parseLong(strarr[cc]));cc++;
			this.ptr_sent.set(Long.parseLong(strarr[cc]));cc++;
			this.ptr_buffered.set(Long.parseLong(strarr[cc]));cc++;
			this.ptr_saved.set(Long.parseLong(strarr[cc]));cc++;
			this.fid_buffer.set(Long.parseLong(strarr[cc]));cc++;
			this.fid_pending.set(Long.parseLong(strarr[cc]));cc++;
		}
		

	}

}