package com.example.demo.iface.rest;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.application.service.ProjectQueryService;
import com.example.demo.application.shared.dto.GanttTaskGottenResult;
import com.example.demo.iface.dto.GanttTaskGottenResource;
import com.example.demo.iface.dto.ProjectGottenResource;
import com.example.demo.iface.dto.ProjectTasksGottenResource;
import com.example.demo.iface.dto.SingleProjectGottenResource;
import com.example.demo.infra.projection.ProjectView;
import com.example.demo.infra.projection.TaskView;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectQueryController {

	private final ProjectQueryService projectQueryService;

	/**
	 * 查詢該使用者所在的所有專案列表
	 */
	@GetMapping
	public ResponseEntity<ProjectGottenResource> getAllProjects(@RequestHeader("X-Tenant-ID") String tenantId,
			@RequestParam String userId) {
		// 實際生產環境建議由登入 Token/SecurityContext 解析 userId
		List<ProjectView> projects = projectQueryService.getProjectsByMember(tenantId, userId);
		return ResponseEntity.ok(new ProjectGottenResource("200", "Success", projects));
	}

	/**
	 * 查詢單一專案詳細資訊
	 */
	@GetMapping("/{projectId}")
	public ResponseEntity<SingleProjectGottenResource> getProjectById(@RequestHeader("X-Tenant-ID") String tenantId,
			@PathVariable String projectId) {

		ProjectView project = projectQueryService.getProjectById(tenantId, projectId);
		return ResponseEntity.ok(new SingleProjectGottenResource("200", "Success", project));
	}

	/**
	 * 查詢指定專案下的所有任務清單 (Gantt Chart 友善)
	 */
	@GetMapping("/{projectId}/tasks")
	public ResponseEntity<ProjectTasksGottenResource> getProjectTasks(@RequestHeader("X-Tenant-ID") String tenantId,
			@PathVariable String projectId) {

		List<TaskView> tasks = projectQueryService.getProjectTasks(tenantId, projectId);
		return ResponseEntity.ok(new ProjectTasksGottenResource("200", "Success", tasks));
	}

	@GetMapping("/{projectId}/gantt")
	public ResponseEntity<GanttTaskGottenResource> getGanttChart(@PathVariable String projectId) {
		List<GanttTaskGottenResult> data = projectQueryService.getGanttData(projectId);
		return ResponseEntity.ok(new GanttTaskGottenResource("200", "Success", data));
	}

}