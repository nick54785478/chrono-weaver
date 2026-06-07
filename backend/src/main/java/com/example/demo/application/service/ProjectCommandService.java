package com.example.demo.application.service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;
import org.springframework.stereotype.Service;

import com.example.demo.application.shared.command.ProjectCommand;
import com.example.demo.application.shared.dto.ProjectCreatedResult;
import com.example.demo.application.shared.dto.TaskAddedResult;
import com.example.demo.application.shared.dto.TaskUpdatedResult;
import com.example.demo.application.shared.response.ProjectResponse;
import com.example.demo.infra.actor.ProjectEventSourcedActor;

import lombok.RequiredArgsConstructor;

/**
 * 專案寫入端應用服務 (CQRS Command Service)
 *
 * <pre>
 * <b>架構定位與考量：</b>
 * <ul>
 * <li><b>應用層防護外殼 (Application Layer Shell)：</b> 隸屬於命令端 (Write Side)。作為 Web Controller 與 Pekko Cluster Sharding 之間的神經樞紐。</li>
 * <li><b>關注點分離 (Separation of Concerns)：</b> 本服務定位於技術與通訊調度。內部「絕不包含」任何核心商務邏輯與防禦校驗，專責處理 HTTP 轉 Actor Message 的Ask 通訊協定。</li>
 * <li><b>完全非阻塞回應 (Pure Non-blocking Asynchronous)：</b> 全域端點皆回傳 {@link CompletionStage}。Web 容器執行緒將指令發送至 Actor 信箱後便立刻釋放歸還執行緒池，
 * 當底層分佈式節點完成持久化並回覆時再行喚醒非同步鏈接，具備極高的系統並發與吞吐承載力。</li>
 * </ul>
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class ProjectCommandService {

	private final ClusterSharding sharding;

	// 設定全域的 Pekko Ask Pattern 超時時間，防止 Actor 意外失效導致連鎖卡死
	private static final Duration ASK_TIMEOUT = Duration.ofSeconds(5);

	// ==========================================
	// 1. 專案級群組操作
	// ==========================================

	/**
	 * 建立全新的專案 (創世操作)
	 *
	 * @param tenantId    租戶識別碼
	 * @param projectCode 🌟 新增：專案代號 (用於任務流水號前綴，例如 "WPG", "API")
	 * @param projectName 專案名稱
	 * @return 包含生成的 Project ID 的執行結果
	 */
	public CompletionStage<ProjectCreatedResult> createProject(String tenantId, String projectCode,
			String projectName) {
		String projectId = this.generateId();
		EntityRef<ProjectCommand> entityRef = getEntityRef(tenantId, projectId);

		return entityRef
				.<ProjectResponse>ask(replyTo -> new ProjectCommand.CreateProject(tenantId, projectId, projectCode,
						projectName, replyTo), ASK_TIMEOUT)
				.thenApply(response -> new ProjectCreatedResult(response.success(), projectId, response.message()));
	}

	// ==========================================
	// 2. 任務級細粒度共編操作 (Gantt Core Dimensions)
	// ==========================================

	/**
	 * 於指定專案的範疇下，新增一筆任務節點 (Task)。
	 *
	 * @param tenantId  租戶識別碼
	 * @param projectId 目標專案 ID
	 * @param taskName  任務名稱
	 * @return 異步生成的全新任務 ID (UUID) 響應
	 */
	public CompletionStage<TaskAddedResult> addTask(String tenantId, String projectId, String taskName) {
		String taskId = this.generateId();
		EntityRef<ProjectCommand> entityRef = getEntityRef(tenantId, projectId);

		return entityRef
				.<ProjectResponse>ask(replyTo -> new ProjectCommand.AddTask(taskId, taskName, replyTo), ASK_TIMEOUT)
				// 🌟 修正：乖乖把 response.message() 放到 message 欄位就好，不要假會去塞 displayId
				.thenApply(response -> new TaskAddedResult(response.success(), taskId, response.message()));
	}

	/**
	 * 調整既有任務的時程排程（甘特圖拖拉操作核心端點）。 💡 支援部分更新 (Partial Update)：傳入 null 代表該時間維度不作異動。
	 *
	 * @param tenantId  租戶識別碼
	 * @param projectId 目標專案 ID
	 * @param taskId    欲修改時程的任務 ID
	 * @param startDate 新的開始日期 (或為 null)
	 * @param endDate   新的結束日期 (或為 null)
	 * @return 異步結果
	 */
	public CompletionStage<TaskUpdatedResult> updateTaskSchedule(String tenantId, String projectId, String taskId,
			LocalDate startDate, LocalDate endDate) {
		EntityRef<ProjectCommand> entityRef = getEntityRef(tenantId, projectId);
		return entityRef.<ProjectResponse>ask(
				replyTo -> new ProjectCommand.UpdateTaskSchedule(taskId, startDate, endDate, replyTo), ASK_TIMEOUT)
				.thenApply(response -> new TaskUpdatedResult(response.success(), taskId, response.message()));
	}

	/**
	 * 更新任務名稱。
	 */
	public CompletionStage<TaskUpdatedResult> updateTaskName(String tenantId, String projectId, String taskId,
			String name) {
		EntityRef<ProjectCommand> entityRef = getEntityRef(tenantId, projectId);
		return entityRef
				.<ProjectResponse>ask(replyTo -> new ProjectCommand.UpdateTaskName(taskId, name, replyTo), ASK_TIMEOUT)
				.thenApply(response -> new TaskUpdatedResult(response.success(), taskId, response.message()));
	}

	/**
	 * 更新任務進度百分比。
	 *
	 * @param progress 進度整數值，預期範圍為 0 到 100
	 */
	public CompletionStage<TaskUpdatedResult> updateTaskProgress(String tenantId, String projectId, String taskId,
			int progress) {
		EntityRef<ProjectCommand> entityRef = getEntityRef(tenantId, projectId);
		return entityRef
				.<ProjectResponse>ask(replyTo -> new ProjectCommand.UpdateTaskProgress(taskId, progress, replyTo),
						ASK_TIMEOUT)
				.thenApply(response -> new TaskUpdatedResult(response.success(), taskId, response.message()));
	}

	/**
	 * 更新任務間的前置相依關聯 (全量替換) 💡 傳入空集合代表清除所有相依性。
	 */
	public CompletionStage<TaskUpdatedResult> updateTaskDependencies(String tenantId, String projectId, String taskId,
			Set<String> dependencies) {
		EntityRef<ProjectCommand> entityRef = getEntityRef(tenantId, projectId);
		return entityRef.<ProjectResponse>ask(
				replyTo -> new ProjectCommand.UpdateTaskDependencies(taskId, dependencies, replyTo), ASK_TIMEOUT)
				.thenApply(response -> new TaskUpdatedResult(response.success(), taskId, response.message()));
	}

	/**
	 * 更新任務的人員指派配置（支援部分異動與人員清空）。
	 * 
	 * @param assigneeId 新的執行者 ID (傳 null 代表沿用舊值，傳空字串 "" 代表清空指派)
	 * @param reviewerId 新的審查驗收者 ID (傳 null 代表沿用舊值，傳空字串 "" 代表清空指派)
	 */
	public CompletionStage<TaskUpdatedResult> updateTaskPersonnel(String tenantId, String projectId, String taskId,
			String assigneeId, String reviewerId) {
		EntityRef<ProjectCommand> entityRef = getEntityRef(tenantId, projectId);
		return entityRef.<ProjectResponse>ask(
				replyTo -> new ProjectCommand.UpdateTaskPersonnel(taskId, assigneeId, reviewerId, replyTo), ASK_TIMEOUT)
				.thenApply(response -> new TaskUpdatedResult(response.success(), taskId, response.message()));
	}
	
	/**
	 * 更新任務所屬的模組 (Epic / 大功能)。
	 *
	 * @param module 模組名稱 (如: 購物車模組、會員系統)
	 */
	public CompletionStage<TaskUpdatedResult> updateTaskModule(String tenantId, String projectId, String taskId,
			String module) {
		EntityRef<ProjectCommand> entityRef = getEntityRef(tenantId, projectId);
		return entityRef
				.<ProjectResponse>ask(replyTo -> new ProjectCommand.UpdateTaskModule(taskId, module, replyTo),
						ASK_TIMEOUT)
				.thenApply(response -> new TaskUpdatedResult(response.success(), taskId, response.message()));
	}

	/**
	 * 更新任務的分類標籤 (Task Type)。
	 *
	 * @param taskType 分類字串 (例如: "Backend", "Frontend")
	 */
	public CompletionStage<TaskUpdatedResult> updateTaskType(String tenantId, String projectId, String taskId,
			String taskType) {
		EntityRef<ProjectCommand> entityRef = getEntityRef(tenantId, projectId);
		return entityRef
				.<ProjectResponse>ask(replyTo -> new ProjectCommand.UpdateTaskType(taskId, taskType, replyTo),
						ASK_TIMEOUT)
				.thenApply(response -> new TaskUpdatedResult(response.success(), taskId, response.message()));
	}
	// ==========================================
	// 內聚技術輔助方法區
	// ==========================================

	/**
	 * 獲取目標分佈式 Actor 的虛擬通訊參考 (EntityRef)。
	 * <p>
	 * 封裝叢集路由鍵組合規則，避免外部到處拼接字串。底層規則必須與 {@code ProjectEventSourcedActor} 保持嚴格一致。
	 * </p>
	 */
	private EntityRef<ProjectCommand> getEntityRef(String tenantId, String projectId) {
		String entityId = tenantId + "_" + projectId;
		return sharding.entityRefFor(ProjectEventSourcedActor.ENTITY_TYPE_KEY, entityId);
	}

	/**
	 * 產生高碰撞防護的唯一識別碼 (Identity ID)
	 * 
	 * 這是不得以的妥協，基於 DDD 唯一識別碼應該由 Aggregate Root 產生
	 * 
	 * <pre>
	 * <b>分散式 DDD 設計考量：</b>
	 * 1. <i>叢集路由機制 (Routing Constraint)：</i> 在發送 Ask 請求前，必須先獲得目標 Actor 的地址代號。若讓記憶體內的 Aggregate Root 內部自行生代號，外殼將陷入無法路由的死胡同。
	 * 2. <i>保護領域純粹性 (Hexagonal)：</i> UUID 依賴系統隨機與時鐘，屬於基礎設施細節。將其留在外殼服務層可確保核心 Record 成為完全無副作用的數學核心。
	 * 3. <i>等冪防護 (Idempotency)：</i> 由外部控制 ID，未來可輕易調整為接收前端傳入的 ID，直接於 Actor 內執行「已存在則阻擋」的等冪防禦。
	 * </pre>
	 */
	private String generateId() {
		return UUID.randomUUID().toString();
	}
}