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
import org.mos.mcore.handler.ChainHandler;
import org.mos.mcore.handler.TransactionHandler;
import org.mos.mcore.model.Action.ActionCommand;
import org.mos.mcore.model.Action.ActionModule;
import org.mos.mcore.model.Action.RetTransactionMessage;
import org.mos.mcore.model.Action.TransactionMessage;
import org.mos.mcore.model.Transaction.TransactionInfo;

@NActorProvider
@Slf4j
@Data
public class GetTransactionByHashImpl extends SessionModules<TransactionMessage> {
	@ActorRequire(name = "bc_chain", scope = "global")
	ChainHandler blockChainHelper;
	@ActorRequire(name = "bc_transaction", scope = "global")
	TransactionHandler transactionHandler;
	@ActorRequire(name = "bc_crypto", scope = "global")
	ICryptoHandler crypto;

	@Override
	public String[] getCmds() {
		return new String[] { ActionCommand.GTH.name() };
	}

	@Override
	public String getModule() {
		return ActionModule.TCT.name();
	}

	@Override
	public void onPBPacket(final FramePacket pack, final TransactionMessage pb, final CompleteHandler handler) {
		RetTransactionMessage.Builder oRet = RetTransactionMessage.newBuilder();
		try {

			TransactionInfo oInfo = transactionHandler.getTransaction(crypto.hexStrToBytes(pb.getHash()));

			if (oInfo == null) {
				oRet.setRetCode(-1);
				oRet.setRetMsg("交易不存在");
				handler.onFinished(PacketHelper.toPBReturn(pack, oRet.build()));
				return;
			}

			oRet.setTransaction(oInfo);
			oRet.setRetCode(1);
		} catch (Exception e) {
			log.error("", e);
			oRet.setRetCode(-1);
			oRet.setRetMsg(e.getMessage());
		}
		handler.onFinished(PacketHelper.toPBReturn(pack, oRet.build()));
	}
}
