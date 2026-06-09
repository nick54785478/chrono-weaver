package com.example.demo.infra.projection;

import java.time.Instant;

import com.example.demo.application.domain.team.aggregate.vo.Role;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "team_members", uniqueConstraints = { @UniqueConstraint(columnNames = { "projectId", "userId" }) })
public class TeamMemberView {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private String id;

	@Column(nullable = false, updatable = false)
	private String tenantId;

	@Column(nullable = false, updatable = false)
	private String projectId;

	@Column(nullable = false, updatable = false)
	private String userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	@Column(nullable = false, updatable = false)
	private Instant joinedAt;

	protected TeamMemberView() {
	}

	public TeamMemberView(String tenantId, String projectId, String userId, Role role, Instant joinedAt) {
		this.tenantId = tenantId;
		this.projectId = projectId;
		this.userId = userId;
		this.role = role;
		this.joinedAt = joinedAt;
	}

	// --- 唯讀 Getters ---
	public String getId() {
		return id;
	}

	public String getTenantId() {
		return tenantId;
	}

	public String getProjectId() {
		return projectId;
	}

	public String getUserId() {
		return userId;
	}

	public Role getRole() {
		return role;
	}

	public Instant getJoinedAt() {
		return joinedAt;
	}

	// ==========================================
	// 🌟 取消 setRole，改為明確的狀態變更行為
	// ==========================================
	/**
	 * 供 Projection 處理 MemberRoleChanged 事件時使用
	 */
	public void applyRoleChange(Role newRole) {
		if (newRole == null) {
			throw new IllegalArgumentException("Role cannot be null in View Model");
		}
		this.role = newRole;
	}
}