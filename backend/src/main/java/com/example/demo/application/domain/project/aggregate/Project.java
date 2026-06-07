package com.example.demo.application.domain.project.aggregate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.example.demo.application.domain.project.aggregate.vo.Task;
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
import com.example.demo.application.shared.exception.DomainException;

// ==========================================
// 專案聚合根 (Aggregate Root)
// ==========================================

/**
 * 專案領域聚合根 (Pure Domain Aggregate Root)
 *
 * <pre>
 * <b>架構設計核心：</b>
 * <ul>
 * <li><b>純粹性 (Purity)：</b> 內部絕對沒有任何 Pekko/Akka/Spring 的框架依賴，也沒有外部 Command 介面。</li>
 * <li><b>無副作用 (Side-effect free)：</b> 業務方法只負責「校驗規則」並「產出事件 (Yield Events)」，絕對不修改自身狀態。</li>
 * <li><b>不可變性 (Immutability)：</b> 實體狀態的演進完全仰賴 {@code
 * apply
 * } 方法產生全新的物件副本，完美貼合 Event Sourcing 鐵則。</li>
 * </ul>
 * </pre>
 *
 * @param tenantId     租戶識別碼 (多租戶資料隔離邊界)
 * @param projectId    專案唯一識別碼 (聚合根 ID)
 * @param projectCode  專案代號 (例如: WPG, API)
 * @param projectName  專案名稱
 * @param taskSequence 任務流水號狀態 (無鎖取號核心)
 * @param tasks        專案轄下之任務清單 (Entity 集合)
 */
