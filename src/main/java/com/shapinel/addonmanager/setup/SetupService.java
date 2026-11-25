package com.shapinel.addonmanager.setup;

import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.domain.entity.AccountEntity;
import com.shapinel.addonmanager.common.domain.entity.AddonSetupEntity;
import com.shapinel.addonmanager.common.domain.entity.AddonSetupTargetEntity;
import com.shapinel.addonmanager.common.domain.entity.LibraryAddonEntity;
import com.shapinel.addonmanager.common.domain.repository.AccountRepository;
import com.shapinel.addonmanager.common.domain.repository.AddonSetupRepository;
import com.shapinel.addonmanager.common.domain.repository.AddonSetupTargetRepository;
import com.shapinel.addonmanager.common.domain.repository.LibraryAddonRepository;
import com.shapinel.addonmanager.common.exception.BusinessException;
import com.shapinel.addonmanager.setup.domain.param.SetupParams.SetupSaveParam;
import com.shapinel.addonmanager.setup.domain.response.SetupResponses.PersonResponse;
import com.shapinel.addonmanager.setup.domain.response.SetupResponses.SetupListResponse;
import com.shapinel.addonmanager.setup.domain.response.SetupResponses.SetupResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 세팅(애드온 묶음) 공유. 만든 사람이 지정한 사람에게만 보이고, 받은 사람은 보고 설치할 수 있다.
 *
 * <p>보안: 만든 사람·로그인한 사람은 토큰에서만 받는다. 고치기·지우기는 만든 사람만 가능하고, 남의 세팅은
 * 존재 여부를 알리지 않으려고 NOT_FOUND 로 답한다. 세팅에는 자료실에 실제 있는 애드온만 담고(이름은 자료실 표기로 맞춤),
 * 공유 대상은 승인된 계정만 허용한다. 설치 자체는 항상 자료실의 검사된 zip 으로만 한다.
 */
@Service
@Transactional(rollbackFor = Exception.class)
@RequiredArgsConstructor
public class SetupService {

    static final int MAX_SETUPS_PER_OWNER = 30;
    private static final int MAX_ADDONS_TEXT = 8000;

    private final AddonSetupRepository setupRepository;
    private final AddonSetupTargetRepository targetRepository;
    private final AccountRepository accountRepository;
    private final LibraryAddonRepository libraryRepository;

    @Transactional(readOnly = true)
    public SetupListResponse list(Long accountId) {
        List<AddonSetupEntity> mine = setupRepository.findByOwnerIdOrderByIdDesc(accountId);
        List<AddonSetupTargetEntity> myTargets = targetRepository.findByAccountId(accountId);
        List<Long> receivedIds = myTargets.stream().map(AddonSetupTargetEntity::getSetupId).toList();
        // 내가 받은 편지 중 연 것
        Set<Long> readIds = myTargets.stream().filter(t -> t.getReadAt() != null).map(AddonSetupTargetEntity::getSetupId).collect(Collectors.toSet());
        List<AddonSetupEntity> received = receivedIds.isEmpty() ? List.of() : setupRepository.findByIdInOrderByIdDesc(receivedIds);

        Set<String> libraryNames = libraryRepository.findByDeletedAtIsNullOrderByNameAsc().stream()
                .map(l -> l.getName().toLowerCase()).collect(Collectors.toSet());
        Map<Long, List<AddonSetupTargetEntity>> targetsBySetup = mine.isEmpty() ? Map.of()
                : targetRepository.findBySetupIdIn(mine.stream().map(AddonSetupEntity::getId).toList()).stream()
                .collect(Collectors.groupingBy(AddonSetupTargetEntity::getSetupId));

        Set<Long> accountIds = new HashSet<>();
        accountIds.add(accountId);
        received.forEach(s -> accountIds.add(s.getOwnerId()));
        targetsBySetup.values().forEach(l -> l.forEach(t -> accountIds.add(t.getAccountId())));
        Map<Long, AccountEntity> accounts = accountRepository.findAllById(accountIds).stream()
                .collect(Collectors.toMap(AccountEntity::getId, Function.identity()));

        List<SetupResponse> mineOut = mine.stream().map(s -> toResponse(s, true, true, libraryNames, accounts,
                targetsBySetup.getOrDefault(s.getId(), List.of()))).toList();
        // 받은 사람에게는 다른 대상자 목록을 내려주지 않는다
        List<SetupResponse> receivedOut = received.stream().map(s -> toResponse(s, false, readIds.contains(s.getId()), libraryNames, accounts, List.of())).toList();
        return new SetupListResponse(mineOut, receivedOut);
    }

    /** 공유 대상으로 고를 수 있는 사람(승인된 다른 계정) */
    @Transactional(readOnly = true)
    public List<PersonResponse> people(Long accountId) {
        return accountRepository.findByStatusAndDeletedAtIsNullOrderByNameAsc(AccountEntity.Status.ACTIVE).stream()
                .filter(a -> !a.getId().equals(accountId))
                .map(a -> new PersonResponse(a.getLoginId(), a.getName())).toList();
    }

