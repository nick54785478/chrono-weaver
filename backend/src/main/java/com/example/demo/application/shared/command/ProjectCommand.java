package com.example.demo.application.shared.command;

import java.time.LocalDate;
import java.util.Set;

import org.apache.pekko.actor.typed.ActorRef;

import com.example.demo.application.domain.project.aggregate.Project;
import com.example.demo.application.shared.response.ProjectResponse;

/**
 * 專案聚合根的所有操作指令 (Command Protocol)。
 * <p>
 * 定義了外部應用程式 (如 Controller 或 Service) 可以對 {@link Project} 聚合根發出的所有業務意圖。 結合
 * {@code sealed interface} 與 Java 21 的 Pattern Matching，確保 Pekko Actor
 * 在處理訊息時的編譯期安全， 防止遺漏任何未處理的 Command。
 * </p>
 */
public sealed interface ProjectCommand {

	/**
	 * 請求建立全新專案
	 * 
	 * @param replyTo Pekko 專用的非同步回應通道
	 */
	record CreateProject(String tenantId, String projectId, String projectCode, String name,
			ActorRef<ProjectResponse> replyTo) implements ProjectCommand {
	}

	/**
	 * 請求新增任務
	 * 
	 * @param taskId   系統內部全域唯一識別碼 (UUID)
	 * @param taskName 任務名稱
	 * @param replyTo  Pekko 專用的非同步回應通道
	 */
	record AddTask(String taskId, String taskName, ActorRef<ProjectResponse> replyTo) implements ProjectCommand {
	}

	/**
	 * 更新任務名稱
	 * 
	 * @param taskId  系統內部全域唯一識別碼 (UUID)
	 * @param name    任務名稱
	 * @param replyTo Pekko 專用的非同步回應通道
	 */
	record UpdateTaskName(String taskId, String name, ActorRef<ProjectResponse> replyTo) implements ProjectCommand {
	}

	/**
	 * 請求更新任務的開始與結束時程
	 */
	record UpdateTaskSchedule(String taskId, LocalDate startDate, LocalDate endDate, ActorRef<ProjectResponse> replyTo)
			implements ProjectCommand {
	}

	/**
	 * 請求更新任務的完成進度
	 */
	record UpdateTaskProgress(String taskId, int progress, ActorRef<ProjectResponse> replyTo)
			implements ProjectCommand {
	}

	/**
	 * 請求更新任務的先後相依條件 (全量替換)
	 *
	 * @param taskId       目標任務 ID
	 * @param dependencies 新的相依任務 ID 集合 (傳入空集合代表清空)
	 * @param replyTo      Pekko 專用的非同步回應通道
	 */
	record UpdateTaskDependencies(String taskId, Set<String> dependencies, ActorRef<ProjectResponse> replyTo)
			implements ProjectCommand {
	}

	// ==========================================
	// 🌟 擴充指令 (意圖導向設計)
	// ==========================================

	/**
	 * 請求更新任務的人員指派狀態。
	 * <p>
	 * 支援部分更新：傳入 null 代表維持現狀，傳入空字串 "" 代表清除該指派。
	 * </p>
	 *
	 * @param taskId     目標任務 ID
	 * @param assigneeId 負責執行的人員 ID (允許為 null 或 "")
	 * @param reviewerId 負責審查的人員 ID (允許為 null 或 "")
	 * @param replyTo    Pekko 專用的非同步回應通道
	 */
	record UpdateTaskPersonnel(String taskId, String assigneeId, String reviewerId, ActorRef<ProjectResponse> replyTo)
			implements ProjectCommand {
	}

	/**
	 * 請求更新任務的分類標籤。
	 *
	 * @param taskId   目標任務 ID
	 * @param taskType 新的任務類型 (如: Backend, Frontend)
	 * @param replyTo  Pekko 專用的非同步回應通道
	 */
	record UpdateTaskType(String taskId, String taskType, ActorRef<ProjectResponse> replyTo) implements ProjectCommand {
	}

	/**
	 * 請求更新任務所屬的模組 (Epic / 大功能)。
	 *
	 * @param taskId  目標任務 ID
	 * @param module  新的模組名稱 (允許為 null 或空白，代表歸類於未分類)
	 * @param replyTo Pekko 專用的非同步回應通道
	 */
	record UpdateTaskModule(String taskId, String module, ActorRef<ProjectResponse> replyTo) implements ProjectCommand {
	}
}