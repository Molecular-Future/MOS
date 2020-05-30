package org.mos.mcore.action.chain;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import onight.oapi.scala.commons.SessionModules;
import onight.osgi.annotation.NActorProvider;
import onight.tfw.async.CompleteHandler;
import onight.tfw.ntrans.api.annotation.ActorRequire;
import onight.tfw.otransio.api.PacketHelper;
import onight.tfw.otransio.api.beans.FramePacket;
import org.mos.mcore.api.ICryptoHandler;
import org.mos.mcore.config.StatRunner;
import org.mos.mcore.handler.MCoreServices;
import org.mos.mcore.model.Action.ActionCommand;
import org.mos.mcore.model.Action.ActionModule;
import org.mos.mcore.model.Action.ChainSummaryMessage;
import org.mos.mcore.model.Action.RetChainSummaryMessage;
import org.mos.mcore.model.Action.RetChainSummaryMessage.ChainSummaryBlock;
import org.mos.mcore.model.Block.BlockInfo;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

@NActorProvider
@Slf4j
@Data
public class GetChainSummaryImpl extends SessionModules<ChainSummaryMessage> {
	@ActorRequire(name = "MCoreServices", scope = "global")
	MCoreServices mcs;
	@ActorRequire(name = "bc_crypto", scope = "global")
	ICryptoHandler crypto;

	@Override
	public String[] getCmds() {
		return new String[] { ActionCommand.CIO.name() };
	}

	@Override
	public String getModule() {
		return ActionModule.SIO.name();
	}

	@Override
	public void onPBPacket(final FramePacket pack, final ChainSummaryMessage pb, final CompleteHandler handler) {
		RetChainSummaryMessage.Builder oRetChainSummaryMessage = RetChainSummaryMessage.newBuilder();
		if (mcs == null || mcs.getChainConfig() == null || !mcs.getChainConfig().isNodeStart()) {
			handler.onFinished(PacketHelper.toPBReturn(pack, oRetChainSummaryMessage.build()));
			return;
		}

		BlockInfo lastBlock = mcs.getChainHandler().getLastConnectedBlock();
		BlockInfo prevBlock = mcs.getChainHandler().getBlockByHash(lastBlock.getHeader().getParentHash().toByteArray());

		SimpleDateFormat simpleDateFormat = new SimpleDateFormat("HH:mm:ss");

		ChainSummaryBlock.Builder lastSummary = ChainSummaryBlock.newBuilder();
		lastSummary.setHash(crypto.bytesToHexStr(lastBlock.getHeader().getHash().toByteArray()));
		lastSummary.setHeight(lastBlock.getHeader().getHeight());
		lastSummary.setTxSize(lastBlock.getHeader().getTxHashsCount());
		lastSummary.setTime(simpleDateFormat.format(new Date(lastBlock.getHeader().getTimestamp())));
		lastSummary.setMiner(crypto.bytesToHexStr(lastBlock.getMiner().getAddress().toByteArray()));

		ChainSummaryBlock.Builder prevSummary = ChainSummaryBlock.newBuilder();
		prevSummary.setHash(crypto.bytesToHexStr(prevBlock.getHeader().getHash().toByteArray()));
		prevSummary.setHeight(prevBlock.getHeader().getHeight());
		prevSummary.setTxSize(prevBlock.getHeader().getTxHashsCount());
		prevSummary.setTime(simpleDateFormat.format(new Date(prevBlock.getHeader().getTimestamp())));
		prevSummary.setMiner(crypto.bytesToHexStr(prevBlock.getMiner().getAddress().toByteArray()));

		DecimalFormat df = new DecimalFormat("0.00");
		oRetChainSummaryMessage.setLast(lastSummary).setPrev(prevSummary)
				.setBlockInterval(
						new Long((lastBlock.getHeader().getTimestamp() - prevBlock.getHeader().getTimestamp()) / 1000)
								.intValue())
				.setProcessTps(new Double(df.format(StatRunner.txProcessTps)))
				.setMaxProcessTps(new Double(df.format(StatRunner.maxProcessTps)))
				.setReceiveTps(new Double(df.format(StatRunner.txReceiveTps)))
				.setMaxReceiveTps(new Double(df.format(StatRunner.maxReceiveTps)))
				.setTotalCreateTx(StatRunner.createTxCounter.get())
				.setTotalReceiveTx(StatRunner.receiveTxCounter.get() + StatRunner.createTxCounter.get())
				.setTotalSyncTx(StatRunner.receiveTxCounter.get())
				.setTxConfirmQueueSize(mcs.getTransactionHandler().getTmConfirmQueue().size())
				.setTxMessageQueueSize(mcs.getTransactionHandler().getTmMessageQueue().size())
				.setTotalProcessTx(mcs.getChainConfig().getConfigAccount().getBalance().longValue())
				.setCoinBase(crypto.bytesToHexStr(mcs.getChainConfig().getMiner_account_address()));

		handler.onFinished(PacketHelper.toPBReturn(pack, oRetChainSummaryMessage.build()));
	}
}
