package com.shapinel.addonmanager.admin.account;

import com.shapinel.addonmanager.admin.account.domain.response.AdminAccountResponses.AccountResponse;
import com.shapinel.addonmanager.common.domain.entity.AccountEntity.Status;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@SecurityRequirement(name = "access-token")
@PreAuthorize("hasAuthority('ADMIN')")
@RestController
@RequiredArgsConstructor
@Tag(name = "[관리자][계정 관리]")
@RequestMapping("/api/v1/admin/accounts")
public class AdminAccountController {

    private final AdminAccountService adminAccountService;

    @Operation(summary = "계정 목록 조회", description = "상태(PENDING/ACTIVE/REJECTED)로 필터링, 미지정 시 전체")
    @GetMapping
    public ResponseEntity<List<AccountResponse>> getAccounts(@RequestParam(required = false) Status status) {
        return ResponseEntity.ok(adminAccountService.getAccounts(status));
    }

    @Operation(summary = "가입 승인", description = "대기 중인 계정을 활성화")
    @PutMapping("/{accountId}/approve")
    public ResponseEntity<Void> approve(@PathVariable Long accountId) {
        adminAccountService.approve(accountId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "가입 거절", description = "계정을 거절 상태로 변경")
    @PutMapping("/{accountId}/reject")
    public ResponseEntity<Void> reject(@PathVariable Long accountId) {
        adminAccountService.reject(accountId);
        return ResponseEntity.ok().build();
    }
}
