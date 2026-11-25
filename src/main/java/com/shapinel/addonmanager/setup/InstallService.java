package com.shapinel.addonmanager.setup;

import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.domain.entity.AccountAddonEntity;
import com.shapinel.addonmanager.common.domain.entity.AddonEventEntity;
import com.shapinel.addonmanager.common.domain.entity.AddonEventEntity.Action;
import com.shapinel.addonmanager.common.domain.entity.LibraryAddonEntity;
import com.shapinel.addonmanager.common.domain.repository.AccountAddonRepository;
import com.shapinel.addonmanager.common.domain.repository.AddonEventRepository;
import com.shapinel.addonmanager.common.domain.repository.GameEnvironmentRepository;
import com.shapinel.addonmanager.common.domain.repository.LibraryAddonRepository;
import com.shapinel.addonmanager.common.exception.BusinessException;
import com.shapinel.addonmanager.library.AddonZipInspector;
import com.shapinel.addonmanager.setup.domain.param.SetupParams.InstallEventParam;
import com.shapinel.addonmanager.setup.domain.response.SetupResponses.EventResponse;
import com.shapinel.addonmanager.setup.domain.response.SetupResponses.InstalledResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 계정별 설치 현황·이력. 앱이 설치·업데이트·삭제한 뒤 알려 주는 값을 쌓는다.
 *
 * <p>보안: 계정은 요청값이 아니라 토큰에서 받고, 환경은 항상 그 계정 소유인지 함께 조회한다(남의 환경 기록 차단).
 * 이 기록은 화면 안내용일 뿐 설치 판단의 기준이 아니다(실제 판단은 사용자 PC 폴더를 읽어서 한다).
 */
@Service
@Transactional(rollbackFor = Exception.class)
@RequiredArgsConstructor
public class InstallService {

    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
    private static final int HISTORY_LIMIT = 100;

    private final GameEnvironmentRepository environmentRepository;
    private final AccountAddonRepository accountAddonRepository;
    private final AddonEventRepository eventRepository;
    private final LibraryAddonRepository libraryRepository;

    public void record(Long accountId, Long environmentId, List<InstallEventParam> events) {
        requireOwnEnvironment(accountId, environmentId);
        for (InstallEventParam e : events) {
            String name = e.getAddonName().trim();
            if (!AddonZipInspector.isSafeFolderName(name)) throw bad("올바른 애드온 이름이 아닙니다.");
            if (e.getAction() == Action.REMOVED) {
                accountAddonRepository.findByEnvironmentIdAndAddonName(environmentId, name).ifPresent(accountAddonRepository::delete);
                log(accountId, environmentId, name, Action.REMOVED, null);
                continue;
            }
            // 설치·업데이트는 자료실에 있는 애드온만 기록한다(이름은 자료실의 표기로 맞춘다)
            LibraryAddonEntity library = libraryRepository.findByNameIgnoreCaseAndDeletedAtIsNull(name)
                    .orElseThrow(() -> bad("자료실에 없는 애드온입니다: " + name));
            String hash = e.getContentHash() == null ? "" : e.getContentHash().toLowerCase();
            if (!HASH.matcher(hash).matches()) throw bad("내용 해시가 올바르지 않습니다.");
            var existing = accountAddonRepository.findByEnvironmentIdAndAddonName(environmentId, library.getName());
            if (existing.isPresent()) {
                existing.get().changeHash(hash);
            } else {
                accountAddonRepository.save(AccountAddonEntity.builder()
                        .accountId(accountId).environmentId(environmentId).addonName(library.getName()).contentHash(hash).build());
            }
            log(accountId, environmentId, library.getName(), existing.isPresent() ? Action.UPDATED : Action.INSTALLED, hash);
        }
    }

    @Transactional(readOnly = true)
    public List<InstalledResponse> installed(Long accountId, Long environmentId) {
        requireOwnEnvironment(accountId, environmentId);
        return accountAddonRepository.findByEnvironmentIdOrderByAddonNameAsc(environmentId).stream()
                .map(a -> new InstalledResponse(a.getAddonName(), a.getContentHash(), a.getUpdatedAt())).toList();
    }

    @Transactional(readOnly = true)
    public List<EventResponse> history(Long accountId) {
        return eventRepository.findByAccountIdOrderByIdDesc(accountId, PageRequest.of(0, HISTORY_LIMIT)).stream()
                .map(e -> new EventResponse(e.getAddonName(), e.getAction().name(), e.getCreatedAt())).toList();
    }

    private void log(Long accountId, Long environmentId, String name, Action action, String hash) {
        eventRepository.save(AddonEventEntity.builder()
                .accountId(accountId).environmentId(environmentId).addonName(name).action(action).contentHash(hash).build());
    }

    private void requireOwnEnvironment(Long accountId, Long environmentId) {
        environmentRepository.findByIdAndAccountIdAndDeletedAtIsNull(environmentId, accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
