package com.shapinel.addonmanager.common.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 공통 컬럼. 값은 DB DEFAULT / ON UPDATE 가 채우므로 JPA 에서는 쓰기 대상에서 제외한다.
 */
@Getter
@MappedSuperclass
public abstract class BaseEntity {

    @Column(name = "created_at", insertable = false, updatable = false)
    protected LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    protected LocalDateTime updatedAt;

    @Column(name = "deleted_at", insertable = false, updatable = false)
    protected LocalDateTime deletedAt;
}
