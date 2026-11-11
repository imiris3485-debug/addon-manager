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

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@Entity
@Table(name = "installed_addon")
public class InstalledAddonEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "environment_id", nullable = false, updatable = false)
    private Long environmentId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "installed_file_id")
    private Long installedFileId;

    @Column(name = "toc_version")
    private String tocVersion;

    /** 쉼표로 구분한 폴더명 목록 */
    @Column(name = "folders", nullable = false)
    private String folders;

    public void update(Long installedFileId, String tocVersion, String folders) {
        this.installedFileId = installedFileId;
        this.tocVersion = tocVersion;
        this.folders = folders;
    }
}
