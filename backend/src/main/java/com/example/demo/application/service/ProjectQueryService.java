package com.example.demo.application.service;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.application.shared.dto.GanttTaskGottenResult;
import com.example.demo.infra.exception.ResourceNotFoundException;
import com.example.demo.infra.persistence.ProjectViewRepository;
import com.example.demo.infra.persistence.TaskViewRepository;
import com.example.demo.infra.projection.ProjectView;
import com.example.demo.infra.projection.TaskView;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 專案讀取端應用服務 (CQRS Query Service)
 *
 * <pre>
 * <b>架構定位與設計核心：</b>
 * <ul>
 * <li><b>讀寫分離 (CQRS - Query Side)：</b> 本服務專責處理所有來自前端或外部系統的資料讀取請求。內部「絕對不」涉及任何狀態變更，亦不依賴任何 Command 聚合根。</li>
 * <li><b>多租戶隔離防護 (Multi-Tenancy Isolation)：</b> 所有的查詢方法皆強制要求傳入 {@code
 * tenantId
 * } 作為第一道防線，徹底杜絕越權存取與 ID 猜測攻擊 (IDOR)。</li>
 * <li><b>唯讀效能極大化 (Read-Only Optimization)：</b> 類別層級宣告了 {@code @Transactional(readOnly = true)}。這會告知 Hibernate 不需要為查出的 Entity 建立快照，也不需進行髒檢查 (Dirty Checking)，大幅降低記憶體消耗並提升吞吐量。</li>
 * </ul>
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectQueryService {

	private final ProjectViewRepository projectRepository;
	private final TaskViewRepository taskRepository;

	// ==========================================
	// 專案層級查詢
	// ==========================================

	/**
	 * 查詢指定租戶下，特定使用者所參與 (身為 TeamMember) 的所有專案清單。
	 *
	 * @param tenantId 租戶識別碼 (安全邊界防護)
	 * @param userId   目標使用者的員工編號/識別碼
	 * @return 該使用者參與的所有專案視圖清單；若無則回傳空列表
	 */
	public List<ProjectView> getProjectsByMember(String tenantId, String userId) {
		log.debug("Fetching projects for tenant: {} where user: {} is a team member", tenantId, userId);
		
		// 呼叫帶有子查詢的高效 Repository 方法
		return projectRepository.findByTenantIdAndMemberUserId(tenantId, userId);
	}

	/**
	 * 查詢指定專案的詳細資訊 (單筆)。
	 *
	 * @param tenantId  租戶識別碼 (作為安全邊界防護)
	 * @param projectId 目標專案唯一識別碼
	 * @return 專案的詳細視圖實體
	 * @throws ResourceNotFoundException 若專案不存在，或該專案不屬於此租戶時拋出
	 */
	public ProjectView getProjectById(String tenantId, String projectId) {
		log.debug("Fetching project [{}] for tenant: {}", projectId, tenantId);

		// 結合 tenantId 與 projectId 進行雙重條件查詢，防止惡意使用者透過竄改 UUID 偷看別家公司的專案
		return projectRepository.findByTenantIdAndProjectId(tenantId, projectId)
				.orElseThrow(() -> new ResourceNotFoundException("找不到指定的專案或無權限存取"));
	}

	// ==========================================
	// 任務層級 (甘特圖) 查詢
	// ==========================================

	/**
	 * 查詢指定專案轄下的所有任務清單 (甘特圖資料源)。
	 * 
	 * <pre>
	 * <b>效能優化策略：</b> 
	 * 這裡故意不透過 project.getTasks() 的 ORM 關聯物件圖來取得資料，
	 * 而是直接針對 view_tasks 實體表下達 SQL 查詢。
	 * 這樣能完美避開 JPA 最惡名昭彰的 N+1 查詢問題，也避免將不必要的 Project 本體載入記憶體。
	 * </pre>
	 *
	 * @param tenantId  租戶識別碼
	 * @param projectId 目標專案唯一識別碼
	 * @return 該專案下所有依建立時間排序的任務視圖清單
	 * @throws ResourceNotFoundException 若母專案不存在或無權存取時拋出
	 */
	public List<TaskView> getProjectTasks(String tenantId, String projectId) {
		log.debug("Fetching tasks for project [{}] under tenant: {}", projectId, tenantId);

		// 1. 邊界與權限防禦：先確認專案存在且真的屬於該租戶
		// 使用 exists 查詢只會回傳 boolean，比撈出整個 Project 實體更節省資料庫效能
		if (!projectRepository.existsByTenantIdAndProjectId(tenantId, projectId)) {
			log.info("tenant:{}, projectId:{}", tenantId, projectId);
			throw new ResourceNotFoundException("找不到指定的專案或無權限存取");
		}

		// 2. 精準撈取任務：實作多租戶資料隔離，並在資料庫層面直接排序好，減輕 AP Server 的負擔
		return taskRepository.findByTenantIdAndProjectIdOrderByCreatedAtAsc(tenantId, projectId);
	}

	public List<GanttTaskGottenResult> getGanttData(String projectId) {
		List<TaskView> tasks = taskRepository.findByProjectId(projectId);

		return tasks.stream()
				.map(t -> new GanttTaskGottenResult(t.getTaskId(), t.getName(),
						t.getStartDate() != null ? t.getStartDate().toString() : LocalDate.now().toString(),
						calculateDuration(t.getStartDate(), t.getEndDate()), t.getProgress() / 100.0f, t.getModule(),
						true))
				.collect(Collectors.toList());
	}

	private int calculateDuration(LocalDate start, LocalDate end) {
		if (start == null || end == null) {
			return 1;
		}
		return (int) java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
	}
}