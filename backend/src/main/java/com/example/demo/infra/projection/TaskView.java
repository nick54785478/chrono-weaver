package com.example.demo.infra.projection;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import com.example.demo.infra.converter.StringSetConverter;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任務唯讀視圖 (CQRS Read Model - Task Projection)
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "view_tasks", indexes = {
		// 補上複合索引：專為 getProjectTasks(tenantId, projectId) 的極速查詢量身打造
		@Index(name = "idx_view_tasks_tenant_project", columnList = "tenant_id, project_id") })
public class TaskView {

	@Id
	@Column(name = "task_id")
	private String taskId;

	// 補齊多租戶邊界欄位
	@Column(name = "tenant_id", nullable = false, length = 50)
	private String tenantId;

	// 關聯到專案的 ID，前端查詢時的關鍵
	@Column(name = "project_id", nullable = false, length = 50)
	private String projectId;

	// 給人類看的顯示用 ID (Business Key)
	@Column(name = "display_id", nullable = false, length = 50)
	private String displayId;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "start_date")
	private LocalDate startDate;

	@Column(name = "end_date")
	private LocalDate endDate;

	@Column(name = "progress")
	private int progress;
	
	@Column(name = "module")
	private String module;

	// 🌟 降維打擊：拿掉 @ElementCollection 與關聯表
	@Convert(converter = StringSetConverter.class)
	@Column(name = "dependencies", columnDefinition = "text")
	private Set<String> dependencies = new HashSet<>();

	@Column(name = "created_at", nullable = false, updatable = false)
	private java.time.Instant createdAt = java.time.Instant.now();

	// ==========================================
	// 🌟 新增：任務屬性與人員指派區塊
	// ==========================================

	/**
	 * 任務類型 (如: Backend, Design) 不使用 Enum，保留極大的彈性供租戶自定義
	 */
	@Column(name = "task_type", length = 50)
	private String taskType;

	/**
	 * 負責執行此任務的人員 ID (EmployeeId / UserId) 允許為 null (代表尚未指派 / Backlog 狀態)
	 */
	@Column(name = "assignee_id", length = 50)
	private String assigneeId;

	/**
	 * 負責審核/驗收此任務的人員 ID (EmployeeId / UserId) 允許為 null (並非所有任務都需要驗收流程)
	 */
	@Column(name = "reviewer_id", length = 50)
	private String reviewerId;

}