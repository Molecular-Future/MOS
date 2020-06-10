package org.mos.mcore.datasource;

import onight.tfw.ojpa.api.ServiceSpec;
import onight.tfw.outils.conf.PropHelper;
import org.mos.mcore.odb.ODBDao;

public class BaseDao extends ODBDao {

	public BaseDao(ServiceSpec serviceSpec) {
		super(serviceSpec);
	}

	@Override
	public String getDomainName() {
		return "base.." + new PropHelper(null).get("org.mos.mcore.backend.base.slice", 1);
	}
}