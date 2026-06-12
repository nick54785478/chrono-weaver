package com.example.demo.infra.adapter;

import java.util.HashSet;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.application.domain.project.event.ProjectEvent;
import com.example.demo.application.port.ProjectViewUpdaterPort;
import com.example.demo.infra.persistence.ProjectViewRepository;
import com.example.demo.infra.persistence.TaskViewRepository;
import com.example.demo.infra.projection.ProjectView;
import com.example.demo.infra.projection.TaskView;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 專案視圖更新服務 (Read Model Updater Adapter)
 * <p>
 * 負責在單一資料庫交易內，將領域事件安全地轉譯並更新至關聯式唯讀視圖 (ProjectView / TaskView)。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
class ProjectViewUpdaterAdapter implements ProjectViewUpdaterPort {

	private final ProjectViewRepository projectRepository;
	private final TaskViewRepository taskRepository;

	@Override
	@Transactional
	public void createProject(ProjectEvent.ProjectCreated created) {
		log.info("🪞 [Updater] 建立專案視圖: [{}]", created.projectId());
		ProjectView entity = new ProjectView(created.projectId(), created.tenantId(), created.name(),
				created.ownerId(), created.projectCode());
		projectRepository.save(entity);
	}

	@Override
	@Transactional
	public void addTask(String tenantId, String projectId, ProjectEvent.TaskAdded added) {
		log.info("🪞 [Updater] 新增任務視圖: [{}], 業務顯示 ID: [{}]", added.taskId(), added.displayId());
		TaskView task = TaskView.builder().taskId(added.taskId()).tenantId(tenantId).projectId(projectId)
				.displayId(added.displayId()).name(added.taskName()).progress(0).dependencies(new HashSet<>())
				.createdAt(java.time.Instant.now()).build();
		taskRepository.save(task);
	}

	@Override
	@Transactional
	public void updateTaskModule(ProjectEvent.TaskModuleUpdated moduleUpdated) {
		log.info("🪞 [Updater] 更新任務模組: [{}] 新模組: [{}]", moduleUpdated.taskId(), moduleUpdated.module());
		taskRepository.findById(moduleUpdated.taskId()).ifPresent(task -> {
			task.setModule(moduleUpdated.module());
			taskRepository.save(task);
		});
	}

	@Override
	@Transactional
	public void updateTaskName(ProjectEvent.TaskNameUpdated nameUpdated) {
		log.info("🪞 [Updater] 更新任務名稱: [{}] 新名稱: [{}]", nameUpdated.taskId(), nameUpdated.name());
		taskRepository.findById(nameUpdated.taskId()).ifPresent(task -> {
			task.setName(nameUpdated.name());
			taskRepository.save(task);
		});
	}

	@Override
	@Transactional
	public void updateTaskSchedule(ProjectEvent.TaskScheduleUpdated scheduleUpdated) {
		log.info("🪞 [Updater] 更新任務時程: [{}]", scheduleUpdated.taskId());
		taskRepository.findById(scheduleUpdated.taskId()).ifPresent(task -> {
			task.setStartDate(scheduleUpdated.startDate());
			task.setEndDate(scheduleUpdated.endDate());
			taskRepository.save(task);
		});
	}

	@Override
	@Transactional
	public void updateTaskProgress(ProjectEvent.TaskProgressUpdated progressUpdated) {
		log.info("🪞 [Updater] 更新任務進度: [{}] 進度: {}%", progressUpdated.taskId(), progressUpdated.progress());
		taskRepository.findById(progressUpdated.taskId()).ifPresent(task -> {
			task.setProgress(progressUpdated.progress());
			taskRepository.save(task);
		});
	}

	@Override
	@Transactional
	public void updateTaskDependencies(ProjectEvent.TaskDependenciesUpdated dependenciesUpdated) {
		log.info("🪞 [Updater] 更新任務相依性: [{}] 依賴數量: [{}]", dependenciesUpdated.taskId(),
				dependenciesUpdated.dependencies().size());
		taskRepository.findById(dependenciesUpdated.taskId()).ifPresent(task -> {
			Set<String> newDependencies = new HashSet<>(dependenciesUpdated.dependencies());
			task.setDependencies(newDependencies);
			taskRepository.save(task);
		});
	}

	@Override
	@Transactional
	public void updateTaskPersonnel(ProjectEvent.TaskPersonnelUpdated personnelUpdated) {
		log.info("🪞 [Updater] 更新任務人員指派: [{}] 執行者: [{}], 審查者: [{}]", personnelUpdated.taskId(),
				personnelUpdated.assigneeId(), personnelUpdated.reviewerId());
		taskRepository.findById(personnelUpdated.taskId()).ifPresent(task -> {
			task.setAssigneeId(personnelUpdated.assigneeId());
			task.setReviewerId(personnelUpdated.reviewerId());
			taskRepository.save(task);
		});
	}

	@Override
	@Transactional
	public void updateTaskType(ProjectEvent.TaskTypeUpdated typeUpdated) {
		log.info("🪞 [Updater] 更新任務類型: [{}] 種類: [{}]", typeUpdated.taskId(), typeUpdated.taskType());
		taskRepository.findById(typeUpdated.taskId()).ifPresent(task -> {
			task.setTaskType(typeUpdated.taskType());
			taskRepository.save(task);
		});
	}
}