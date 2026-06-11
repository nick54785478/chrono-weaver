package com.example.demo.application.port;

import com.example.demo.application.domain.team.event.ProjectTeamEvent;

public interface TeamMemberViewUpdaterPort {

	void addMember(String tenantId, String projectId, ProjectTeamEvent.MemberAdded event);

	void changeMemberRole(String projectId, ProjectTeamEvent.MemberRoleChanged event);

	void removeMember(String projectId, ProjectTeamEvent.MemberRemoved event);
}