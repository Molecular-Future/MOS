package org.mos.mcore.datasource;

import onight.tfw.ojpa.api.ServiceSpec;
import onight.tfw.outils.conf.PropHelper;
import org.mos.mcore.odb.ODBDao;

public class BlockDao extends ODBDao {

	public BlockDao(ServiceSpec serviceSpec) {
		super(serviceSpec);
	}

	@Override
	public String getDomainName() {
		//return "block.index";
		
		if (new PropHelper(null).get("org.mos.mcore.backend.block.timeslice", 0) == 1) {
			return "block.." + new PropHelper(null).get("org.mos.mcore.backend.block.slice", 256)+".t";
		}
		return "block.." + new PropHelper(null).get("org.mos.mcore.backend.block.slice", 256);
	}
}
