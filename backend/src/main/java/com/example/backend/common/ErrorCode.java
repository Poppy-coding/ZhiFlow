package com.example.backend.common;

import org.springframework.http.HttpStatus;

/**
 * 全局业务错误码。
 *
 * <p>接口返回体中的 {@code code} 用来表达业务错误，{@link HttpStatus} 用来表达 HTTP
 * 传输语义。前端可以统一读取 {@code Result.code / Result.message} 判断业务结果。
 */
public enum ErrorCode {

    /** 请求参数格式不正确、缺少必要参数或参数类型转换失败。 */
    INVALID_ARGUMENT(40000, HttpStatus.BAD_REQUEST),

    /** Bean Validation 校验失败，例如字段为空、长度不符合要求。 */
    VALIDATION_FAILED(40001, HttpStatus.BAD_REQUEST),

    /** 用户未登录、token 缺失、token 无效或登录状态已过期。 */
    UNAUTHORIZED(40100, HttpStatus.UNAUTHORIZED),

    /** 用户已登录，但没有权限访问当前资源或执行当前操作。 */
    FORBIDDEN(40300, HttpStatus.FORBIDDEN),

    /** 请求的资源不存在，例如用户、视频、任务记录不存在。 */
    NOT_FOUND(40400, HttpStatus.NOT_FOUND),

    /** 当前资源状态和请求操作冲突，例如重复提交、任务已在处理中。 */
    CONFLICT(40900, HttpStatus.CONFLICT),

    /** 请求语义正确，但当前业务条件无法处理，例如上下文未就绪。 */
    UNPROCESSABLE(42200, HttpStatus.UNPROCESSABLE_ENTITY),

    /** 请求过于频繁，触发登录失败保护、用户限流或任务限流。 */
    RATE_LIMITED(42900, HttpStatus.TOO_MANY_REQUESTS),

    /** 服务端出现未预期异常。 */
    INTERNAL_ERROR(50000, HttpStatus.INTERNAL_SERVER_ERROR),

    /** 外部依赖或当前服务暂时不可用，例如 Redis、对象存储、模型服务异常。 */
    SERVICE_UNAVAILABLE(50300, HttpStatus.SERVICE_UNAVAILABLE);

    private final int code;
    private final HttpStatus httpStatus;

    ErrorCode(int code, HttpStatus httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public int code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
