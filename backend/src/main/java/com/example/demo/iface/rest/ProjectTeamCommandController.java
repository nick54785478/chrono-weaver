package com.example.demo.iface.rest;

import java.util.concurrent.CompletionStage;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.application.service.ProjectTeamCommandService;
import com.example.demo.iface.dto.AddMemberResource;
import com.example.demo.iface.dto.ChangeRoleResource;

/**
 * 專案團隊指令控制器 (Command Controller)
 * <p>
 * 架構職責： 專門處理外部對「專案團隊 (ProjectTeam)」發起的寫入/變更意圖 (Commands)。 透過非同步
 * (CompletionStage) 將 HTTP 請求轉交給 Pekko 叢集處理，不阻塞 Servlet Thread。
 * </p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/team")
public class ProjectTeamCommandController {

	private final ProjectTeamCommandService commandService;

	public ProjectTeamCommandController(ProjectTeamCommandService commandService) {
		this.commandService = commandService;
	}

	// ==========================================
	// 🌟 API 端點 (Endpoints)
	// ==========================================

	/**
	 * 加入新成員至專案團隊
	 */
	@PostMapping("/members")
	public CompletionStage<ResponseEntity<?>> addMember(@RequestHeader("X-Tenant-ID") String tenantId,
			@PathVariable String projectId, @RequestBody AddMemberResource resource) {

		// 呼叫 Service 後，直接使用 Method Reference 轉交給統一的 handleResult 處理
		return commandService.addMember(tenantId, projectId, resource.userId(), resource.role())
				.thenApply(this::handleResult);
	}

	/**
	 * 變更團隊成員角色
	 */
	@PutMapping("/members/{userId}/role")
	public CompletionStage<ResponseEntity<?>> changeRole(@RequestHeader("X-Tenant-ID") String tenantId,
			@PathVariable String projectId, @PathVariable String userId, @RequestBody ChangeRoleResource resource) {

		return commandService.changeRole(tenantId, projectId, userId, resource.role()).thenApply(this::handleResult);
	}

	/**
	 * 將成員移出專案團隊
	 */
	@DeleteMapping("/members/{userId}")
	public CompletionStage<ResponseEntity<?>> removeMember(@RequestHeader("X-Tenant-ID") String tenantId,
			@PathVariable String projectId, @PathVariable String userId) {

		return commandService.removeMember(tenantId, projectId, userId).thenApply(this::handleResult);
	}

	// ==========================================
	// 🌟 統一回應處理器
	// ==========================================

	/**
	 * 統一處理 CommandResult 與 HTTP 狀態碼的轉換
	 * <p>
	 * 將 Pekko 封裝的回應結果轉譯為前端友善的 HTTP 協定狀態。
	 * </p>
	 *
	 * @param result 領域聚合根執行業務邏輯後的結果 (通常為 Record)
	 * @return 200 OK (含成功資料) 或 400 Bad Request (含業務錯誤訊息)
	 */
	private ResponseEntity<?> handleResult(Object result) {
		try {
			// 利用 Reflection 動態提取 Record 的 success 與 message 屬性
			var successField = result.getClass().getMethod("success");
			boolean isSuccess = (boolean) successField.invoke(result);

			if (isSuccess) {
				// 成功時，直接把整包 Record 當作 JSON 回傳給前端
				return ResponseEntity.ok(result);
			} else {
				// 失敗時，提取訊息並以 HTTP 400 回傳
				var messageField = result.getClass().getMethod("message");
				String errorMsg = (String) messageField.invoke(result);
				return ResponseEntity.badRequest().body(errorMsg);
			}
		} catch (Exception e) {
			return ResponseEntity.internalServerError().body("系統內部封裝錯誤: 無法解析領域回應格式");
		}
	}
}