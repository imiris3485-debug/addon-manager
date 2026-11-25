package com.shapinel.addonmanager.common.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 환경별 현재 설치 현황(애드온당 1행). 앱이 설치·업데이트·삭제할 때 알려 준 값이다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@Entity
@Table(name = "account_addon")
public class AccountAddonEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Column(name = "environment_id", nullable = false, updatable = false)
    private Long environmentId;

    @Column(name = "addon_name", nullable = false, updatable = false)
    private String addonName;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    public void changeHash(String contentHash) {
        this.contentHash = contentHash;
    }
}
