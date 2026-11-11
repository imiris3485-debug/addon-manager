package com.shapinel.addonmanager.common.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 에러 코드. 실패 유형과 HTTP 상태코드가 어긋나지 않도록 상태를 함께 가진다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "데이터를 찾을 수 없습니다."),
    CONFLICT(HttpStatus.CONFLICT, "이미 존재하거나 충돌하는 요청입니다."),
    CREATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "생성에 실패했습니다."),
    UPDATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "수정에 실패했습니다."),
    DELETE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "삭제에 실패했습니다."),
    EXTERNAL_API_ERROR(HttpStatus.BAD_GATEWAY, "외부 서비스(CurseForge) 호출에 실패했습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;
}
