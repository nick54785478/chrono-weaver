package com.example.demo.iface.event;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.apache.pekko.Done;
import org.apache.pekko.projection.eventsourced.EventEnvelope;
import org.apache.pekko.projection.javadsl.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.demo.application.domain.project.event.ProjectEvent;
import com.example.demo.application.port.ProjectViewUpdaterPort;

/**
 * 專案讀取模型更新引擎 (CQRS Read Model Projection)
 * <p>
 * 採用 Port-Adapter 架構，本元件專責訂閱與解析 Pekko Event Journal 串流。
 * 實際的資料庫變更與 @Transactional 交易邊界，統一委託給 {@link ProjectViewUpdaterPort} 執行。
 * </p>
 */
@Component
public class ProjectProjectionHandler extends Handler<EventEnvelope<ProjectEvent>> {

	private static final Logger log = LoggerFactory.getLogger(ProjectProjectionHandler.class);

	// 🌟 注入 Port，依賴反轉
	private final ProjectViewUpdaterPort viewUpdater;

	public ProjectProjectionHandler(ProjectViewUpdaterPort viewUpdater) {
		this.viewUpdater = viewUpdater;
	}

	@Override
	public CompletionStage<Done> process(EventEnvelope<ProjectEvent> envelope) {
		ProjectEvent event = envelope.event();

		// 💡 實務技巧：從 Pekko 信封提取 Entity ID 並分離租戶邊界
		String persistenceId = envelope.persistenceId();
		String entityId = persistenceId.split("\\|")[1];
		String[] idParts = entityId.split("_", 2);
		String tenantId = idParts[0];
		String projectId = idParts[1];

		try {
			// 使用 Java 21 Pattern Matching Switch 精準路由事件
			switch (event) {
			case ProjectEvent.ProjectCreated created -> viewUpdater.createProject(created);
			case ProjectEvent.TaskAdded added -> viewUpdater.addTask(tenantId, projectId, added);
			case ProjectEvent.TaskModuleUpdated moduleUpdated -> viewUpdater.updateTaskModule(moduleUpdated);
			case ProjectEvent.TaskNameUpdated nameUpdated -> viewUpdater.updateTaskName(nameUpdated);
			case ProjectEvent.TaskScheduleUpdated scheduleUpdated -> viewUpdater.updateTaskSchedule(scheduleUpdated);
			case ProjectEvent.TaskProgressUpdated progressUpdated -> viewUpdater.updateTaskProgress(progressUpdated);
			case ProjectEvent.TaskDependenciesUpdated dependenciesUpdated ->
				viewUpdater.updateTaskDependencies(dependenciesUpdated);
			case ProjectEvent.TaskPersonnelUpdated personnelUpdated ->
				viewUpdater.updateTaskPersonnel(personnelUpdated);
			case ProjectEvent.TaskTypeUpdated typeUpdated -> viewUpdater.updateTaskType(typeUpdated);

			case ProjectEvent.TaskDependencyAdded deprecated -> {
				// 理論上這個事件會被 Upcaster 攔截，加上此 case 滿足 exhaustive 檢查
				log.warn("⚠️ [Projection] 收到預期外的廢棄事件，請檢查 Upcaster 設定: [{}]", deprecated.taskId());
			}
			}
		} catch (Exception ex) {
			log.error("💥 [Projection] 專案視圖處理事件失敗，Offset 暫停前進。事件詳情: {}", event, ex);
			throw new RuntimeException("Projection Pipeline 崩潰，請求框架發起重試", ex);
		}

		// 告訴 Pekko Projection 框架這個 Event 已被成功消費
		return CompletableFuture.completedFuture(Done.getInstance());
	}
}