package com.example.demo.iface.rest;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.application.service.ProjectTeamQueryService;
import com.example.demo.iface.dto.TeamMembersGottenResource;
import com.example.demo.infra.projection.TeamMemberView;

import lombok.RequiredArgsConstructor;

/**
 * 專案團隊查詢控制器 (Query Controller)
 * <p>
 * 專責處理外部對「專案團隊 (ProjectTeam)」發起的查詢請求 (Queries)。 路由與 Command Controller
 * 保持一致，但只接受 GET 方法。
 * </p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/team")
@RequiredArgsConstructor
public class ProjectTeamQueryController {

	private final ProjectTeamQueryService queryService;

	/**
	 * 取得專案團隊成員列表
	 */
	@GetMapping("/members")
	public ResponseEntity<TeamMembersGottenResource> getTeamMembers(@RequestHeader("X-Tenant-ID") String tenantId,
			@PathVariable String projectId) {
		// 直接調用唯讀的 Query Service 取得平坦化的視圖資料
		List<TeamMemberView> members = queryService.getTeamMembers(tenantId, projectId);
		return new ResponseEntity<>(new TeamMembersGottenResource("200", "Success", members), HttpStatus.OK);
	}
}