public record Project(String tenantId, String projectId, String projectCode, String projectName, int taskSequence,
		Map<String, Task> tasks) {

	/**
	 * 建立聚合根的初始空狀態 (Zero State)。
	 * <p>
	 * 專供 Event Sourcing 框架在重播 (Replay) 歷史事件前，作為最初始的基底狀態使用。
	 * </p>
	 *
	 * @return 全空的 Project 實例
	 */
	public static Project empty() {
		// 初始狀態下，流水號從 0 開始
		return new Project(null, null, null, null, 0, new HashMap<>());
	}

	// ==========================================
	// 1. 靜態工廠方法：負責「無中生有」的領域防護
	// ==========================================

	/**
	 * 初始化專案 (創世操作)。
	 *
	 * @param tenantId    租戶識別碼
	 * @param projectId   系統生成的專案唯一識別碼
	 * @param projectCode 專案代號 (用於任務取號)
	 * @param projectName 專案名稱
	 * @return 包含 {@link ProjectCreated} 事件的列表
	 * @throws DomainException 若專案名稱或代號為空時拋出
	 */
	public static List<ProjectEvent> initialize(String tenantId, String projectId, String projectCode,
			String projectName) {
		if (projectName == null || projectName.trim().isEmpty()) {
			throw new DomainException("專案名稱不能為空");
		}
		if (projectCode == null || projectCode.trim().isEmpty()) {
			throw new DomainException("專案代號不能為空");
		}
		return List.of(new ProjectCreated(tenantId, projectId, projectCode, projectName));
	}

	// ==========================================
	// 2. 實體方法 (Command Handlers)：負責對「已存在實體」的操作防護
	// ==========================================

	/**
	 * 在專案中新增一筆任務。
	 *
	 * @param taskId   系統生成的任務唯一識別碼 (UUID)
	 * @param taskName 任務名稱
	 * @return 包含 {@link TaskAdded} 事件的列表
	 */
	public List<ProjectEvent> addTask(String taskId, String taskName) {
		if (this.projectName == null) {
			throw new DomainException("專案尚未建立，無法新增任務");
		}
		if (this.tasks.containsKey(taskId)) {
			throw new DomainException("任務 ID 已存在，請勿重複新增");
		}
		if (taskName == null || taskName.trim().isEmpty()) {
			throw new DomainException("任務名稱不能為空");
		}

		// 🌟 核心取號機制：專案代號 + "-" + (當前流水號 + 1)
		// 保證並發安全，絕對不會重複
		String generatedDisplayId = this.projectCode + "-" + (this.taskSequence + 1);

		return List.of(new TaskAdded(taskId, generatedDisplayId, taskName));
	}

	/**
	 * 更新任務的開始與結束時程。
	 */
	public List<ProjectEvent> updateTaskSchedule(String taskId, LocalDate startDate, LocalDate endDate) {
		if (!this.tasks.containsKey(taskId)) {
			throw new DomainException("找不到任務");
		}

		Task currentTask = this.tasks.get(taskId);
		LocalDate newStartDate = startDate != null ? startDate : currentTask.startDate();
		LocalDate newEndDate = endDate != null ? endDate : currentTask.endDate();

		if (newStartDate != null && newEndDate != null && newStartDate.isAfter(newEndDate)) {
			throw new DomainException("開始日期不能晚於結束日期");
		}

		if (Objects.equals(newStartDate, currentTask.startDate())
				&& Objects.equals(newEndDate, currentTask.endDate())) {
			return List.of();
		}

		return List.of(new TaskScheduleUpdated(taskId, newStartDate, newEndDate));
	}

	public List<ProjectEvent> updateTaskName(String taskId, String name) {
		// 這裡進行業務規則校驗 (例如檢查任務是否存在)
		if (this.findTask(taskId).isEmpty()) {
			throw new DomainException("任務不存在，無法更新名稱");
		}
		// 回傳領域事件
		return List.of(new ProjectEvent.TaskNameUpdated(taskId, name));
	}

	/**
	 * 更新任務的完成進度。
	 */
	public List<ProjectEvent> updateTaskProgress(String taskId, int progress) {
		if (!this.tasks.containsKey(taskId)) {
			throw new DomainException("找不到任務");
		}
		if (progress < 0 || progress > 100) {
			throw new DomainException("進度必須介於 0 到 100 之間");
		}
		return List.of(new TaskProgressUpdated(taskId, progress));
	}

	/**
	 * 更新任務間的先後相依性 (全量替換)。
	 */
	public List<ProjectEvent> updateTaskDependencies(String taskId, Set<String> dependencies) {
		if (!this.tasks.containsKey(taskId)) {
			throw new DomainException("任務不存在，無法更新相依性");
		}

		Set<String> newDependencies = dependencies == null ? new HashSet<>() : new HashSet<>(dependencies);

		if (newDependencies.contains(taskId)) {
			throw new DomainException("任務不能依賴自己");
		}

		for (String depId : newDependencies) {
			if (!this.tasks.containsKey(depId)) {
				throw new DomainException("依賴的目標任務不存在: " + depId);
			}
		}

		Task currentTask = this.tasks.get(taskId);

		if (Objects.equals(newDependencies, currentTask.dependencies())) {
			return List.of();
		}

		return List.of(new TaskDependenciesUpdated(taskId, newDependencies));
	}

	/**
	 * 更新任務的人員指派 (支援部分更新與清空)。
	 */
	public List<ProjectEvent> updateTaskPersonnel(String taskId, String assigneeId, String reviewerId) {
		if (!this.tasks.containsKey(taskId)) {
			throw new DomainException("找不到任務");
		}

		Task currentTask = this.tasks.get(taskId);

		String newAssignee = assigneeId != null ? assigneeId : currentTask.assigneeId();
		String newReviewer = reviewerId != null ? reviewerId : currentTask.reviewerId();

		if (newAssignee != null && !newAssignee.trim().isEmpty() && newAssignee.equals(newReviewer)) {
			throw new DomainException("執行者與審查者不能是同一人");
		}

		if (Objects.equals(newAssignee, currentTask.assigneeId())
				&& Objects.equals(newReviewer, currentTask.reviewerId())) {
			return List.of();
		}

		return List.of(new TaskPersonnelUpdated(taskId, newAssignee, newReviewer));
	}

	/**
	 * 更新任務類型 (標籤分類)。
	 */
	public List<ProjectEvent> updateTaskType(String taskId, String taskType) {
		if (!this.tasks.containsKey(taskId)) {
			throw new DomainException("找不到任務");
		}

		Task currentTask = this.tasks.get(taskId);

		if (Objects.equals(taskType, currentTask.taskType())) {
			return List.of();
		}

		return List.of(new TaskTypeUpdated(taskId, taskType));
	}

	// ==========================================
	// 3. 狀態演進 (Event Sourcing State Applicator)
	// ==========================================

	/**
	 * 狀態推演引擎 (State Mutator)。
	 * <p>
	 * 根據接收到的歷史事件 (Event)，產生並回傳一個「全新」的專案狀態副本 (Copy-on-Write)。 這是 Event Sourcing
	 * 的核心，確保聚合根維持絕對的不可變性 (Immutability)。
	 * </p>
	 */
	public Project apply(ProjectEvent event) {

		// 🌟 使用 Java Pattern Matching Switch
		// 好處：只要 ProjectEvent 是 sealed interface，若未來新增事件卻忘了寫 case，編譯器會直接報錯阻擋！
		return switch (event) {

		case ProjectCreated e ->
			// 【創世事件】：專案建立時，賦予基本資料，流水號歸 0，準備一個乾淨的任務 Map
			new Project(e.tenantId(), e.projectId(), e.projectCode(), e.name(), 0, new HashMap<>());

		case TaskAdded e -> {
			// 【新增任務】：建立一個全空的任務實體。
			// 💡 注意最後一個參數 null 代表預設的 module 為未分類
			Task newTask = new Task(e.taskId(), e.displayId(), e.taskName(), null, null, 0, new HashSet<>(), null, null,
					null, null);

			// 🌟 核心演進：唯有新增任務時，必須將專案的 taskSequence 推進 +1
			yield copyWithTaskAndSequence(newTask, this.taskSequence + 1);
		}

		case TaskNameUpdated e -> {
			Task t = this.tasks.get(e.taskId());
			// 【更新名稱】：複製所有舊屬性，唯獨替換 name
			Task updated = new Task(t.taskId(), t.displayId(), e.name(), t.startDate(), t.endDate(), t.progress(),
					t.dependencies(), t.taskType(), t.assigneeId(), t.reviewerId(), t.module());

			// 🌟 狀態演進：一般屬性更新不影響取號邏輯，直接繼承當前的 taskSequence 即可
			yield copyWithTaskAndSequence(updated, this.taskSequence);
		}

		case TaskModuleUpdated e -> {
			Task t = this.tasks.get(e.taskId());
			// 【更新模組/Epic】：複製所有舊屬性，唯獨替換 module
			Task updated = new Task(t.taskId(), t.displayId(), t.name(), t.startDate(), t.endDate(), t.progress(),
					t.dependencies(), t.taskType(), t.assigneeId(), t.reviewerId(), e.module());
			yield copyWithTaskAndSequence(updated, this.taskSequence);
		}

		case TaskScheduleUpdated e -> {
			Task t = this.tasks.get(e.taskId());
			// 【更新時程】：複製所有舊屬性，唯獨替換 startDate 與 endDate
			Task updated = new Task(t.taskId(), t.displayId(), t.name(), e.startDate(), e.endDate(), t.progress(),
					t.dependencies(), t.taskType(), t.assigneeId(), t.reviewerId(), t.module());
			yield copyWithTaskAndSequence(updated, this.taskSequence);
		}

		case TaskProgressUpdated e -> {
			Task t = this.tasks.get(e.taskId());
			// 【更新進度】：複製所有舊屬性，唯獨替換 progress
			Task updated = new Task(t.taskId(), t.displayId(), t.name(), t.startDate(), t.endDate(), e.progress(),
					t.dependencies(), t.taskType(), t.assigneeId(), t.reviewerId(), t.module());
			yield copyWithTaskAndSequence(updated, this.taskSequence);
		}

		case TaskDependenciesUpdated e -> {
			Task t = this.tasks.get(e.taskId());
			// 【更新相依性】：複製所有舊屬性，唯獨替換 dependencies 集合
			Task updated = new Task(t.taskId(), t.displayId(), t.name(), t.startDate(), t.endDate(), t.progress(),
					e.dependencies(), t.taskType(), t.assigneeId(), t.reviewerId(), t.module());
			yield copyWithTaskAndSequence(updated, this.taskSequence);
		}

		case TaskPersonnelUpdated e -> {
			Task t = this.tasks.get(e.taskId());
			// 【更新人員】：複製所有舊屬性，唯獨替換 assigneeId 與 reviewerId
			Task updated = new Task(t.taskId(), t.displayId(), t.name(), t.startDate(), t.endDate(), t.progress(),
					t.dependencies(), t.taskType(), e.assigneeId(), e.reviewerId(), t.module());
			yield copyWithTaskAndSequence(updated, this.taskSequence);
		}

		case TaskTypeUpdated e -> {
			Task t = this.tasks.get(e.taskId());
			// 【更新類型】：複製所有舊屬性，唯獨替換 taskType
			Task updated = new Task(t.taskId(), t.displayId(), t.name(), t.startDate(), t.endDate(), t.progress(),
					t.dependencies(), e.taskType(), t.assigneeId(), t.reviewerId(), t.module());
			yield copyWithTaskAndSequence(updated, this.taskSequence);
		}

		case TaskDependencyAdded deprecated -> {
			// 處理被廢棄的歷史事件 (為了相容舊資料重播)
			// 實際運行中通常會被 Upcaster 攔截升級，但這裡保留 default fallback
			yield this;
		}
		};
	}
	// ==========================================
	// 4. 輔助方法：維持不可變性 (Immutability)
	// ==========================================

	/**
	 * 產生包含更新後任務與流水號的新專案實體副本。
	 *
	 * @param task        更新過狀態的任務物件
	 * @param newSequence 更新後的任務流水號
	 * @return 全新的 Project 物件
	 */
	private Project copyWithTaskAndSequence(Task task, int newSequence) {
		Map<String, Task> newTasks = new HashMap<>(this.tasks);
		newTasks.put(task.taskId(), task);
		// 🌟 拷貝時帶上最新的 taskSequence
		return new Project(this.tenantId, this.projectId, this.projectCode, this.projectName, newSequence, newTasks);
	}

	/**
	 * 更新任務所屬的模組 (Epic / 大功能)。
	 * 
	 * @param taskId 任務 ID
	 * @param module 模組名稱 (允許為 null 或空白，代表歸類於未分類)
	 */
	public List<ProjectEvent> updateTaskModule(String taskId, String module) {
		Task currentTask = findTask(taskId).orElseThrow(() -> new DomainException("找不到任務"));

		// 等冪性檢查：如果模組名稱沒變，就不產生事件
		if (Objects.equals(module, currentTask.module())) {
			return List.of();
		}

		return List.of(new ProjectEvent.TaskModuleUpdated(taskId, module));
	}

	/**
	 * 私有輔助方法：在聚合根內部尋找任務
	 */
	private Optional<Task> findTask(String taskId) {
		return Optional.ofNullable(this.tasks.get(taskId));
	}
}