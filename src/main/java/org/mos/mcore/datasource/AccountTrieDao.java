package org.mos.mcore.datasource;

import onight.tfw.ojpa.api.ServiceSpec;
import onight.tfw.outils.conf.PropHelper;
import org.mos.mcore.odb.ODBDao;

public class AccountTrieDao extends ODBDao {

	public AccountTrieDao(ServiceSpec serviceSpec) {
		super(serviceSpec);
	}

	@Override
	public String getDomainName() {
		if (new PropHelper(null).get("org.mos.mcore.backend.account.timeslice", 1) == 1) {
			return "account.." + new PropHelper(null).get("org.mos.mcore.backend.account.slice", 256) + ".t";
		}
		return "account.." + new PropHelper(null).get("org.mos.mcore.backend.account.slice", 256);
	}
}