package com.example.demo.iface.rest;

import java.util.concurrent.CompletionStage;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.application.service.ProjectCommandService;
import com.example.demo.iface.dto.AddTaskResource;
import com.example.demo.iface.dto.CreateProjectResource;
import com.example.demo.iface.dto.UpdateDependenciesResource;
import com.example.demo.iface.dto.UpdateModuleResource;
import com.example.demo.iface.dto.UpdatePersonnelResource;
import com.example.demo.iface.dto.UpdateProgressResource;
import com.example.demo.iface.dto.UpdateScheduleResource;
import com.example.demo.iface.dto.UpdateTaskNameResource;
import com.example.demo.iface.dto.UpdateTaskTypeResource;

import lombok.RequiredArgsConstructor;

/**
 * 專案寫入端控制器 (CQRS Command Controller)
 *
 * <p>
 * 💡 <b>架構定位：</b> 本控制器專司處理所有會改變系統狀態的寫入請求 (Commands)。 嚴格遵守 RESTful API
 * 設計規範，將「專案」與「任務」視為階層式資源 (Hierarchical Resources)。 所有請求皆強制要求提供
 * {@code X-Tenant-ID} 標頭，以落實多租戶邊界防護。
 * </p>
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor // 💡 實務推薦：取代 @AllArgsConstructor，確保只針對 final 欄位進行依賴注入
public class ProjectCommandController {

	private final ProjectCommandService commandService;

	// ==========================================
	// 1. 專案管理 API
	// ==========================================

	/**
	 * 建立全新的專案 (創世操作)
	 *
	 * @param tenantId 多租戶識別碼 (由 API Gateway 或 Auth Filter 傳入)
	 * @param resource 包含專案名稱和專案代碼等初始化參數
	 * @return HTTP 200 及剛生成的 Project ID；若違反領域規則則回傳 400 Bad Request
	 */
	@PostMapping
	public CompletionStage<ResponseEntity<?>> createProject(@RequestHeader("X-Tenant-ID") String tenantId,
			@RequestBody CreateProjectResource resource) {

		// 🌟 把 resource.projectCode() 傳進去
		return commandService.createProject(tenantId, resource.projectCode(), resource.name())
				.thenApply(this::handleResult);
	}

	// ==========================================
	// 2. 任務管理 API (RESTful 階層設計)
	// ==========================================

	/**
	 * 於指定專案下新增一筆任務
	 *
	 * @param tenantId  多租戶識別碼
	 * @param projectId 目標專案 ID (URL Path)
	 * @param resource  包含任務名稱等初始參數
	 * @return HTTP 200 及剛生成的 Task ID
	 */
	@PostMapping("/{projectId}/tasks")
	public CompletionStage<ResponseEntity<?>> addTask(@RequestHeader("X-Tenant-ID") String tenantId,
			@PathVariable String projectId, @RequestBody AddTaskResource resource) {

		return commandService.addTask(tenantId, projectId, resource.name()).thenApply(this::handleResult);
	}

	/**
	 * 更新任務名稱
	 */
	@PatchMapping("/{projectId}/tasks/{taskId}/name")
	public CompletionStage<ResponseEntity<?>> updateTaskName(@RequestHeader("X-Tenant-ID") String tenantId,
			@PathVariable String projectId, @PathVariable String taskId, @RequestBody UpdateTaskNameResource resource) {

	    return commandService.updateTaskName(tenantId, projectId, taskId, resource.name())
	            .thenApply(this::handleResult);
	}
	
	/**
	 * 更新任務的排程時間 (可用於甘特圖拖曳操作)
	 * <p>
	 * 使用 {@code PUT} 表達對時間區間的完整替換 (Idempotent 冪等操作)。
	 * </p>
	 */
	@PutMapping("/{projectId}/tasks/{taskId}/schedule")
	public CompletionStage<ResponseEntity<?>> updateTaskSchedule(@RequestHeader("X-Tenant-ID") String tenantId,
			@PathVariable String projectId, @PathVariable String taskId, @RequestBody UpdateScheduleResource request) {

		return commandService.updateTaskSchedule(tenantId, projectId, taskId, request.startDate(), request.endDate())
				.thenApply(this::handleResult);
	}

	/**
	 * 更新任務進度(百分比)
	 */
	@PutMapping("/{projectId}/tasks/{taskId}/progress")
	public CompletionStage<ResponseEntity<?>> updateTaskProgress(@RequestHeader("X-Tenant-ID") String tenantId,
			@PathVariable String projectId, @PathVariable String taskId, @RequestBody UpdateProgressResource resource) {

		return commandService.updateTaskProgress(tenantId, projectId, taskId, resource.progress())
				.thenApply(this::handleResult);
	}

	/**
	 * 更新任務間的前置相依關係 (Dependencies)
	 * <p>
	 * 💡 使用 {@code PUT} 表達對集合的全量替換。若 {@code dependencyIds} 為空陣列，則代表清除該任務的所有相依性。
	 * </p>
	 */
	@PutMapping("/{projectId}/tasks/{taskId}/dependencies")
	public CompletionStage<ResponseEntity<?>> updateTaskDependencies(@RequestHeader("X-Tenant-ID") String tenantId,
			@PathVariable String projectId, @PathVariable String taskId,
			@RequestBody UpdateDependenciesResource request) {

		return commandService.updateTaskDependencies(tenantId, projectId, taskId, request.dependencyIds())
				.thenApply(this::handleResult);
	}

	// ==========================================
	// 3. 任務屬性擴充 API (意圖導向設計)
	// ==========================================

	/**
	 * 任務人員指派 (設定執行者與審核者)
	 * <p>
	 * 💡 <b>設計亮點：</b> 使用 {@code PATCH} 動詞代表部分更新 (Partial Update)。 若 Request Body
	 * 中某個欄位未傳或傳入 {@code null}，聚合根將會保留該欄位的原始狀態； 若前端意圖「拔除」某個負責人，需約定傳入空字串 {@code ""}。
	 * </p>
	 */
	@PatchMapping("/{projectId}/tasks/{taskId}/personnel")
	public CompletionStage<ResponseEntity<?>> updateTaskPersonnel(@RequestHeader("X-Tenant-ID") String tenantId,
			@PathVariable String projectId, @PathVariable String taskId,
			@RequestBody UpdatePersonnelResource resource) {

		return commandService
				.updateTaskPersonnel(tenantId, projectId, taskId, resource.assigneeId(), resource.reviewerId())
				.thenApply(this::handleResult);
	}

	/**
	 * 更新任務類型 (標籤分類)
	 * <p>
	 * 提供敏捷開發中隨時為任務打上 Backend, Frontend 等標籤的彈性。
	 * </p>
	 */
	@PatchMapping("/{projectId}/tasks/{taskId}/type")
	public CompletionStage<ResponseEntity<?>> updateTaskType(@RequestHeader("X-Tenant-ID") String tenantId,
			@PathVariable String projectId, @PathVariable String taskId, @RequestBody UpdateTaskTypeResource resource) {

		return commandService.updateTaskType(tenantId, projectId, taskId, resource.taskType())
				.thenApply(this::handleResult);
	}
	
	/**
	 * 更新任務所屬的模組 (Epic / 大功能)
	 * <p>
	 * 用於將任務分門別類，方便前端甘特圖進行群組化 (Grouping) 顯示。
	 * </p>
	 */
	@PatchMapping("/{projectId}/tasks/{taskId}/module")
	public CompletionStage<ResponseEntity<?>> updateTaskModule(@RequestHeader("X-Tenant-ID") String tenantId,
			@PathVariable String projectId, @PathVariable String taskId, @RequestBody UpdateModuleResource resource) {

		return commandService.updateTaskModule(tenantId, projectId, taskId, resource.module())
				.thenApply(this::handleResult);
	}

	// ==========================================
	// 共用邏輯與 DTO (Request Records)
	// ==========================================

	/**
	 * 統一處理 CommandResult 與 HTTP 狀態碼的轉換
	 * <p>
	 * 將 Pekko 封裝的回應結果轉譯為前端友善的 HTTP 協定狀態。
	 * </p>
	 *
	 * @param result 領域聚合根執行業務邏輯後的結果
	 * @return 200 OK (含 Resource ID) 或 400 Bad Request (含業務錯誤訊息)
	 */
	private ResponseEntity<?> handleResult(Object result) {
		try {
			// 利用 Reflection 或強轉判斷 success 狀態 (此處以簡單的 getter 概念示意)
			var successField = result.getClass().getMethod("success");
			boolean isSuccess = (boolean) successField.invoke(result);

			if (isSuccess) {
				// 成功時，直接把整包 Record (例如 TaskAddedResult) 當作 JSON 回傳給前端！
				return ResponseEntity.ok(result);
			} else {
				var messageField = result.getClass().getMethod("message");
				String errorMsg = (String) messageField.invoke(result);
				return ResponseEntity.badRequest().body(errorMsg);
			}
		} catch (Exception e) {
			return ResponseEntity.internalServerError().body("系統內部封裝錯誤");
		}
	}

}