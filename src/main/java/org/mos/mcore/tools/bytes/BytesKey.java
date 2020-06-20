package org.mos.mcore.tools.bytes;

import org.spongycastle.util.encoders.Hex;

import java.io.Serializable;
import java.util.Arrays;

public class BytesKey implements Comparable<BytesKey>, Serializable {
	private static final long serialVersionUID = 1L;
	private final byte[] data;
	private int hashCode = 0;

	public BytesKey(byte[] data) {
		if (data == null)
			throw new NullPointerException("Data must not be null");
		this.data = data;
		this.hashCode = Arrays.hashCode(data);
	}

	public boolean equals(Object other) {
		if (!(other instanceof BytesKey))
			return false;
		byte[] otherData = ((BytesKey) other).getData();
		return BytesComparisons.compareTo(data, 0, data.length, otherData, 0, otherData.length) == 0;
	}

	@Override
	public int hashCode() {
		return hashCode;
	}

	@Override
	public int compareTo(BytesKey o) {
		return BytesComparisons.compareTo(data, 0, data.length, o.getData(), 0, o.getData().length);
	}

	public byte[] getData() {
		return data;
	}

	@Override
	public String toString() {
		return Hex.toHexString(data);
	}
}
