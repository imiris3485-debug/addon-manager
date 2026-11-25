package com.shapinel.addonmanager.setup;

import com.shapinel.addonmanager.common.domain.entity.AddonSnapshotEntity;
import com.shapinel.addonmanager.common.domain.entity.GameEnvironmentEntity;
import com.shapinel.addonmanager.common.domain.entity.LibraryAddonEntity;
import com.shapinel.addonmanager.common.domain.repository.AddonSnapshotRepository;
import com.shapinel.addonmanager.common.domain.repository.GameEnvironmentRepository;
import com.shapinel.addonmanager.common.domain.repository.LibraryAddonRepository;
import com.shapinel.addonmanager.common.exception.BusinessException;
import com.shapinel.addonmanager.setup.domain.response.SnapshotResponses.SnapshotResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 보관함(내 애드온 목록 기록) 정책
 * - 남의 게임 환경으로는 기록할 수 없다.
 * - 자동 기록: 자료실에 있는 이름만 자료실 표기로 맞춰 이름순으로 남긴다. 빈 목록은 남기지 않는다(실수로 다 지운 직후의
 *   "없음"이 좋은 기록을 밀어내지 않게). 직전 기록과 같은 목록이면 남기지 않고, 최근 10개만 유지한다.
 * - 직접 보관: 이름이 필요하고 20개까지.
 * - 남의 기록은 지울 수 없고 존재도 알리지 않는다.
 * - 자료실에서 사라진 애드온은 목록에서 따로 알린다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SnapshotServiceTest {

    @Mock AddonSnapshotRepository snapshotRepository;
    @Mock GameEnvironmentRepository environmentRepository;
    @Mock LibraryAddonRepository libraryRepository;

    SnapshotService service;

    @BeforeEach
    void setUp() {
        service = new SnapshotService(snapshotRepository, environmentRepository, libraryRepository);
        when(environmentRepository.findByIdAndAccountIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(GameEnvironmentEntity.builder().id(7L).accountId(1L).gameVersion("1.60.1").build()));
        when(libraryRepository.findByDeletedAtIsNullOrderByNameAsc()).thenReturn(List.of(
                LibraryAddonEntity.builder().name("Questie").build(), LibraryAddonEntity.builder().name("Bagnon").build(),
                LibraryAddonEntity.builder().name("QuestieDB").build()));
        when(snapshotRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(snapshotRepository.findFirstByAccountIdAndSourceOrderByIdDesc(1L, "AUTO")).thenReturn(Optional.empty());
        when(snapshotRepository.findByAccountIdAndSourceOrderByIdAsc(1L, "AUTO")).thenReturn(List.of());
    }

    @Test
    void 남의_환경으로는_기록할_수_없다() {
        assertThatThrownBy(() -> service.saveAuto(2L, 7L, List.of("Questie"))).isInstanceOf(BusinessException.class);
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void 자동_기록은_자료실_표기로_맞춰_이름순으로_남긴다() {
        boolean saved = service.saveAuto(1L, 7L, List.of("questiedb", "BAGNON", "questie", "Unknown", "Questie"));

        assertThat(saved).isTrue();
        ArgumentCaptor<AddonSnapshotEntity> captor = ArgumentCaptor.forClass(AddonSnapshotEntity.class);
        verify(snapshotRepository).save(captor.capture());
        assertThat(captor.getValue().getAddons()).isEqualTo("Bagnon,Questie,QuestieDB");
        assertThat(captor.getValue().getGameVersion()).isEqualTo("1.60.1");
        assertThat(captor.getValue().getSource()).isEqualTo("AUTO");
    }

    @Test
    void 빈_목록은_남기지_않는다() {
        assertThat(service.saveAuto(1L, 7L, List.of())).isFalse();
        assertThat(service.saveAuto(1L, 7L, List.of("Unknown"))).isFalse();
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void 직전_기록과_같은_목록이면_남기지_않는다() {
        AddonSnapshotEntity last = AddonSnapshotEntity.builder().accountId(1L).gameVersion("1.60.1").source("AUTO").addons("Bagnon,Questie").build();
        when(snapshotRepository.findFirstByAccountIdAndSourceOrderByIdDesc(1L, "AUTO")).thenReturn(Optional.of(last));

        assertThat(service.saveAuto(1L, 7L, List.of("questie", "Bagnon"))).isFalse();
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void 자동_기록은_최근_10개만_유지한다() {
        List<AddonSnapshotEntity> autos = new ArrayList<>();
        for (int i = 0; i < 12; i++) autos.add(AddonSnapshotEntity.builder().accountId(1L).source("AUTO").addons("x" + i).build());
        when(snapshotRepository.findByAccountIdAndSourceOrderByIdAsc(1L, "AUTO")).thenReturn(autos);

        service.saveAuto(1L, 7L, List.of("Questie"));

        verify(snapshotRepository).delete(autos.get(0));
        verify(snapshotRepository).delete(autos.get(1));
        verify(snapshotRepository, never()).delete(autos.get(2));
    }

    @Test
    void 직접_보관은_이름이_필요하고_20개까지다() {
        assertThatThrownBy(() -> service.saveManual(1L, 7L, "  ", List.of("Questie"))).isInstanceOf(BusinessException.class);

        when(snapshotRepository.countByAccountIdAndSource(1L, "MANUAL")).thenReturn((long) SnapshotService.MAX_MANUAL);
        assertThatThrownBy(() -> service.saveManual(1L, 7L, "내 세팅", List.of("Questie")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("20개");
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void 자료실에_있는_애드온이_하나도_없으면_보관할_수_없다() {
        assertThatThrownBy(() -> service.saveManual(1L, 7L, "내 세팅", List.of("Unknown")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("하나도 없어");
    }

    @Test
    void 남의_기록은_지울_수_없고_존재도_알리지_않는다() {
        AddonSnapshotEntity others = AddonSnapshotEntity.builder().publicId("p").accountId(9L).source("MANUAL").addons("Questie").build();
        when(snapshotRepository.findByPublicId("p")).thenReturn(Optional.of(others));

        assertThatThrownBy(() -> service.delete(1L, "p")).isInstanceOf(BusinessException.class).hasMessage("데이터를 찾을 수 없습니다.");
        verify(snapshotRepository, never()).delete(any());
    }

    @Test
    void 자료실에서_사라진_애드온은_따로_알린다() {
        when(snapshotRepository.findByAccountIdOrderByIdDesc(1L)).thenReturn(List.of(
                AddonSnapshotEntity.builder().publicId("p").accountId(1L).source("AUTO").addons("Gone,Questie").build()));

        List<SnapshotResponse> list = service.list(1L);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getAddons()).containsExactly("Questie");
        assertThat(list.get(0).getMissingAddons()).containsExactly("Gone");
    }
}
