package com.example.demo.application.domain.team.aggregate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.demo.application.domain.team.aggregate.vo.Role;
import com.example.demo.application.domain.team.aggregate.vo.TeamMember;
import com.example.demo.application.domain.team.event.ProjectTeamEvent;
import com.example.demo.application.shared.exception.DomainException;

/**
 * 專案團隊領域聚合根 (Pure Domain Aggregate Root)
 *
 * <pre>
 * <b>架構設計核心：</b>
 * <ul>
 * <li><b>併發隔離：</b> 將團隊成員的增減與專案核心 (Project) 分離，避免 HR 加人時，與 PO 改專案名稱發生樂觀鎖衝突。</li>
 * <li><b>1:1 ID 映射：</b> 此聚合的 projectId 與 Project 聚合的 projectId 絕對相同。</li>
 * </ul>
 * </pre>
 */
public record ProjectTeam(String tenantId, String projectId, Map<String, TeamMember> members // 以 userId 為 Key，確保成員唯一性
) {

	/**
	 * 建立聚合根的初始空狀態 (Zero State)
	 */
	public static ProjectTeam empty() {
		return new ProjectTeam(null, null, new HashMap<>());
	}

	// ==========================================
	// 1. 靜態工廠方法：無中生有
	// ==========================================

	/**
	 * 初始化團隊 (通常在 Project 建立成功後的 Saga 或 Event Handler 中觸發)
	 */
	public static List<ProjectTeamEvent> initialize(String tenantId, String projectId) {
		if (projectId == null || projectId.trim().isEmpty()) {
			throw new DomainException("專案團隊必須綁定有效的專案 ID");
		}
		return List.of(new ProjectTeamEvent.TeamInitialized(tenantId, projectId));
	}

	// ==========================================
	// 2. 實體方法 (Command Handlers)：防護規則
	// ==========================================

	/**
	 * 加入新成員
	 */
	public List<ProjectTeamEvent> addMember(String userId, Role role) {
		if (this.projectId == null) {
			throw new DomainException("團隊尚未初始化");
		}
		if (this.members.containsKey(userId)) {
			throw new DomainException("該使用者已在團隊中，不可重複加入");
		}
		if (role == null) {
			throw new DomainException("加入團隊必須指定角色");
		}

		return List.of(new ProjectTeamEvent.MemberAdded(userId, role, Instant.now()));
	}

	/**
	 * 變更成員角色
	 */
	public List<ProjectTeamEvent> changeRole(String userId, Role newRole) {
		if (!this.members.containsKey(userId)) {
			throw new DomainException("找不到該成員");
		}

		TeamMember currentMember = this.members.get(userId);

		// 🌟 修正：這裡是檢查 currentMember 的角色
		if (currentMember.role() == newRole) {
			return List.of(); // 角色無異動，等冪性保護，不產生無謂的事件
		}

		return List.of(new ProjectTeamEvent.MemberRoleChanged(userId, newRole));
	}

	/**
	 * 移除成員
	 */
	public List<ProjectTeamEvent> removeMember(String userId) {
		if (!this.members.containsKey(userId)) {
			throw new DomainException("該使用者不在團隊中");
		}

		return List.of(new ProjectTeamEvent.MemberRemoved(userId));
	}

	// ==========================================
	// 3. 狀態演進 (Event Applicator)
	// ==========================================

	public ProjectTeam apply(ProjectTeamEvent event) {
		return switch (event) {
		case ProjectTeamEvent.TeamInitialized e -> new ProjectTeam(e.tenantId(), e.projectId(), new HashMap<>());

		case ProjectTeamEvent.MemberAdded e -> {
			TeamMember newMember = new TeamMember(e.userId(), e.role(), e.joinedAt());
			yield copyWithMember(e.userId(), newMember);
		}

		case ProjectTeamEvent.MemberRoleChanged e -> {
			TeamMember oldMember = this.members.get(e.userId());
			TeamMember updatedMember = new TeamMember(oldMember.userId(), e.newRole(), oldMember.joinedAt());
			yield copyWithMember(e.userId(), updatedMember);
		}

		case ProjectTeamEvent.MemberRemoved e -> {
			Map<String, TeamMember> newMembers = new HashMap<>(this.members);
			newMembers.remove(e.userId());
			yield new ProjectTeam(this.tenantId, this.projectId, newMembers);
		}
		};
	}

	// ==========================================
	// 4. 輔助方法 (Immutability)
	// ==========================================

	private ProjectTeam copyWithMember(String userId, TeamMember member) {
		Map<String, TeamMember> newMembers = new HashMap<>(this.members);
		newMembers.put(userId, member);
		return new ProjectTeam(this.tenantId, this.projectId, newMembers);
	}
}