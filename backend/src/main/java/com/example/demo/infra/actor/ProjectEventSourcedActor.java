package com.example.demo.infra.actor;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.javadsl.CommandHandler;
import org.apache.pekko.persistence.typed.javadsl.Effect;
import org.apache.pekko.persistence.typed.javadsl.EventHandler;
import org.apache.pekko.persistence.typed.javadsl.EventSourcedBehavior;

import com.example.demo.application.domain.project.aggregate.Project;
import com.example.demo.application.domain.project.event.ProjectEvent;
import com.example.demo.application.shared.command.ProjectCommand;
import com.example.demo.application.shared.command.ProjectCommand.AddTask;
import com.example.demo.application.shared.command.ProjectCommand.CreateProject;
import com.example.demo.application.shared.command.ProjectCommand.UpdateTaskDependencies;
import com.example.demo.application.shared.command.ProjectCommand.UpdateTaskModule;
import com.example.demo.application.shared.command.ProjectCommand.UpdateTaskName;
import com.example.demo.application.shared.command.ProjectCommand.UpdateTaskPersonnel;
import com.example.demo.application.shared.command.ProjectCommand.UpdateTaskProgress;
import com.example.demo.application.shared.command.ProjectCommand.UpdateTaskSchedule;
import com.example.demo.application.shared.command.ProjectCommand.UpdateTaskType;
import com.example.demo.application.shared.exception.DomainException;
import com.example.demo.application.shared.response.ProjectResponse;

/**
 * 基礎設施層：Pekko Event Sourcing 的宿主 (Host)
 *
 * <pre>
 * <b>架構定位與職責：</b>
 * <ul>
 * <li><b>技術外殼 (Imperative Shell)：</b> 負責處理 Actor 生命週期、分散式路由、事件持久化 (Cassandra) 與回覆前端。</li>
 * <li><b>無業務邏輯：</b> 這裡「沒有」任何業務規則的判斷。它純粹扮演「總機」的角色：收信 (Command) -> 轉交給大腦 (Project Aggregate) -> 將大腦產生的記憶 (Event) 寫入硬碟 -> 回報成功。</li>
 * </ul>
 * </pre>
 */
public class ProjectEventSourcedActor extends EventSourcedBehavior<ProjectCommand, ProjectEvent, Project> {

	// ==========================================
	// 1. 基礎設定與生命週期
	// ==========================================
	public static final String TAG = "ProjectEvent";
	public static final EntityTypeKey<ProjectCommand> ENTITY_TYPE_KEY = EntityTypeKey.create(ProjectCommand.class,
			"Project");

	private ProjectEventSourcedActor(PersistenceId persistenceId) {
		super(persistenceId);
	}

	public static Behavior<ProjectCommand> create(String tenantId, String projectId) {
		// 使用底線 "_" 作為分隔符，避免踩到 Pekko 實體 ID 的管線符號 "|" 地雷
		return new ProjectEventSourcedActor(PersistenceId.of(ENTITY_TYPE_KEY.name(), tenantId + "_" + projectId));
	}

	@Override
	public Set<String> tagsFor(ProjectEvent event) {
		// 為所有 Event 貼上標籤，讓 CQRS 的 Projection 可以透過這個 Tag 抓取事件串流
		return Collections.singleton(TAG);
	}

	@Override
	public Project emptyState() {
		// 從純領域模型取得乾淨的初始狀態
		return Project.empty();
	}

	// ==========================================
	// 2. Command 路由器 (將 Application Command 餵給大腦)
	// ==========================================
	@Override
	public CommandHandler<ProjectCommand, ProjectEvent, Project> commandHandler() {
		return newCommandHandlerBuilder().forAnyState().onCommand(CreateProject.class, this::onCreateProject)
				.onCommand(UpdateTaskName.class, this::onUpdateTaskName).onCommand(AddTask.class, this::onAddTask)
				.onCommand(UpdateTaskSchedule.class, this::onUpdateTaskSchedule)
				.onCommand(UpdateTaskProgress.class, this::onUpdateTaskProgress)
				.onCommand(UpdateTaskDependencies.class, this::onUpdateTaskDependencies)
				.onCommand(UpdateTaskPersonnel.class, this::onUpdateTaskPersonnel)
				.onCommand(UpdateTaskType.class, this::onUpdateTaskType)
				.onCommand(UpdateTaskModule.class, this::onUpdateTaskModule).build();
	}

	// --- 以下為各個 Command 的拆解與持久化實作 ---

	private Effect<ProjectEvent, Project> onCreateProject(Project state, CreateProject cmd) {
		try {
			// 💡 呼叫純領域聚合根的靜態工廠方法進行無中生有的初始化
			List<ProjectEvent> events = Project.initialize(cmd.tenantId(), cmd.projectId(), cmd.projectCode(),
					cmd.name());

			// 持久化大腦產出的 Event，並透過 replyTo 回覆成功
			return Effect().persist(events).thenReply(cmd.replyTo(), s -> new ProjectResponse(true, "專案建立成功"));
		} catch (DomainException e) {
			// 攔截大腦拋出的業務防禦異常，並回覆失敗原因
			return Effect().reply(cmd.replyTo(), new ProjectResponse(false, e.getMessage()));
		}
	}

