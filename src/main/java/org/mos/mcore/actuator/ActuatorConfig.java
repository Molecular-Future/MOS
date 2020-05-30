package org.mos.mcore.actuator;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import onight.tfw.outils.conf.PropHelper;
import org.spongycastle.util.encoders.Hex;

import java.math.BigInteger;

@Slf4j
public class ActuatorConfig {
	private static PropHelper prop = new PropHelper(null);

	// 手续费存储账户
	public static byte[] lock_account_address = Hex.decode(prop.get("org.mos.mcore.handler.lock.address", "3db85d2797ddc2a4802e9fff1a0232b0bbf06c8a"));
	// 交易费用存储账户,未启用
	public static byte[] fee_account_address = Hex.decode(prop.get("org.mos.mcore.handler.fee.address", "8502208b949e7c08acb4c2f1aef06caa39891a79"));
	// 创建联合账户的手续费
	public static BigInteger unionAccount_create_lock_balance = readBigIntegerValue(
			"org.mos.mcore.unionaccount.create.lock.balance", "0");
	// 存档交易手续费
	public static BigInteger data_archive_lock_balance = readBigIntegerValue("org.mos.mcore.data.archive.lock.balance",
				"0");
	// token创建时的手续费
	public static BigInteger token_create_lock_balance = readBigIntegerValue("org.mos.mcore.token.create.lock.balance",
			"0");
	// token增发时的手续费
	public static BigInteger token_mint_lock_balance = readBigIntegerValue("org.mos.mcore.token.mint.lock.balance",
			"0");
	// token燃烧时的手续费
	public static BigInteger token_burn_lock_balance = readBigIntegerValue("org.mos.mcore.token.burn.lock.balance",
			"0");
	// token冻结的手续费
	public static BigInteger token_freeze_lock_balance = readBigIntegerValue("org.mos.mcore.token.freeze.lock.balance",
			"0");
	// token解冻的手续费
	public static BigInteger token_unfreeze_lock_balance = readBigIntegerValue(
			"org.mos.mcore.token.unfreeze.lock.balance", "0");
	// 合约创建时的手续费
	public static BigInteger contract_create_lock_balance = readBigIntegerValue("org.mos.mcore.contract.lock.balance",
			"0");
	// cryptotoken创建时的手续费
	public static BigInteger crypto_token_create_lock_balance = readBigIntegerValue(
			"org.mos.mcore.cryptotoken.lock.balance", "0");
	// token的最小发行数量
	public static BigInteger minTokenTotal = readBigIntegerValue("org.mos.mcore.token.min.total", "1000000000000000000");
	// token的最大发行数量
	public static BigInteger maxTokenTotal = readBigIntegerValue("org.mos.mcore.token.max.total", "10000000000000000000000000000");
	// 一个区块的奖励
	// public static BigInteger minerReward = new
	// BigInteger(prop.get("block.miner.reward", "0"));
	
	//evfs 文件信息汇总根账号
	public static byte[] evfs_root_address = Hex.decode(prop.get("org.mos.mcore.handler.evfs.root.address", "d34247a1c149a7005fa23e8ce6cca7dcf50ffbbe"));
	public static ByteString evfs_root_address_BS = ByteString.copyFrom(evfs_root_address);


	// FIXME 这是为测试准备的，不要发布到生产环境
	public static ByteString white_list_admin = ByteString
			.copyFrom(Hex.decode(prop.get("org.mos.mcore.handler.whitelist.admin", "")));
	
	private static BigInteger readBigIntegerValue(String key, String defaultVal) {
		try {
			return new BigInteger(prop.get(key, defaultVal));
		} catch (Exception e) {
			log.error("cannot read key::" + key, e);
		}
		return new BigInteger(defaultVal);
	}

}
