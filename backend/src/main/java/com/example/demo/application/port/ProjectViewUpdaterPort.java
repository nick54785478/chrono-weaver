package com.example.demo.application.port;

import com.example.demo.application.domain.project.event.ProjectEvent;

/**
 * 專案視圖更新埠 (Read Model Updater Port) 定義更新專案與任務唯讀視圖的使用案例 (Use Cases)。
 */
public interface ProjectViewUpdaterPort {

	void createProject(ProjectEvent.ProjectCreated event);

	// 任務新增時，需要額外傳入從聚合根 ID 拆解出來的 tenantId 與 projectId
	void addTask(String tenantId, String projectId, ProjectEvent.TaskAdded event);

	void updateTaskModule(ProjectEvent.TaskModuleUpdated event);

	void updateTaskName(ProjectEvent.TaskNameUpdated event);

	void updateTaskSchedule(ProjectEvent.TaskScheduleUpdated event);

	void updateTaskProgress(ProjectEvent.TaskProgressUpdated event);

	void updateTaskDependencies(ProjectEvent.TaskDependenciesUpdated event);

	void updateTaskPersonnel(ProjectEvent.TaskPersonnelUpdated event);

	void updateTaskType(ProjectEvent.TaskTypeUpdated event);
}