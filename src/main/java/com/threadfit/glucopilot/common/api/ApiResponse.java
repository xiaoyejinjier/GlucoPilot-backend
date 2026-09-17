package com.threadfit.glucopilot.common.api;

import com.threadfit.glucopilot.common.context.RequestContext;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应体，契约：{ code, message, data, requestId }。
 * 成功：HTTP 200 且 code=0。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;
    private String requestId;

    public static <T> ApiResponse<T> ok(T data) {
        return of(ErrorCode.SUCCESS, ErrorCode.SUCCESS.getDefaultMessage(), data);
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return of(errorCode, errorCode.getDefaultMessage(), null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return of(errorCode, message, null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message, T data) {
        return of(errorCode, message, data);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(code);
        response.setMessage(message);
        response.setData(null);
        response.setRequestId(RequestContext.getRequestId());
        return response;
    }

    private static <T> ApiResponse<T> of(ErrorCode errorCode, String message, T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(errorCode.getCode());
        response.setMessage(message);
        response.setData(data);
        response.setRequestId(RequestContext.getRequestId());
        return response;
    }
}
