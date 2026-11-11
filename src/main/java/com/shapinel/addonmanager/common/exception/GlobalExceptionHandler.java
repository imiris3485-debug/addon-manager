package com.shapinel.addonmanager.common.exception;

import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.response.ErrorResponse;
import com.shapinel.addonmanager.common.response.ErrorResponse.FieldError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code.name(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code.name(), code.getMessage(), errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.BAD_REQUEST.name(), "JSON 형식과 인코딩(UTF-8)을 확인해 주세요."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.BAD_REQUEST.name(), e.getMessage()));
    }

    /** @PreAuthorize 실패가 아래 Exception 핸들러에 먹혀 500이 되지 않도록 별도 처리한다. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
        ErrorCode code = ErrorCode.FORBIDDEN;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code.name(), code.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        // 상세 원인은 로그로만 남기고, 응답에는 고정 문구만 내려 내부 정보를 숨긴다
        log.error("처리되지 않은 예외", e);
        ErrorCode code = ErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code.name(), code.getMessage()));
    }
}
