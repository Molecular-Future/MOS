package org.mos.mcore.datasource;

import onight.tfw.ojpa.api.ServiceSpec;
import org.mos.mcore.odb.ODBDao;

public class BlockSecondaryDao extends ODBDao {

	public BlockSecondaryDao(ServiceSpec serviceSpec) {
		super(serviceSpec);
	}

	@Override
	public String getDomainName() {
		return "blocksec.index";
	}
}