    public SetupResponse create(Long accountId, SetupSaveParam param) {
        if (setupRepository.countByOwnerId(accountId) >= MAX_SETUPS_PER_OWNER) {
            throw bad("세팅은 " + MAX_SETUPS_PER_OWNER + "개까지 만들 수 있습니다. 안 쓰는 것을 지워 주세요.");
        }
        Clean clean = clean(accountId, param);
        AddonSetupEntity saved = setupRepository.save(AddonSetupEntity.builder()
                .publicId(UUID.randomUUID().toString()).ownerId(accountId)
                .name(clean.name).description(clean.description).addons(clean.addons).build());
        saveTargets(saved.getId(), clean.targetIds);
        return one(accountId, saved.getPublicId());
    }

    public SetupResponse update(Long accountId, String publicId, SetupSaveParam param) {
        AddonSetupEntity setup = ownSetup(accountId, publicId);
        Clean clean = clean(accountId, param);
        setup.update(clean.name, clean.description, clean.addons);
        targetRepository.deleteBySetupId(setup.getId());
        targetRepository.flush();
        saveTargets(setup.getId(), clean.targetIds);
        return one(accountId, publicId);
    }

    /** 받은 편지를 열어 읽음으로 표시한다. 내게 온 편지가 아니면 존재를 알리지 않고 NOT_FOUND. */
    public void markRead(Long accountId, String publicId) {
        AddonSetupEntity setup = setupRepository.findByPublicId(publicId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        targetRepository.findBySetupIdAndAccountId(setup.getId(), accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)).markRead();
    }

    public void delete(Long accountId, String publicId) {
        AddonSetupEntity setup = ownSetup(accountId, publicId);
        targetRepository.deleteBySetupId(setup.getId());
        setupRepository.delete(setup);
    }

    private SetupResponse one(Long accountId, String publicId) {
        SetupListResponse all = list(accountId);
        return all.getMine().stream().filter(s -> s.getId().equals(publicId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private AddonSetupEntity ownSetup(Long accountId, String publicId) {
        AddonSetupEntity setup = setupRepository.findByPublicId(publicId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!setup.getOwnerId().equals(accountId)) throw new BusinessException(ErrorCode.NOT_FOUND);
        return setup;
    }

    private void saveTargets(Long setupId, Collection<Long> targetIds) {
        for (Long id : targetIds) targetRepository.save(AddonSetupTargetEntity.builder().setupId(setupId).accountId(id).build());
    }

    private record Clean(String name, String description, String addons, Set<Long> targetIds) {
    }

    private Clean clean(Long accountId, SetupSaveParam p) {
        String name = p.getName().trim();
        if (name.isEmpty() || hasControl(name, false)) throw bad("이름이 올바르지 않습니다.");
        String description = p.getDescription() == null || p.getDescription().isBlank() ? null : p.getDescription().trim();
        if (description != null && hasControl(description, true)) throw bad("설명에 쓸 수 없는 문자가 있습니다.");

        // 자료실에 있는 애드온만, 자료실 표기로 맞추고 중복은 한 번만
        Set<String> addons = new LinkedHashSet<>();
        for (String raw : p.getAddons()) {
            LibraryAddonEntity found = libraryRepository.findByNameIgnoreCaseAndDeletedAtIsNull(raw.trim())
                    .orElseThrow(() -> bad("자료실에 없는 애드온입니다: " + shorten(raw)));
            addons.add(found.getName());
        }
        String joined = String.join(",", addons);
        if (joined.length() > MAX_ADDONS_TEXT) throw bad("애드온이 너무 많습니다.");

        Set<Long> targetIds = new LinkedHashSet<>();
        if (p.getTargets() != null && !p.getTargets().isEmpty()) {
            Set<String> wanted = p.getTargets().stream().map(String::trim).collect(Collectors.toCollection(LinkedHashSet::new));
            List<AccountEntity> found = accountRepository.findByLoginIdInAndStatusAndDeletedAtIsNull(wanted, AccountEntity.Status.ACTIVE);
            Set<String> foundIds = found.stream().map(AccountEntity::getLoginId).collect(Collectors.toSet());
            for (String w : wanted) if (!foundIds.contains(w)) throw bad("공유할 수 없는 사용자입니다: " + shorten(w));
            // 자기 자신은 대상이 아니다(내 세팅은 항상 내가 본다)
            found.stream().map(AccountEntity::getId).filter(id -> !id.equals(accountId)).forEach(targetIds::add);
        }
        return new Clean(name, description, joined, targetIds);
    }

    private static SetupResponse toResponse(AddonSetupEntity s, boolean mine, boolean read, Set<String> libraryNames,
                                            Map<Long, AccountEntity> accounts, List<AddonSetupTargetEntity> targets) {
        List<String> all = Arrays.stream(s.getAddons().split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
        List<String> present = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String n : all) (libraryNames.contains(n.toLowerCase()) ? present : missing).add(n);
        AccountEntity owner = accounts.get(s.getOwnerId());
        List<PersonResponse> targetOut = new ArrayList<>();
        for (AddonSetupTargetEntity t : targets) {
            AccountEntity a = accounts.get(t.getAccountId());
            if (a != null) targetOut.add(new PersonResponse(a.getLoginId(), a.getName()));
        }
        return new SetupResponse(s.getPublicId(), s.getName(), s.getDescription(), present, missing,
                owner == null ? "" : owner.getLoginId(), owner == null ? "" : owner.getName(), mine, targetOut, s.getUpdatedAt(),
                s.getCreatedAt(), read);
    }

    private static boolean hasControl(String text, boolean allowNewline) {
        return text.chars().anyMatch(c -> Character.isISOControl(c) && !(allowNewline && (c == '\n' || c == '\r')));
    }

    private static String shorten(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
