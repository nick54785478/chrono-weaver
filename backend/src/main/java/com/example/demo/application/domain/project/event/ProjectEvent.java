package com.example.demo.application.domain.project.event;

import java.time.LocalDate;
import java.util.Set;

/**
 * 專案聚合根的所有領域事件 (Domain Events)。
 * <p>
 * 在 Event Sourcing 架構中，事件代表「系統中已發生的歷史事實 (Historical Facts)」。 它們是狀態的唯一來源 (Source
 * of Truth)，一經產生即不可變更。 使用 {@code sealed interface} 限制實作，保障系統重播事件時的型別安全。
 * </p>
 */
public sealed interface ProjectEvent {

	/**
	 * 專案已建立事件
	 */
	record ProjectCreated(String tenantId, String projectId, String projectCode, String name) implements ProjectEvent {
	}

	/**
	 * 任務已新增事件
	 */
	record TaskAdded(String taskId, String displayId, String taskName) implements ProjectEvent {
	}

	/**
	 * 更新任務名稱
	 */
	record TaskNameUpdated(String taskId, String name) implements ProjectEvent {
	}

	/**
	 * 任務時程已更新事件
	 */
	record TaskScheduleUpdated(String taskId, LocalDate startDate, LocalDate endDate) implements ProjectEvent {
	}

	/**
	 * 任務進度已更新事件
	 */
	record TaskProgressUpdated(String taskId, int progress) implements ProjectEvent {
	}

	/**
	 * @deprecated 已於 v2 版本廢棄，請改用 {@link TaskDependenciesUpdated}。 保留此類別僅為配合 Pekko
	 * ReadEventAdapter 進行歷史事件向上轉型 (Upcasting)。
	 */
	@Deprecated(since = "2.0")
	record TaskDependencyAdded(String taskId, String dependsOnTaskId) implements ProjectEvent {
	}

	/**
	 * 任務相依性已建立事件
	 */
	record TaskDependenciesUpdated(String taskId, Set<String> dependencies) implements ProjectEvent {
	}

	/**
	 * 任務人員指派更新事件
	 * <p>
	 * 紀錄了誰被指派執行任務，以及誰負責最終驗收。
	 * </p>
	 * * @param taskId     任務 ID
	 * @param assigneeId 負責執行的成員 ID
	 * @param reviewerId 負責驗收的成員 ID
	 */
	public record TaskPersonnelUpdated(String taskId, String assigneeId, String reviewerId) implements ProjectEvent {
	}

	/**
	 * 任務類型更新事件
	 * <p>
	 * 允許在任務建立後，隨時補充或修改任務的分類標籤 (如：Backend, Frontend, Bug)。
	 * </p>
	 * * @param taskId   任務 ID
	 * @param taskType 任務的分類標籤
	 */
	public record TaskTypeUpdated(String taskId, String taskType) implements ProjectEvent {
	}

	// ==========================================
	// 🌟 新增：Epic / 模組分組事件
	// ==========================================

	/**
	 * 任務所屬模組 (Epic) 更新事件
	 * <p>
	 * 用於將任務分門別類，方便前端甘特圖進行群組化 (Grouping) 顯示。
	 * </p>
	 * * @param taskId 任務 ID
	 * @param module 模組名稱 (如: 購物車模組、會員系統)
	 */
	public record TaskModuleUpdated(String taskId, String module) implements ProjectEvent {
	}
}