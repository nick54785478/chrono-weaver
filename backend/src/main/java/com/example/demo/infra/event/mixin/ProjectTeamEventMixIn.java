package com.example.demo.infra.event.mixin;

import com.example.demo.application.domain.team.event.ProjectTeamEvent;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 專案團隊事件的 Jackson 序列化替身 (MixIn) 負責隔離底層的 JSON 解析邏輯，保護 Domain 的純潔性。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({ @JsonSubTypes.Type(value = ProjectTeamEvent.TeamInitialized.class, name = "TeamInitialized"),
		@JsonSubTypes.Type(value = ProjectTeamEvent.MemberAdded.class, name = "MemberAdded"),
		@JsonSubTypes.Type(value = ProjectTeamEvent.MemberRoleChanged.class, name = "MemberRoleChanged"),
		@JsonSubTypes.Type(value = ProjectTeamEvent.MemberRemoved.class, name = "MemberRemoved") })
public interface ProjectTeamEventMixIn {
	// 這裡裡面什麼都不用寫！它只是一個用來掛載註解的空殼。
}