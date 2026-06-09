package com.example.demo.application.shared.command;

import org.apache.pekko.actor.typed.ActorRef;

import com.example.demo.application.domain.team.aggregate.vo.Role;
import com.example.demo.application.shared.response.ProjectResponse;

/**
 * 專案團隊聚合的所有操作指令 (Command Protocol)。
 */
public sealed interface ProjectTeamCommand {

	/**
	 * 請求初始化專案團隊 (通常在 Project 建立後觸發)
	 */
	record InitializeTeam(String tenantId, String projectId, ActorRef<ProjectResponse> replyTo)
			implements ProjectTeamCommand {
	}

	/**
	 * 請求加入新成員
	 */
	record AddMember(String userId, Role role, ActorRef<ProjectResponse> replyTo) implements ProjectTeamCommand {
	}

	/**
	 * 請求變更成員角色
	 */
	record ChangeRole(String userId, Role newRole, ActorRef<ProjectResponse> replyTo) implements ProjectTeamCommand {
	}

	/**
	 * 請求移除成員
	 */
	record RemoveMember(String userId, ActorRef<ProjectResponse> replyTo) implements ProjectTeamCommand {
	}
}