package org.mos.mcore.exception;

import onight.tfw.ojpa.api.exception.NotSuportException;

public class DposNodeNotReadyException extends NotSuportException {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	public DposNodeNotReadyException(String message) {
		super(message);
	}

}
