package com.threadfit.glucopilot.common.api;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 统一业务错误码：HTTP 三位语义 + body.code 五位细分。
 * 成功固定 code=0；后续细分只在同族下追加（如 40901）。
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    SUCCESS(HttpStatus.OK, 0, "ok"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, 40000, "参数格式、类型或必填校验失败"),
    BIZ_REJECTED(HttpStatus.BAD_REQUEST, 40001, "业务校验失败或凭证错误"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, 40100, "未登录或 Token 失效"),
    FORBIDDEN(HttpStatus.FORBIDDEN, 40300, "无权限、账号禁用或注销中"),
    MESSAGE_REQUEST_PENDING(HttpStatus.FORBIDDEN, 40301, "等待对方回复或相互关注后可继续发信息"),
    NOT_FOUND(HttpStatus.NOT_FOUND, 40400, "资源不存在或不属于当前用户"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, 40500, "接口不支持此请求方法，请确认后端版本与请求方式"),
    CONFLICT(HttpStatus.CONFLICT, 40900, "冲突（唯一键、乐观锁、账号占用）"),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, 42901, "限流或登录锁定"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, 50000, "系统异常");

    private final HttpStatus httpStatus;
    private final int code;
    private final String defaultMessage;
}
