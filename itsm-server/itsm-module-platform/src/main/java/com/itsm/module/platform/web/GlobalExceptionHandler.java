package com.itsm.module.platform.web;

import com.itsm.common.api.ErrorCode;
import com.itsm.common.api.Result;
import com.itsm.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理:统一转换为 Result 结构,避免异常转发到 /error
 * 造成结构丢失或被安全过滤器二次包装。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常:按错误码返回,保留具体 message */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        return Result.fail(e.getErrorCode(), e.getMessage());
    }

    /** 参数校验失败:返回首个字段错误 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .orElse(ErrorCode.BAD_REQUEST.getMessage());
        return Result.fail(ErrorCode.BAD_REQUEST, message);
    }

    @ExceptionHandler({org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.multipart.MaxUploadSizeExceededException.class})
    public Result<Void> handleRequest(Exception e) {
        return Result.fail(ErrorCode.BAD_REQUEST, "请求参数、幂等键、编号或附件大小不符合要求");
    }

    /** 兜底:未知异常不向前端泄漏堆栈 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("未处理异常", e);
        return Result.fail(ErrorCode.INTERNAL_ERROR);
    }
}

