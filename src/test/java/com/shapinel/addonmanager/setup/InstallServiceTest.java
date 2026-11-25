package com.shapinel.addonmanager.setup;

import com.shapinel.addonmanager.common.domain.entity.AccountAddonEntity;
import com.shapinel.addonmanager.common.domain.entity.AddonEventEntity;
import com.shapinel.addonmanager.common.domain.entity.AddonEventEntity.Action;
import com.shapinel.addonmanager.common.domain.entity.GameEnvironmentEntity;
import com.shapinel.addonmanager.common.domain.entity.LibraryAddonEntity;
import com.shapinel.addonmanager.common.domain.repository.AccountAddonRepository;
import com.shapinel.addonmanager.common.domain.repository.AddonEventRepository;
import com.shapinel.addonmanager.common.domain.repository.GameEnvironmentRepository;
import com.shapinel.addonmanager.common.domain.repository.LibraryAddonRepository;
import com.shapinel.addonmanager.common.exception.BusinessException;
import com.shapinel.addonmanager.setup.domain.param.SetupParams.InstallEventParam;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 설치 기록 정책
 * - 남의 게임 환경에는 기록할 수 없다(환경은 항상 로그인한 계정 소유로만 조회).
 * - 설치·업데이트는 자료실에 있는 애드온만, 올바른 해시와 함께 기록한다. 처음이면 INSTALLED, 이미 있으면 UPDATED.
 * - 삭제는 현황에서 지우고 이력만 남긴다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InstallServiceTest {

    static final String HASH = "a".repeat(64);

    @Mock GameEnvironmentRepository environmentRepository;
    @Mock AccountAddonRepository accountAddonRepository;
    @Mock AddonEventRepository eventRepository;
    @Mock LibraryAddonRepository libraryRepository;

    InstallService service;

    @BeforeEach
    void setUp() {
        service = new InstallService(environmentRepository, accountAddonRepository, eventRepository, libraryRepository);
        when(environmentRepository.findByIdAndAccountIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(GameEnvironmentEntity.builder().id(7L).accountId(1L).build()));
        for (String n : List.of("questie", "Questie")) {
            when(libraryRepository.findByNameIgnoreCaseAndDeletedAtIsNull(n))
                    .thenReturn(Optional.of(LibraryAddonEntity.builder().name("Questie").build()));
        }
    }

    private static InstallEventParam event(String name, Action action, String hash) {
        InstallEventParam p = new InstallEventParam();
        ReflectionTestUtils.setField(p, "addonName", name);
        ReflectionTestUtils.setField(p, "action", action);
        ReflectionTestUtils.setField(p, "contentHash", hash);
        return p;
    }

    @Test
    void 남의_환경에는_기록할_수_없다() {
        assertThatThrownBy(() -> service.record(2L, 7L, List.of(event("Questie", Action.INSTALLED, HASH))))
                .isInstanceOf(BusinessException.class);
        verify(accountAddonRepository, never()).save(any());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void 처음_설치하면_INSTALLED_로_기록하고_자료실_표기로_맞춘다() {
        when(accountAddonRepository.findByEnvironmentIdAndAddonName(7L, "Questie")).thenReturn(Optional.empty());

        service.record(1L, 7L, List.of(event("questie", Action.INSTALLED, HASH)));

        ArgumentCaptor<AccountAddonEntity> saved = ArgumentCaptor.forClass(AccountAddonEntity.class);
        verify(accountAddonRepository).save(saved.capture());
        assertThat(saved.getValue().getAddonName()).isEqualTo("Questie");
        ArgumentCaptor<AddonEventEntity> ev = ArgumentCaptor.forClass(AddonEventEntity.class);
        verify(eventRepository).save(ev.capture());
        assertThat(ev.getValue().getAction()).isEqualTo(Action.INSTALLED);
    }

    @Test
    void 이미_있으면_해시를_바꾸고_UPDATED_로_기록한다() {
        AccountAddonEntity existing = AccountAddonEntity.builder().environmentId(7L).addonName("Questie").contentHash("b".repeat(64)).build();
        when(accountAddonRepository.findByEnvironmentIdAndAddonName(7L, "Questie")).thenReturn(Optional.of(existing));

        service.record(1L, 7L, List.of(event("Questie", Action.UPDATED, HASH)));

        assertThat(existing.getContentHash()).isEqualTo(HASH);
        verify(accountAddonRepository, never()).save(any());
        ArgumentCaptor<AddonEventEntity> ev = ArgumentCaptor.forClass(AddonEventEntity.class);
        verify(eventRepository).save(ev.capture());
        assertThat(ev.getValue().getAction()).isEqualTo(Action.UPDATED);
    }

    @Test
    void 자료실에_없거나_해시가_잘못되면_거부한다() {
        when(libraryRepository.findByNameIgnoreCaseAndDeletedAtIsNull("Nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.record(1L, 7L, List.of(event("Nope", Action.INSTALLED, HASH))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("자료실에 없는");
        assertThatThrownBy(() -> service.record(1L, 7L, List.of(event("Questie", Action.INSTALLED, "xyz"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("해시");
        assertThatThrownBy(() -> service.record(1L, 7L, List.of(event("../evil", Action.REMOVED, null))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 삭제는_현황에서_지우고_이력만_남긴다() {
        AccountAddonEntity existing = AccountAddonEntity.builder().environmentId(7L).addonName("Questie").contentHash(HASH).build();
        when(accountAddonRepository.findByEnvironmentIdAndAddonName(7L, "Questie")).thenReturn(Optional.of(existing));

        service.record(1L, 7L, List.of(event("Questie", Action.REMOVED, null)));

        verify(accountAddonRepository).delete(existing);
        ArgumentCaptor<AddonEventEntity> ev = ArgumentCaptor.forClass(AddonEventEntity.class);
        verify(eventRepository).save(ev.capture());
        assertThat(ev.getValue().getAction()).isEqualTo(Action.REMOVED);
    }
}
