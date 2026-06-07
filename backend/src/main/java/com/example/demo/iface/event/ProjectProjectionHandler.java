package com.example.demo.iface.event;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.apache.pekko.Done;
import org.apache.pekko.projection.eventsourced.EventEnvelope;
import org.apache.pekko.projection.javadsl.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.application.domain.project.event.ProjectEvent;
import com.example.demo.application.domain.project.event.ProjectEvent.ProjectCreated;
import com.example.demo.application.domain.project.event.ProjectEvent.TaskAdded;
import com.example.demo.application.domain.project.event.ProjectEvent.TaskDependenciesUpdated;
import com.example.demo.application.domain.project.event.ProjectEvent.TaskDependencyAdded;
import com.example.demo.application.domain.project.event.ProjectEvent.TaskModuleUpdated;
import com.example.demo.application.domain.project.event.ProjectEvent.TaskNameUpdated;
import com.example.demo.application.domain.project.event.ProjectEvent.TaskPersonnelUpdated;
import com.example.demo.application.domain.project.event.ProjectEvent.TaskProgressUpdated;
import com.example.demo.application.domain.project.event.ProjectEvent.TaskScheduleUpdated;
import com.example.demo.application.domain.project.event.ProjectEvent.TaskTypeUpdated;
import com.example.demo.infra.persistence.ProjectViewRepository;
import com.example.demo.infra.persistence.TaskViewRepository;
import com.example.demo.infra.projection.ProjectView;
import com.example.demo.infra.projection.TaskView;

/**
 * 專案與任務唯讀端投影處理器 (CQRS Projection Handler)
 *
 * <pre>
 * <b>架構定位與職責：</b>
 * <ul>
 * <li><b>讀寫分離 (CQRS Side)：</b> 隸屬於查詢端 (Read Side)。負責監聽來自技術外殼持久化 (Cassandra Journal) 的歷史事実事件。</li>
 * <li><b>最終一致性 (Eventual Consistency)：</b> 將事件中的增量變更，即時轉化並扁平化投影 (Project) 到 PostgreSQL 唯讀視圖表中。</li>
 * <li><b>技術防禦與優化：</b> 
 * 1. <i>多租戶動態解析：</i> 透過 Pekko Envelope 封套自帶的唯一邊界識別碼，動態反向拆解租戶 ID 與專案 ID，保持事件本體輕量化。
 * 2. <i>髒檢查刷新機制：</i> 針對使用元素映射或轉換器的集合欄位 (如 dependencies)，更新時重新建立集合引用，以強迫 Hibernate 偵測狀態改變並觸發 SQL UPDATE。
 * </li>
 * </ul>
 * </pre>
 */
@Component
public class ProjectProjectionHandler extends Handler<EventEnvelope<ProjectEvent>> {

	private final Logger log = LoggerFactory.getLogger(ProjectProjectionHandler.class);

	private final ProjectViewRepository projectRepository;
	private final TaskViewRepository taskRepository;

	/**
	 * 建構子注入讀取端專用的 JPA 儲存庫。
	 */
	public ProjectProjectionHandler(ProjectViewRepository projectRepository, TaskViewRepository taskRepository) {
		this.projectRepository = projectRepository;
		this.taskRepository = taskRepository;
	}

