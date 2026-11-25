package com.shapinel.addonmanager.setup;

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
import com.shapinel.addonmanager.setup.domain.response.SetupResponses.SetupListResponse;
import com.shapinel.addonmanager.setup.domain.response.SetupResponses.SetupResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 세팅 공유 정책
 * - 세팅에는 자료실에 있는 애드온만 담을 수 있고, 이름은 자료실 표기로 맞추며 중복은 한 번만 담는다.
 * - 공유 대상은 승인된 계정만 허용하고 자기 자신은 대상에서 뺀다.
 * - 고치기·지우기는 만든 사람만 가능하며, 남의 세팅은 존재 여부를 알리지 않고 NOT_FOUND 로 답한다.
 * - 받은 사람에게는 다른 공유 대상 목록을 내려주지 않는다.
 * - 세팅은 한 사람당 30개까지.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SetupServiceTest {

    @Mock AddonSetupRepository setupRepository;
    @Mock AddonSetupTargetRepository targetRepository;
    @Mock AccountRepository accountRepository;
    @Mock LibraryAddonRepository libraryRepository;

    SetupService service;
    /** save 로 마지막에 저장된 세팅(저장 뒤 목록 조회가 이걸 돌려준다) */
    AddonSetupEntity lastSaved;

    @BeforeEach
    void setUp() {
        service = new SetupService(setupRepository, targetRepository, accountRepository, libraryRepository);
        when(setupRepository.save(any())).thenAnswer(i -> {
            AddonSetupEntity e = i.getArgument(0);
            ReflectionTestUtils.setField(e, "id", 10L);
            lastSaved = e;
            return e;
        });
        when(setupRepository.findByOwnerIdOrderByIdDesc(1L)).thenAnswer(i -> lastSaved == null ? List.of() : List.of(lastSaved));
        when(accountRepository.findAllById(anyCollection())).thenReturn(List.of(account(1L, "me", "나"), account(2L, "bob", "밥")));
        for (String n : List.of("Questie", "QuestieDB", "Bagnon")) {
            when(libraryRepository.findByNameIgnoreCaseAndDeletedAtIsNull(n))
                    .thenReturn(Optional.of(LibraryAddonEntity.builder().name(n).build()));
            when(libraryRepository.findByNameIgnoreCaseAndDeletedAtIsNull(n.toLowerCase()))
                    .thenReturn(Optional.of(LibraryAddonEntity.builder().name(n).build()));
        }
        when(libraryRepository.findByDeletedAtIsNullOrderByNameAsc()).thenReturn(List.of(
                LibraryAddonEntity.builder().name("Questie").build(), LibraryAddonEntity.builder().name("QuestieDB").build()));
    }

    private static AccountEntity account(long id, String loginId, String name) {
        return AccountEntity.builder().id(id).loginId(loginId).name(name).status(AccountEntity.Status.ACTIVE).build();
    }

    private static SetupSaveParam param(String name, List<String> addons, List<String> targets) {
        SetupSaveParam p = new SetupSaveParam();
        ReflectionTestUtils.setField(p, "name", name);
        ReflectionTestUtils.setField(p, "addons", addons);
        ReflectionTestUtils.setField(p, "targets", targets);
        return p;
    }

    @Test
    void 자료실_표기로_맞추고_중복은_한_번만_담는다() {
        service.create(1L, param("기본", List.of("questie", "Questie", "questiedb"), null));

        assertThat(lastSaved.getAddons()).isEqualTo("Questie,QuestieDB");
    }

    @Test
    void 자료실에_없는_애드온은_거부한다() {
        when(libraryRepository.findByNameIgnoreCaseAndDeletedAtIsNull("Nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(1L, param("기본", List.of("Nope"), null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("자료실에 없는 애드온");
        verify(setupRepository, never()).save(any());
    }

    @Test
    void 승인되지_않았거나_없는_사용자에게는_공유할_수_없다() {
        when(accountRepository.findByLoginIdInAndStatusAndDeletedAtIsNull(anyCollection(), any()))
                .thenReturn(List.of(account(2L, "bob", "밥")));

        assertThatThrownBy(() -> service.create(1L, param("기본", List.of("Questie"), List.of("bob", "ghost"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("공유할 수 없는 사용자");
        verify(setupRepository, never()).save(any());
    }

    @Test
    void 자기_자신은_공유_대상에서_뺀다() {
        when(accountRepository.findByLoginIdInAndStatusAndDeletedAtIsNull(anyCollection(), any()))
                .thenReturn(List.of(account(1L, "me", "나"), account(2L, "bob", "밥")));

        service.create(1L, param("기본", List.of("Questie"), List.of("me", "bob")));

        ArgumentCaptor<AddonSetupTargetEntity> captor = ArgumentCaptor.forClass(AddonSetupTargetEntity.class);
        verify(targetRepository).save(captor.capture());
        assertThat(captor.getValue().getAccountId()).isEqualTo(2L);
    }

    @Test
    void 남의_세팅은_고치거나_지울_수_없고_존재도_알리지_않는다() {
        AddonSetupEntity others = AddonSetupEntity.builder().id(5L).publicId("p").ownerId(9L).name("x").addons("Questie").build();
        when(setupRepository.findByPublicId("p")).thenReturn(Optional.of(others));

        assertThatThrownBy(() -> service.delete(1L, "p")).isInstanceOf(BusinessException.class).hasMessage("데이터를 찾을 수 없습니다.");
        assertThatThrownBy(() -> service.update(1L, "p", param("n", List.of("Questie"), null))).isInstanceOf(BusinessException.class);
        verify(setupRepository, never()).delete(any());
        verify(targetRepository, never()).deleteBySetupId(any());
    }

    @Test
    void 세팅은_한_사람당_30개까지() {
        when(setupRepository.countByOwnerId(1L)).thenReturn((long) SetupService.MAX_SETUPS_PER_OWNER);

        assertThatThrownBy(() -> service.create(1L, param("기본", List.of("Questie"), null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("30개");
    }

    @Test
    void 받은_사람에게는_다른_공유_대상을_내려주지_않고_자료실에서_사라진_애드온은_따로_알린다() {
        AddonSetupEntity received = AddonSetupEntity.builder().id(5L).publicId("p").ownerId(9L).name("레이드").addons("Questie,Gone").build();
        when(setupRepository.findByOwnerIdOrderByIdDesc(2L)).thenReturn(List.of());
        when(targetRepository.findByAccountId(2L)).thenReturn(List.of(AddonSetupTargetEntity.builder().setupId(5L).accountId(2L).build()));
        when(setupRepository.findByIdInOrderByIdDesc(anyCollection())).thenReturn(List.of(received));
        when(accountRepository.findAllById(anyCollection())).thenReturn(List.of(account(9L, "boss", "대장"), account(2L, "bob", "밥")));

        SetupListResponse list = service.list(2L);

        assertThat(list.getReceived()).hasSize(1);
        SetupResponse r = list.getReceived().get(0);
        assertThat(r.isMine()).isFalse();
        assertThat(r.getOwnerLoginId()).isEqualTo("boss");
        assertThat(r.getTargets()).isEmpty();
        assertThat(r.getAddons()).containsExactly("Questie");
        assertThat(r.getMissingAddons()).containsExactly("Gone");
    }
}
