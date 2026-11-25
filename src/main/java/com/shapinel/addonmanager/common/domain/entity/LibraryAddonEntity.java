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

/** 애드온 자료실의 항목 하나(애드온 폴더 하나). 파일은 서버 폴더에 <publicId>.zip 으로 둔다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@Entity
@Table(name = "library_addon")
public class LibraryAddonEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부에 노출하는 식별자(UUID). 순차 PK 는 노출하지 않는다. */
    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    /** 애드온 폴더 이름. 대소문자 구분 없이 유일하다. */
    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "version")
    private String version;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /** .toc 가 요구하는 같이 설치할 폴더들(쉼표 구분). 다시 올릴 때마다 새로 읽는다. */
    @Column(name = "requires")
    private String requires;

    /**
     * 관리자가 따로 지정한 같이 설치할 폴더들(쉼표 구분). 실제 애드온은 .toc 에 선언하지 않은 의존성이 있어서
     * (예: Questie 는 QuestieDB 를 .toc 에 적지 않는다) 관리자가 보충한다. 다시 올려도 유지한다.
     */
    @Column(name = "extra_requires")
    private String extraRequires;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "note")
    private String note;

    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    public void changeExtraRequires(String extraRequires) {
        this.extraRequires = extraRequires;
    }

    /** 같은 폴더를 다시 올리면 파일과 정보를 새 것으로 바꾼다(publicId·관리자 지정 의존성은 유지). */
    public void replace(String version, String contentHash, String requires, long fileSize, String note, Long uploadedBy) {
        this.version = version;
        this.contentHash = contentHash;
        this.requires = requires;
        this.fileSize = fileSize;
        this.note = note;
        this.uploadedBy = uploadedBy;
    }
}
