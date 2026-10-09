package com.example.redisadmin.exception;

import com.example.redisadmin.model.vo.ApiResponse;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 统一异常映射。全部返回 <b>HTTP 200</b> + envelope（对齐 kafkaVisual5），前端只看 code。
 * <p>日志分级：4 万段业务异常 warn 且不带堆栈；系统异常 error 带全堆栈（设计 §7.2 / §10）。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * WRONGTYPE 前缀：优先映射 40902 类型冲突。
     */
    private static final String WRONGTYPE_PREFIX = "WRONGTYPE";

    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBizException(BizException e) {
        ErrorCode code = e.getErrorCode();
        if (isBusiness(code)) {
            // 业务异常：warn 单行，不打堆栈
            log.warn("业务异常 code={} msg={}", code.getCode(), e.getMessage());
        } else {
            log.warn("业务异常 code={} msg={} cause={}", code.getCode(), e.getMessage(), rootMessage(e));
        }
        return ApiResponse.error(code.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", message);
        return ApiResponse.error(ErrorCode.VALIDATION_ERROR.getCode(), message);
    }

    @ExceptionHandler(BindException.class)
    public ApiResponse<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数绑定失败: {}", message);
        return ApiResponse.error(ErrorCode.VALIDATION_ERROR.getCode(), message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ApiResponse<Void> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(v -> v.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数约束校验失败: {}", message);
        return ApiResponse.error(ErrorCode.VALIDATION_ERROR.getCode(), message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体格式错误: {}", rootMessage(e));
        return ApiResponse.error(ErrorCode.VALIDATION_ERROR.getCode(), "请求体格式错误: " + rootMessage(e));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ApiResponse<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String message = "参数类型错误: " + e.getName() + " 应为 "
                + (e.getRequiredType() == null ? "合法值" : e.getRequiredType().getSimpleName());
        log.warn("{}", message);
        return ApiResponse.error(ErrorCode.VALIDATION_ERROR.getCode(), message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ApiResponse<Void> handleMissingParam(MissingServletRequestParameterException e) {
        String message = "缺少必填参数: " + e.getParameterName();
        log.warn("{}", message);
        return ApiResponse.error(ErrorCode.VALIDATION_ERROR.getCode(), message);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ApiResponse<Void> handleNoHandlerFound(NoHandlerFoundException e) {
        log.warn("端点不存在: {} {}", e.getHttpMethod(), e.getRequestURL());
        return ApiResponse.error(ErrorCode.VALIDATION_ERROR.getCode(),
                "端点不存在: " + e.getHttpMethod() + " " + e.getRequestURL());
    }

    /**
     * Boot 3.2+ 对未匹配路径抛 {@code NoResourceFoundException}（静态资源兜底），
     * 单独映射为 40001 文案，避免落进 50000 的 error 全堆栈。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ApiResponse<Void> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("资源不存在: {}", e.getResourcePath());
        return ApiResponse.error(ErrorCode.VALIDATION_ERROR.getCode(),
                "端点不存在: " + e.getHttpMethod() + " " + e.getResourcePath());
    }

    /**
     * 命令级超时 → 50302。
     */
    @ExceptionHandler(RedisCommandTimeoutException.class)
    public ApiResponse<Void> handleCommandTimeout(RedisCommandTimeoutException e) {
        log.warn("Redis 命令超时: {}", rootMessage(e));
        return ApiResponse.error(ErrorCode.REDIS_UNAVAILABLE.getCode(), "Redis 命令超时: " + rootMessage(e));
    }

    /**
     * {@code WRONGTYPE} → 40902（类型冲突）；其余 {@link RedisException} → 50001。
     * 细分语义一律走 msg 文案，不新增错误码。
     */
    @ExceptionHandler(RedisException.class)
    public ApiResponse<Void> handleRedisException(RedisException e) {
        String message = rootMessage(e);
        if (e instanceof RedisCommandExecutionException && message != null
                && message.toUpperCase().startsWith(WRONGTYPE_PREFIX)) {
            log.warn("Redis 类型冲突: {}", message);
            return ApiResponse.error(ErrorCode.TYPE_MISMATCH.getCode(), message);
        }
        log.warn("Redis 命令执行失败: {}", message);
        return ApiResponse.error(ErrorCode.REDIS_COMMAND_FAILED.getCode(), message);
    }

    /** 兜底：未预期系统异常，error + 全堆栈。 */
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return ApiResponse.error(ErrorCode.SYSTEM_ERROR.getCode(), "系统异常: " + rootMessage(e));
    }

    /**
     * 4 万段（40001/40401/40402/40902）不打堆栈。
     */
    private boolean isBusiness(ErrorCode code) {
        int value = code.getCode();
        return value == ErrorCode.VALIDATION_ERROR.getCode()
                || value == ErrorCode.PROFILE_NOT_FOUND.getCode()
                || value == ErrorCode.KEY_NOT_FOUND.getCode()
                || value == ErrorCode.TYPE_MISMATCH.getCode();
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
