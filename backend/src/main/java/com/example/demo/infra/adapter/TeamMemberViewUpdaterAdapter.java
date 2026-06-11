package com.example.demo.infra.adapter;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.application.domain.team.event.ProjectTeamEvent;
import com.example.demo.application.port.TeamMemberViewUpdaterPort;
import com.example.demo.infra.persistence.TeamMemberRepository;
import com.example.demo.infra.projection.TeamMemberView;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 團隊成員視圖更新服務 (Read Model Updater)
 * <p>
 * 架構定位：這「不是」Command Service。它是專門服務於 Projection Pipeline 的內部元件。
 * 職責：負責在單一資料庫交易內，將領域事件的安全地轉譯並更新至關聯式唯讀視圖 (View)。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
class TeamMemberViewUpdaterAdapter implements TeamMemberViewUpdaterPort {

	private final TeamMemberRepository repository;

	// 🌟 完美的交易邊界：每個事件的處理都包在獨立的 Transaction 中
	@Transactional
	@Override
	public void addMember(String tenantId, String projectId, ProjectTeamEvent.MemberAdded event) {
		repository.findByProjectIdAndUserId(projectId, event.userId())
				.ifPresentOrElse(existing -> log.debug("[Updater] 成員 {} 已存在，忽略重複事件", event.userId()), () -> {
					TeamMemberView newView = new TeamMemberView(tenantId, projectId, event.userId(), event.role(),
							event.joinedAt());
					repository.save(newView);
					log.info("[Updater] 新增成員至唯讀視圖: {}", event.userId());
				});
	}

	@Transactional
	@Override
	public void changeMemberRole(String projectId, ProjectTeamEvent.MemberRoleChanged event) {
		repository.findByProjectIdAndUserId(projectId, event.userId()).ifPresent(view -> {
			view.applyRoleChange(event.newRole());
			repository.save(view);
			log.info("[Updater] 更新成員角色至唯讀視圖: {}", event.userId());
		});
	}

	// 🌟 這裡完美解決了你剛剛遇到的 EntityManager 報錯！
	@Transactional
	@Override
	public void removeMember(String projectId, ProjectTeamEvent.MemberRemoved event) {
		repository.deleteByProjectIdAndUserId(projectId, event.userId());
		log.info("[Updater] 移除成員唯讀視圖: {}", event.userId());
	}
}