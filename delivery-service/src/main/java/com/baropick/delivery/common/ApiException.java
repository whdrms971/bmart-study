package com.baropick.delivery.common;

import org.springframework.http.HttpStatus;

/** 계약에 정의된 오류 코드를 가진 예외. 응답: {"code": "...", "message": "..."} */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static ApiException notFound(String code, String msg) { return new ApiException(HttpStatus.NOT_FOUND, code, msg); }
    public static ApiException conflict(String code, String msg) { return new ApiException(HttpStatus.CONFLICT, code, msg); }
    public static ApiException forbidden(String code, String msg) { return new ApiException(HttpStatus.FORBIDDEN, code, msg); }
    public static ApiException badRequest(String code, String msg) { return new ApiException(HttpStatus.BAD_REQUEST, code, msg); }
    public static ApiException unavailable(String code, String msg) { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, msg); }
}
