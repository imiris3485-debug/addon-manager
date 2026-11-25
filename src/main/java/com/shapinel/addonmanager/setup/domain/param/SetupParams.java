package com.shapinel.addonmanager.setup.domain.param;

import com.shapinel.addonmanager.common.domain.entity.AddonEventEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

public class SetupParams {

    /** 세팅 만들기·고치기. 애드온 이름과 대상 로그인 ID 는 서비스가 자료실·계정과 대조한다. */
    @Getter
    @NoArgsConstructor
    public static class SetupSaveParam {
        @NotBlank(message = "이름은 필수입니다")
        @Size(max = 50, message = "이름은 50자 이하로 입력해 주세요")
        private String name;

        @Size(max = 1000, message = "편지글은 1000자 이하로 입력해 주세요")
        private String description;

        @NotEmpty(message = "애드온을 하나 이상 고르세요")
        @Size(max = 150, message = "애드온은 150개 이하로 고르세요")
        private List<@NotBlank @Size(max = 100) String> addons;

        /** 공유할 사람의 로그인 ID. 비어 있으면 나만 보는 세팅이다. */
        @Size(max = 30, message = "공유 대상은 30명 이하로 지정해 주세요")
        private List<@NotBlank @Size(max = 50) String> targets;
    }

    /** 설치·삭제 결과 알림(앱이 설치한 뒤 보낸다) */
    @Getter
    @NoArgsConstructor
    public static class InstallEventsParam {
        @NotNull(message = "환경은 필수입니다")
        private Long environmentId;

        @Valid
        @NotEmpty(message = "이벤트가 없습니다")
        @Size(max = 50, message = "한 번에 50개까지 보낼 수 있습니다")
        private List<InstallEventParam> events;
    }

    @Getter
    @NoArgsConstructor
    public static class InstallEventParam {
        @NotBlank
        @Size(max = 100)
        private String addonName;

        @NotNull
        private AddonEventEntity.Action action;

        /** 설치·업데이트일 때 설치한 내용의 해시(64자리 16진수) */
        @Size(max = 64)
        private String contentHash;
    }
}
