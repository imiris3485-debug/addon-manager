package com.shapinel.addonmanager.library;

import com.shapinel.addonmanager.common.domain.entity.LibraryAddonEntity;
import com.shapinel.addonmanager.common.domain.repository.LibraryAddonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 자료실 메타데이터 저장 전용. 파일 입출력(느린 작업)이 DB 트랜잭션에 묶이지 않도록 LibraryService 와 분리했다.
 */
@Component
@Transactional(rollbackFor = Exception.class)
@RequiredArgsConstructor
public class LibraryStore {

    private final LibraryAddonRepository repository;

    @Transactional(readOnly = true)
    public List<LibraryAddonEntity> findAll() {
        return repository.findByDeletedAtIsNullOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Optional<LibraryAddonEntity> findByPublicId(String publicId) {
        return repository.findByPublicIdAndDeletedAtIsNull(publicId);
    }

    @Transactional(readOnly = true)
    public Optional<LibraryAddonEntity> findByName(String name) {
        return repository.findByNameIgnoreCaseAndDeletedAtIsNull(name);
    }

    /** 같은 이름이 있으면 그 행을 새 정보로 바꾸고, 없으면 새로 만든다. */
    public LibraryAddonEntity upsert(String publicId, String name, String version, String contentHash, String requires,
                                     long fileSize, String note, Long uploadedBy) {
        return repository.findByNameIgnoreCaseAndDeletedAtIsNull(name)
                .map(e -> {
                    e.replace(version, contentHash, requires, fileSize, note, uploadedBy);
                    return e;
                })
                .orElseGet(() -> repository.save(LibraryAddonEntity.builder()
                        .publicId(publicId).name(name).version(version).contentHash(contentHash).requires(requires)
                        .fileSize(fileSize).note(note).uploadedBy(uploadedBy).build()));
    }

    /** 관리자가 지정한 같이 설치할 폴더를 바꾼다. 항목이 없으면 비어 있는 값을 돌려준다. */
    public Optional<LibraryAddonEntity> updateExtraRequires(String publicId, String extraRequires) {
        return repository.findByPublicIdAndDeletedAtIsNull(publicId).map(e -> {
            e.changeExtraRequires(extraRequires);
            return e;
        });
    }

    /** 소프트 삭제 컬럼은 DB 가 관리하므로(JPA 가 쓸 수 없음) 물리 삭제한다. */
    public void delete(String publicId) {
        repository.findByPublicIdAndDeletedAtIsNull(publicId).ifPresent(repository::delete);
    }
}
