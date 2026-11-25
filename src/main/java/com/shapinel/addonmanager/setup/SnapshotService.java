package com.shapinel.addonmanager.setup;

import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.domain.entity.AddonSnapshotEntity;
import com.shapinel.addonmanager.common.domain.entity.GameEnvironmentEntity;
import com.shapinel.addonmanager.common.domain.entity.LibraryAddonEntity;
import com.shapinel.addonmanager.common.domain.repository.AddonSnapshotRepository;
import com.shapinel.addonmanager.common.domain.repository.GameEnvironmentRepository;
import com.shapinel.addonmanager.common.domain.repository.LibraryAddonRepository;
import com.shapinel.addonmanager.common.exception.BusinessException;
import com.shapinel.addonmanager.setup.domain.response.SnapshotResponses.SnapshotResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 보관함: 내 애드온 목록의 기록. 와우를 새로 깔거나 폴더를 다 지워도 예전 목록으로 그대로 다시 설치할 수 있게 한다.
 *
 * <ul>
 *   <li>자동 기록: 앱이 폴더를 읽고 알려 준 목록이 직전 기록과 달라졌을 때만 남긴다. 빈 목록은 남기지 않는다
 *       (실수로 다 지운 직후의 "아무것도 없음"이 좋은 기록을 밀어내지 않게). 계정당 최근 10개만 유지한다.</li>
 *   <li>직접 보관: 이름을 붙여 남기며 지울 때까지 유지한다(계정당 20개).</li>
 * </ul>
 *
 * <p>보안: 계정은 토큰에서만 받고, 환경은 항상 그 계정 소유인지 함께 조회한다. 기록에는 자료실에 실제 있는 애드온 이름만
 * (자료실 표기로 맞춰) 담는다. 남의 기록은 존재를 알리지 않고 NOT_FOUND 로 답한다.
 */
@Service
@Transactional(rollbackFor = Exception.class)
@RequiredArgsConstructor
public class SnapshotService {

    static final int MAX_AUTO = 10;
    static final int MAX_MANUAL = 20;
    private static final int MAX_ADDONS_TEXT = 8000;

    private final AddonSnapshotRepository snapshotRepository;
    private final GameEnvironmentRepository environmentRepository;
    private final LibraryAddonRepository libraryRepository;

    /** @return 새 기록을 남겼으면 true(직전과 같은 목록이거나 비어 있으면 false) */
    public boolean saveAuto(Long accountId, Long environmentId, List<String> names) {
        GameEnvironmentEntity env = ownEnvironment(accountId, environmentId);
        String addons = canonical(names);
        if (addons.isEmpty()) return false;

        boolean same = snapshotRepository.findFirstByAccountIdAndSourceOrderByIdDesc(accountId, AddonSnapshotEntity.AUTO)
                .filter(last -> java.util.Objects.equals(last.getGameVersion(), env.getGameVersion()))
                .map(last -> last.getAddons().equals(addons)).orElse(false);
        if (same) return false;

        snapshotRepository.save(AddonSnapshotEntity.builder()
                .publicId(UUID.randomUUID().toString()).accountId(accountId).gameVersion(env.getGameVersion())
                .source(AddonSnapshotEntity.AUTO).addons(addons).build());
        // 오래된 자동 기록부터 지워 최근 MAX_AUTO 개만 남긴다
        List<AddonSnapshotEntity> autos = snapshotRepository.findByAccountIdAndSourceOrderByIdAsc(accountId, AddonSnapshotEntity.AUTO);
        for (int i = 0; i < autos.size() - MAX_AUTO; i++) snapshotRepository.delete(autos.get(i));
        return true;
    }

    public SnapshotResponse saveManual(Long accountId, Long environmentId, String label, List<String> names) {
        GameEnvironmentEntity env = ownEnvironment(accountId, environmentId);
        String cleanLabel = label == null ? "" : label.trim();
        if (cleanLabel.isEmpty() || cleanLabel.chars().anyMatch(Character::isISOControl)) throw bad("이름이 올바르지 않습니다.");
        if (snapshotRepository.countByAccountIdAndSource(accountId, AddonSnapshotEntity.MANUAL) >= MAX_MANUAL) {
            throw bad("직접 보관한 기록은 " + MAX_MANUAL + "개까지 둘 수 있습니다. 안 쓰는 것을 지워 주세요.");
        }
        String addons = canonical(names);
        if (addons.isEmpty()) throw bad("자료실에 있는 애드온이 하나도 없어 보관할 수 없습니다.");

        AddonSnapshotEntity saved = snapshotRepository.save(AddonSnapshotEntity.builder()
                .publicId(UUID.randomUUID().toString()).accountId(accountId).gameVersion(env.getGameVersion())
                .source(AddonSnapshotEntity.MANUAL).label(cleanLabel).addons(addons).build());
        return toResponse(saved, libraryNames());
    }

    @Transactional(readOnly = true)
    public List<SnapshotResponse> list(Long accountId) {
        Set<String> names = libraryNames();
        return snapshotRepository.findByAccountIdOrderByIdDesc(accountId).stream().map(s -> toResponse(s, names)).toList();
    }

    public void delete(Long accountId, String publicId) {
        AddonSnapshotEntity s = snapshotRepository.findByPublicId(publicId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!s.getAccountId().equals(accountId)) throw new BusinessException(ErrorCode.NOT_FOUND);
        snapshotRepository.delete(s);
    }

    private GameEnvironmentEntity ownEnvironment(Long accountId, Long environmentId) {
        return environmentRepository.findByIdAndAccountIdAndDeletedAtIsNull(environmentId, accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private Set<String> libraryNames() {
        return libraryRepository.findByDeletedAtIsNullOrderByNameAsc().stream()
                .map(l -> l.getName().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    /** 자료실에 있는 이름만, 자료실 표기로 맞추고 중복을 없앤 뒤 이름순 쉼표 문자열로 만든다. 없으면 빈 문자열. */
    private String canonical(List<String> names) {
        Map<String, LibraryAddonEntity> byName = libraryRepository.findByDeletedAtIsNullOrderByNameAsc().stream()
                .collect(Collectors.toMap(l -> l.getName().toLowerCase(Locale.ROOT), Function.identity(), (a, b) -> a));
        Map<String, String> sorted = new TreeMap<>();
        for (String raw : names) {
            LibraryAddonEntity found = byName.get(raw.trim().toLowerCase(Locale.ROOT));
            if (found != null) sorted.put(found.getName().toLowerCase(Locale.ROOT), found.getName());
        }
        String joined = String.join(",", sorted.values());
        if (joined.length() > MAX_ADDONS_TEXT) throw bad("애드온이 너무 많습니다.");
        return joined;
    }

    private static SnapshotResponse toResponse(AddonSnapshotEntity s, Set<String> libraryNames) {
        List<String> present = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String n : Arrays.stream(s.getAddons().split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList()) {
            (libraryNames.contains(n.toLowerCase(Locale.ROOT)) ? present : missing).add(n);
        }
        return new SnapshotResponse(s.getPublicId(), s.getSource(), s.getLabel(), s.getGameVersion(), present, missing, s.getCreatedAt());
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
