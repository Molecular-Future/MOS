package org.mos.mcore.tools.queue;

import com.google.protobuf.ByteString;

import java.nio.ByteBuffer;

public interface IStorable {

	void toBytes(ByteBuffer buff);

	void fromBytes(ByteBuffer buff);
	
	ByteString getStorableKey();
	
	long calcSize();

}