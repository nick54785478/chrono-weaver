package com.example.demo.application.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.infra.exception.ResourceNotFoundException;
import com.example.demo.infra.persistence.ProjectViewRepository;
import com.example.demo.infra.persistence.TeamMemberRepository;
import com.example.demo.infra.projection.TeamMemberView;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 專案團隊讀取端應用服務 (CQRS Query Service)
 *
 * <pre>
 * <b>架構定位與設計核心：</b>
 * <ul>
 * <li><b>讀寫分離：</b> 專責處理團隊成員的唯讀查詢，直接存取 TeamMemberView 實體。</li>
 * <li><b>多租戶隔離：</b> 強制校驗 tenantId，防止越權偷看其他公司的專案團隊名單。</li>
 * <li><b>效能極大化：</b> 標註 readOnly = true，免除 Hibernate 快照與髒檢查負擔。</li>
 * </ul>
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectTeamQueryService {

	private final TeamMemberRepository teamMemberRepository;
	private final ProjectViewRepository projectRepository; // 借用專案的 Repository 來做權限防護

	/**
	 * 查詢指定專案下的所有團隊成員名單。
	 *
	 * @param tenantId  租戶識別碼 (第一道安全防線)
	 * @param projectId 目標專案唯一識別碼
	 * @return 該專案下所有依加入時間排序的成員視圖清單
	 * @throws ResourceNotFoundException 若專案不存在或無權存取時拋出
	 */
	public List<TeamMemberView> getTeamMembers(String tenantId, String projectId) {
		log.debug("Fetching team members for project [{}] under tenant: {}", projectId, tenantId);

		// 1. 邊界與權限防禦：先確認「母專案」存在，且真的屬於該租戶
		// 這邊的 exists 查詢極其輕量，能有效阻擋惡意 API 請求
		if (!projectRepository.existsByTenantIdAndProjectId(tenantId, projectId)) {
			log.warn("Unauthorized access attempt or project not found. tenant:{}, projectId:{}", tenantId, projectId);
			throw new ResourceNotFoundException("找不到指定的專案或無權限存取該專案團隊");
		}

		// 2. 精準撈取名單：利用 DB 索引直接排序好，減輕 AP 負擔
		return teamMemberRepository.findByTenantIdAndProjectIdOrderByJoinedAtAsc(tenantId, projectId);
	}
}