	/**
	 * 處理單個事件封套的核心投影邏輯。
	 *
	 * @param envelope Pekko 傳遞的事件封套，內含領域事件與元數據 (Metadata)
	 * @return 異步完成信號 {@link CompletionStage}，內含 {@link Done} 代表此 Offset 已順利消費完畢
	 */
	@Override
	@Transactional // 確保單個事件的投影更新具備資料庫交易原子性 (Atomic Transaction)
	public CompletionStage<Done> process(EventEnvelope<ProjectEvent> envelope) {

		ProjectEvent event = envelope.event();

		// 💡 實務技巧：從 Pekko 信封提取 Entity ID
		// persistenceId 格式為 "Project|tenantA_proj1"
		String persistenceId = envelope.persistenceId();
		String entityId = persistenceId.split("\\|")[1]; // 取得 "tenantA_proj1"

		// 分離出 tenantId 與 projectId，用作多租戶資料邊界隔離
		String[] idParts = entityId.split("_");
		String tenantId = idParts[0]; // 取得 "tenantA"
		String projectId = idParts[1]; // 取得 "proj1"

		// 使用 Java 16+ Pattern Matching Switch 處理所有讀取端事件變更
		switch (event) {
		case ProjectCreated created -> {
			log.info("🪞 [Projection] 建立專案視圖: [{}]", created.projectId());
			ProjectView entity = new ProjectView(created.projectId(), created.tenantId(), created.name());
			projectRepository.save(entity);
		}

		case TaskAdded added -> {
			log.info("🪞 [Projection] 新增任務視圖: [{}], 業務顯示 ID: [{}]", added.taskId(), added.displayId());

			// 🌟 修正點：改用 Lombok Builder 具名賦值，徹底消滅參數錯位與一堆 null 的問題
			TaskView task = TaskView.builder().taskId(added.taskId()).tenantId(tenantId).projectId(projectId)
					.displayId(added.displayId()).name(added.taskName()).progress(0).dependencies(new HashSet<>())
					.createdAt(java.time.Instant.now())
					// 💡 其餘像是 startDate, endDate, taskType, assigneeId 等欄位，
					// 只要沒寫出來，Builder 就會自動幫你預設為 null，不用再手動塞 null 了！
					.build();

			taskRepository.save(task);
		}
		// 🌟 新增：處理任務模組 (Epic) 更新
		case TaskModuleUpdated moduleUpdated -> {
			log.info("🪞 [Projection] 更新任務模組: [{}] 新模組: [{}]", moduleUpdated.taskId(), moduleUpdated.module());
			taskRepository.findById(moduleUpdated.taskId()).ifPresent(task -> {
				task.setModule(moduleUpdated.module());
				taskRepository.save(task);
			});
		}
		// 在 ProjectProjectionHandler 的 switch(event) 中新增
		case TaskNameUpdated nameUpdated -> {
			log.info("🪞 [Projection] 更新任務名稱: [{}] 新名稱: [{}]", nameUpdated.taskId(), nameUpdated.name());
			taskRepository.findById(nameUpdated.taskId()).ifPresent(task -> {
				task.setName(nameUpdated.name());
				taskRepository.save(task);
			});
		}

		case TaskScheduleUpdated scheduleUpdated -> {
			log.info("🪞 [Projection] 更新任務時程: [{}]", scheduleUpdated.taskId());
			taskRepository.findById(scheduleUpdated.taskId()).ifPresent(task -> {
				task.setStartDate(scheduleUpdated.startDate());
				task.setEndDate(scheduleUpdated.endDate());
				taskRepository.save(task); // 觸發 JPA 狀態快照更新
			});
		}

		case TaskProgressUpdated progressUpdated -> {
			log.info("🪞 [Projection] 更新任務進度: [{}] 進度: {}%", progressUpdated.taskId(), progressUpdated.progress());
			taskRepository.findById(progressUpdated.taskId()).ifPresent(task -> {
				task.setProgress(progressUpdated.progress());
				taskRepository.save(task);
			});
		}

		// ==========================================
		// 🌟 更新：處理任務相依性 (全量替換)
		// ==========================================
		case TaskDependenciesUpdated dependenciesUpdated -> {
			log.info("🪞 [Projection] 更新任務相依性: [{}] 依賴數量: [{}]", dependenciesUpdated.taskId(),
					dependenciesUpdated.dependencies().size());

			taskRepository.findById(dependenciesUpdated.taskId()).ifPresent(task -> {
				// 🌟 關鍵優化不變：依然需要重新 new 出一個 HashSet
				// 將從 Event 傳來的新 Set 整個包進去，強迫 Hibernate 發現「集合的記憶體參考變了」
				// 這樣 Hibernate 就會自動幫我們執行：先 DELETE 舊的關聯，再 INSERT 新的關聯
				Set<String> newDependencies = new HashSet<>(dependenciesUpdated.dependencies());

				task.setDependencies(newDependencies);
				taskRepository.save(task);
			});
		}

		case TaskPersonnelUpdated personnelUpdated -> {
			log.info("🪞 [Projection] 更新任務人員指派: [{}] 執行者: [{}], 審查者: [{}]", personnelUpdated.taskId(),
					personnelUpdated.assigneeId(), personnelUpdated.reviewerId());
			taskRepository.findById(personnelUpdated.taskId()).ifPresent(task -> {
				// 配合寫入端的「部分更新」原則，雖然領域聚合根本身在記憶體做過狀態融合，
				// 唯讀投影端此處只需「無腦同步」接收到的事實最終事件狀態即可，不需再做重複驗證
				task.setAssigneeId(personnelUpdated.assigneeId());
				task.setReviewerId(personnelUpdated.reviewerId());
				taskRepository.save(task);
			});
		}

		case TaskTypeUpdated typeUpdated -> {
			log.info("🪞 [Projection] 更新任務類型: [{}] 種類: [{}]", typeUpdated.taskId(), typeUpdated.taskType());
			taskRepository.findById(typeUpdated.taskId()).ifPresent(task -> {
				task.setTaskType(typeUpdated.taskType());
				taskRepository.save(task);
			});
		}
		case TaskDependencyAdded deprecated -> {
			// 理論上這個事件在從 DB 讀出來的瞬間，就已經被 ProjectEventUpcaster 攔截並升級了。
			// 所以程式在執行時「絕對不會」走到這裡。
			// 加上這個 case 純粹是為了滿足 Java 17+ 對 sealed interface 的 exhaustive (窮舉) 編譯檢查。
			log.warn("⚠️ [Projection] 收到預期外的廢棄事件，請檢查 Upcaster 設定: [{}]", deprecated.taskId());
		}
		}

		// 告訴 Pekko Projection 框架這個 Event 已被成功消費，可以將資料庫 Offset 書籤向前推进
		return CompletableFuture.completedFuture(Done.getInstance());
	}
}