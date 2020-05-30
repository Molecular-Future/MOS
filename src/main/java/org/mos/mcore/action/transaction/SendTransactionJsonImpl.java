package org.mos.mcore.action.transaction;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import onight.oapi.scala.commons.SessionModules;
import onight.osgi.annotation.NActorProvider;
import onight.tfw.async.CompleteHandler;
import onight.tfw.ntrans.api.annotation.ActorRequire;
import onight.tfw.otransio.api.PacketHelper;
import onight.tfw.otransio.api.beans.FramePacket;
import org.mos.mcore.api.ICryptoHandler;
import org.mos.mcore.bean.TransactionMessage;
import org.mos.mcore.handler.ChainHandler;
import org.mos.mcore.handler.TransactionHandler;
import org.mos.mcore.model.Action.ActionCommand;
import org.mos.mcore.model.Action.ActionModule;
import org.mos.mcore.model.Action.RetSendTransactionMessage;
import org.mos.mcore.model.Action.TransactionInfoImpl;

@NActorProvider
@Slf4j
@Data
public class SendTransactionJsonImpl extends SessionModules<TransactionInfoImpl> {
	@ActorRequire(name = "bc_chain", scope = "global")
	ChainHandler blockChainHelper;
	@ActorRequire(name = "bc_transaction", scope = "global")
	TransactionHandler transactionHandler;
	@ActorRequire(name = "bc_crypto", scope = "global")
	ICryptoHandler crypto;

	@Override
	public String[] getCmds() {
		return new String[] { ActionCommand.MTXJ.name() };
	}

	@Override
	public String getModule() {
		return ActionModule.TCT.name();
	}

	@Override
	public void onPBPacket(final FramePacket pack, final TransactionInfoImpl pb, final CompleteHandler handler) {
		RetSendTransactionMessage.Builder oRetSendTransactionMessage = RetSendTransactionMessage.newBuilder();
		try {
			TransactionMessage tm = transactionHandler.createTransaction(pb.getTransaction().toBuilder());
			oRetSendTransactionMessage.setRetCode(1);
			oRetSendTransactionMessage.setHash(crypto.bytesToHexStr(tm.getKey()));
		} catch (Exception e) {
			log.error("", e);
			oRetSendTransactionMessage.clear();
			oRetSendTransactionMessage.setRetCode(-1);
			oRetSendTransactionMessage.setRetMsg(e.getMessage());
			handler.onFinished(PacketHelper.toPBReturn(pack, oRetSendTransactionMessage.build()));
			return;
		}
		handler.onFinished(PacketHelper.toPBReturn(pack, oRetSendTransactionMessage.build()));
	}
}