	private Effect<ProjectEvent, Project> onAddTask(Project state, AddTask cmd) {
		try {
			List<ProjectEvent> events = state.addTask(cmd.taskId(), cmd.taskName());
			return Effect().persist(events).thenReply(cmd.replyTo(), s -> new ProjectResponse(true, "任務新增成功"));
		} catch (DomainException e) {
			return Effect().reply(cmd.replyTo(), new ProjectResponse(false, e.getMessage()));
		}
	}

	private Effect<ProjectEvent, Project> onUpdateTaskName(Project state, UpdateTaskName cmd) {
		try {
			// 1. 呼叫大腦 (Project Aggregate) 的業務方法
			List<ProjectEvent> events = state.updateTaskName(cmd.taskId(), cmd.name());

			// 2. 持久化事件並回覆前端
			return Effect().persist(events).thenReply(cmd.replyTo(), s -> new ProjectResponse(true, "任務名稱更新成功"));
		} catch (DomainException e) {
			// 3. 處理領域層拋出的業務校驗錯誤
			return Effect().reply(cmd.replyTo(), new ProjectResponse(false, e.getMessage()));
		}
	}

	private Effect<ProjectEvent, Project> onUpdateTaskSchedule(Project state, UpdateTaskSchedule cmd) {
		try {
			List<ProjectEvent> events = state.updateTaskSchedule(cmd.taskId(), cmd.startDate(), cmd.endDate());
			return Effect().persist(events).thenReply(cmd.replyTo(), s -> new ProjectResponse(true, "時程更新成功"));
		} catch (DomainException e) {
			return Effect().reply(cmd.replyTo(), new ProjectResponse(false, e.getMessage()));
		}
	}

	private Effect<ProjectEvent, Project> onUpdateTaskProgress(Project state, UpdateTaskProgress cmd) {
		try {
			List<ProjectEvent> events = state.updateTaskProgress(cmd.taskId(), cmd.progress());
			return Effect().persist(events).thenReply(cmd.replyTo(), s -> new ProjectResponse(true, "進度更新成功"));
		} catch (DomainException e) {
			return Effect().reply(cmd.replyTo(), new ProjectResponse(false, e.getMessage()));
		}
	}

	private Effect<ProjectEvent, Project> onUpdateTaskDependencies(Project state, UpdateTaskDependencies cmd) {
		try {
			List<ProjectEvent> events = state.updateTaskDependencies(cmd.taskId(), cmd.dependencies());
			return Effect().persist(events).thenReply(cmd.replyTo(), s -> new ProjectResponse(true, "相依性更新成功"));
		} catch (DomainException e) {
			return Effect().reply(cmd.replyTo(), new ProjectResponse(false, e.getMessage()));
		}
	}

	private Effect<ProjectEvent, Project> onUpdateTaskPersonnel(Project state, UpdateTaskPersonnel cmd) {
		try {
			List<ProjectEvent> events = state.updateTaskPersonnel(cmd.taskId(), cmd.assigneeId(), cmd.reviewerId());
			return Effect().persist(events).thenReply(cmd.replyTo(), s -> new ProjectResponse(true, "人員指派更新成功"));
		} catch (DomainException e) {
			return Effect().reply(cmd.replyTo(), new ProjectResponse(false, e.getMessage()));
		}
	}

	private Effect<ProjectEvent, Project> onUpdateTaskType(Project state, UpdateTaskType cmd) {
		try {
			List<ProjectEvent> events = state.updateTaskType(cmd.taskId(), cmd.taskType());
			return Effect().persist(events).thenReply(cmd.replyTo(), s -> new ProjectResponse(true, "任務類型更新成功"));
		} catch (DomainException e) {
			return Effect().reply(cmd.replyTo(), new ProjectResponse(false, e.getMessage()));
		}
	}

	// ==========================================
	// 🌟 新增：更新模組 (Epic) 的持久化實作
	// ==========================================
	private Effect<ProjectEvent, Project> onUpdateTaskModule(Project state, UpdateTaskModule cmd) {
		try {
			// 呼叫聚合根方法產生事件
			List<ProjectEvent> events = state.updateTaskModule(cmd.taskId(), cmd.module());
			// 持久化並回覆
			return Effect().persist(events).thenReply(cmd.replyTo(), s -> new ProjectResponse(true, "任務所屬模組更新成功"));
		} catch (DomainException e) {
			return Effect().reply(cmd.replyTo(), new ProjectResponse(false, e.getMessage()));
		}
	}

	// ==========================================
	// 3. Event 路由器 (無腦套用，更新肌肉狀態)
	// ==========================================
	@Override
	public EventHandler<Project, ProjectEvent> eventHandler() {
		return newEventHandlerBuilder().forAnyState()
				// 因為我們在 Project.java 裡面已經寫好了 Pattern Matching 來處理所有 Event
				// 所以這裡的實作變得不可思議的乾淨，直接把 Event 丟給大腦的 apply() 方法進行狀態演化即可！
				.onAnyEvent((state, event) -> state.apply(event));
	}
}