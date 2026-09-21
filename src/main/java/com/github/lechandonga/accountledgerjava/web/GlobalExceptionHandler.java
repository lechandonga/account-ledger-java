package com.github.lechandonga.accountledgerjava.web;

import com.github.lechandonga.accountledgerjava.exception.AccountAlreadyExistsException;
import com.github.lechandonga.accountledgerjava.exception.AccountNotFoundException;
import com.github.lechandonga.accountledgerjava.exception.IdempotentRequestConflictException;
import com.github.lechandonga.accountledgerjava.exception.InsufficientFundsException;
import com.github.lechandonga.accountledgerjava.exception.InvalidRequestException;
import com.github.lechandonga.accountledgerjava.exception.LedgerException;
import com.github.lechandonga.accountledgerjava.exception.OptimisticLockConflictException;
import com.github.lechandonga.accountledgerjava.exception.PersistenceFailureException;
import com.github.lechandonga.accountledgerjava.exception.SchemaMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;

/**
 * 统一异常映射：LedgerException 体系 -> 可区分的 HTTP 结果；
 * 其余未预期异常一律 500 且不回显底层信息（底层细节仅写日志）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(AccountNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler({AccountAlreadyExistsException.class,
            IdempotentRequestConflictException.class,
            OptimisticLockConflictException.class})
    public ResponseEntity<ApiError> handleConflict(LedgerException e) {
        // 乐观锁冲突与幂等键误用都属于“与当前资源状态冲突”，但 errorCode 不同，可据此区分
        log.info("请求冲突 code={} message={}", e.getErrorCode(), e.getMessage());
        return build(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ApiError> handleInsufficientFunds(InsufficientFundsException e) {
        log.info("业务拒绝 code={} message={}", e.getErrorCode(), e.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, e);
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiError> handleInvalid(InvalidRequestException e) {
        return build(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        ApiError body = new ApiError("INVALID_REQUEST", "请求体格式非法或缺失", OffsetDateTime.now());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(PersistenceFailureException.class)
    public ResponseEntity<ApiError> handlePersistence(PersistenceFailureException e) {
        // 底层异常仅在日志中保留 cause，响应中不暴露
        log.error("持久化失败 code={}", e.getErrorCode(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, e);
    }

    @ExceptionHandler(SchemaMismatchException.class)
    public ResponseEntity<ApiError> handleSchema(SchemaMismatchException e) {
        log.error("数据库结构与预期不一致 code={}", e.getErrorCode(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, e);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        // 兜底：任何未归类异常都不得把堆栈/SQL 等底层信息透出到接口层
        log.error("未预期异常，按内部错误处理", e);
        ApiError body = new ApiError("INTERNAL_ERROR", "系统内部错误", OffsetDateTime.now());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, LedgerException e) {
        return ResponseEntity.status(status)
                .body(new ApiError(e.getErrorCode(), e.getMessage(), OffsetDateTime.now()));
    }
}
