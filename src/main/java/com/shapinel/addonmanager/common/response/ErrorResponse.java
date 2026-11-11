package com.shapinel.addonmanager.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 실패 응답 공통 형식. 성공 응답은 래퍼 없이 DTO를 그대로 반환한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, List<FieldError> errors, LocalDateTime timestamp) {

    public record FieldError(String field, String reason) {
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null, LocalDateTime.now());
    }

    public static ErrorResponse of(String code, String message, List<FieldError> errors) {
        return new ErrorResponse(code, message, errors, LocalDateTime.now());
    }
}
