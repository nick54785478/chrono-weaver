//package com.example.demo.application.shared.protocal;
//
//import java.time.LocalDate;
//import java.util.HashMap;
//import java.util.Map;
//import java.util.Set;
//
//import org.apache.pekko.actor.typed.ActorRef;
//
///**
// * 共編欄位與協議
// */
//public interface ProjectProtocol {
//
//	interface Command {
//	}
//
//	interface Event {
//	}
//
//	// ==========================================
//	// 1. Commands (高顆粒度共編指令)
//	// ==========================================
//	record CreateProject(String tenantId, String projectId, String name, ActorRef<Response> replyTo)
//			implements Command {
//	}
//
//	record AddTask(String taskId, String taskName, ActorRef<Response> replyTo) implements Command {
//	}
//
//	// 專注於甘特圖核心維度的指令
//	record UpdateTaskSchedule(String taskId, LocalDate startDate, LocalDate endDate, ActorRef<Response> replyTo)
//			implements Command {
//	}
//
//	record UpdateTaskProgress(String taskId, int progress, ActorRef<Response> replyTo) implements Command {
//	}
//
//	record AddTaskDependency(String taskId, String dependsOnTaskId, ActorRef<Response> replyTo) implements Command {
//	}
//
//	record Response(boolean success, String message) {
//	}
//
//	// ==========================================
//	// 2. Events (歷史事實)
//	// ==========================================
//	record ProjectCreated(String tenantId, String projectId, String name) implements Event {
//	}
//
//	record TaskAdded(String taskId, String taskName) implements Event {
//	}
//
//	record TaskScheduleUpdated(String taskId, LocalDate startDate, LocalDate endDate) implements Event {
//	}
//
//	record TaskProgressUpdated(String taskId, int progress) implements Event {
//	}
//
//	record TaskDependencyAdded(String taskId, String dependsOnTaskId) implements Event {
//	}
//
//	// ==========================================
//	// 3. State (甘特圖所需的完整聚合根狀態)
//	// ==========================================
//	record Task(String taskId, String name, LocalDate startDate, LocalDate endDate, int progress,
//			Set<String> dependencies // 記錄依賴的其他 taskId
//	) {
//	}
//
//	record ProjectState(String tenantId, String projectId, String projectName, Map<String, Task> tasks) {
//		public static ProjectState empty() {
//			return new ProjectState(null, null, null, new HashMap<>());
//		}
//
//		public ProjectState copyWithTask(Task task) {
//			Map<String, Task> newTasks = new HashMap<>(this.tasks);
//			newTasks.put(task.taskId(), task);
//			return new ProjectState(tenantId, projectId, projectName, newTasks);
//		}
//	}
//}