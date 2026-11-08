package com.janetfilter.dbeaver.export.utils;

/** Status-bearing failure shared by the controller and native operations. */
public final class HttpException extends RuntimeException {
    public final int status;

    public HttpException(int status, String message) {
        super(message);
        this.status = status;
    }
}
