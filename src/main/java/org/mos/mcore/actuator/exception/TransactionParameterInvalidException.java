package org.mos.mcore.actuator.exception;

/**
 * Email: king.camulos@gmail.com
 * Date: 2018/11/7
 * DESC:
 */
public class TransactionParameterInvalidException extends IllegalArgumentException {
    public TransactionParameterInvalidException(String msg) {
        super(msg);
    }

    /**
     *
     */
    private static final long serialVersionUID = 1L;
}