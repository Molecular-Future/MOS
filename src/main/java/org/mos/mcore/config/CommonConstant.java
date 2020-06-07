package org.mos.mcore.config;

import java.math.BigInteger;

public class CommonConstant {
	public static final BigInteger Account_Default_Nonce = BigInteger.ZERO;
	public static final BigInteger Account_Default_Balance = BigInteger.ZERO;

	public static final byte[] Exists_Crypto_Token = "DB_EXISTS_CRYPTO_TOKEN_31041002478455727644".getBytes();
	public static final byte[] Exists_Token = "DB_EXISTS_TOKEN_31041002478455727644".getBytes();
	public static final byte[] Exists_Contract = "DB_EXISTS_CONTRACT_31041002478455727644".getBytes();

	public static final byte[] Node_Account_Address = "NODE_ACCOUNT_ADDRESS_31041002478455727644".getBytes();

	public static final byte[] Max_Connected_Block = "MAX_CONNECTED_BLOCK_31041002478455727644".getBytes();
	public static final byte[] Max_Stabled_Block = "MAX_STABLED_BLOCK_31041002478455727644".getBytes();
	
	// FIXME 这是为测试准备的，不要发布到生产环境
	public static final byte[] White_List = "WHITE_LIST_31041002478455727644".getBytes();
}
