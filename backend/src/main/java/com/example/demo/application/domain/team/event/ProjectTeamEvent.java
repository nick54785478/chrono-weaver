package com.example.demo.application.domain.team.event;

import java.time.Instant;

import com.example.demo.application.domain.team.aggregate.vo.Role;

/**
 * 專案團隊聚合的所有領域事件
 */
public sealed interface ProjectTeamEvent {

	record TeamInitialized(String tenantId, String projectId) implements ProjectTeamEvent {
	}

	record MemberAdded(String userId, Role role, Instant joinedAt) implements ProjectTeamEvent {
	}

	record MemberRoleChanged(String userId, Role newRole) implements ProjectTeamEvent {
	}

	record MemberRemoved(String userId) implements ProjectTeamEvent {
	}
}