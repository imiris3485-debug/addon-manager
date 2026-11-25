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

/** 내 애드온 목록의 기록(보관함). 폴더를 다 지워도 이 기록으로 그대로 다시 설치할 수 있다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@Entity
@Table(name = "addon_snapshot")
public class AddonSnapshotEntity extends BaseEntity {

    public static final String AUTO = "AUTO";
    public static final String MANUAL = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부에 노출하는 식별자(UUID). 순차 PK 는 노출하지 않는다. */
    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Column(name = "game_version", updatable = false)
    private String gameVersion;

    @Column(name = "source", nullable = false, updatable = false)
    private String source;

    @Column(name = "label", updatable = false)
    private String label;

    /** 자료실 애드온 폴더 이름(쉼표 구분, 이름순) */
    @Column(name = "addons", nullable = false, updatable = false, length = 8000)
    private String addons;
}
