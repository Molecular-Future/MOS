package org.mos.mcore.crypto;

import lombok.Data;

@Data
public class KeyPairs {
	String pubkey;
	String prikey;
	String address;
	String bcuid;

	public KeyPairs(String pubkey, String prikey, String address, String bcuid) {
		super();
		this.pubkey = pubkey;
		this.prikey = prikey;
		this.address = address;
		this.bcuid = bcuid;
	}

}
