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

import com.example.demo.application.domain.team.aggregate.ProjectTeam;
import com.example.demo.application.domain.team.event.ProjectTeamEvent;
import com.example.demo.application.shared.command.ProjectTeamCommand;
import com.example.demo.application.shared.exception.DomainException;
import com.example.demo.application.shared.response.ProjectResponse;

/**
 * 基礎設施層：ProjectTeam 的 Event Sourcing 宿主 Actor
 * <p>
 * 專責處理團隊成員併發操作的持久化，與 Project 核心的 Actor 完全隔離， 確保「修改專案名稱」與「增減團隊成員」不會發生鎖定衝突。
 * </p>
 */
public class ProjectTeamEventSourcedActor
		extends EventSourcedBehavior<ProjectTeamCommand, ProjectTeamEvent, ProjectTeam> {

	// ==========================================
	// 1. 基礎設定與生命週期
	// ==========================================
	public static final String TAG = "ProjectTeamEvent";
	

	// 🌟 核心設計：使用獨立的 EntityTypeKey，讓叢集知道這是 Team，不是 Project
	public static final EntityTypeKey<ProjectTeamCommand> ENTITY_TYPE_KEY = EntityTypeKey
			.create(ProjectTeamCommand.class, "ProjectTeam");

	private ProjectTeamEventSourcedActor(PersistenceId persistenceId) {
		super(persistenceId);
	}

	/**
	 * 建立 Actor 行為。 💡 注意：這裡的 projectId 傳入的是隸屬專案的 ID，確保 1:1 的對應關係。
	 */
	public static Behavior<ProjectTeamCommand> create(String tenantId, String projectId) {
		return new ProjectTeamEventSourcedActor(PersistenceId.of(ENTITY_TYPE_KEY.name(), tenantId + "_" + projectId));
	}

	@Override
    public Set<String> tagsFor(ProjectTeamEvent event) {
        return Set.of(TAG);
    }

	@Override
	public ProjectTeam emptyState() {
		return ProjectTeam.empty(); // 從 Domain 取得空狀態
	}

	// ==========================================
	// 2. Command 路由器
	// ==========================================
	@Override
	public CommandHandler<ProjectTeamCommand, ProjectTeamEvent, ProjectTeam> commandHandler() {
		return newCommandHandlerBuilder().forAnyState()
				.onCommand(ProjectTeamCommand.InitializeTeam.class, this::onInitializeTeam)
				.onCommand(ProjectTeamCommand.AddMember.class, this::onAddMember)
				.onCommand(ProjectTeamCommand.ChangeRole.class, this::onChangeRole)
				.onCommand(ProjectTeamCommand.RemoveMember.class, this::onRemoveMember).build();
	}

	// --- Command 拆解實作 ---

	private Effect<ProjectTeamEvent, ProjectTeam> onInitializeTeam(ProjectTeam state,
			ProjectTeamCommand.InitializeTeam cmd) {
		try {
			// 防呆：如果團隊已經初始化過了（例如重試機制觸發），直接回覆成功
			if (state.projectId() != null) {
				return Effect().reply(cmd.replyTo(), new ProjectResponse(true, "團隊已初始化"));
			}

			List<ProjectTeamEvent> events = ProjectTeam.initialize(cmd.tenantId(), cmd.projectId());
			return Effect().persist(events).thenReply(cmd.replyTo(), s -> new ProjectResponse(true, "團隊初始化成功"));
		} catch (DomainException e) {
			return Effect().reply(cmd.replyTo(), new ProjectResponse(false, e.getMessage()));
		}
	}

	private Effect<ProjectTeamEvent, ProjectTeam> onAddMember(ProjectTeam state, ProjectTeamCommand.AddMember cmd) {
		try {
			List<ProjectTeamEvent> events = state.addMember(cmd.userId(), cmd.role());
			return Effect().persist(events).thenReply(cmd.replyTo(), s -> new ProjectResponse(true, "成員加入成功"));
		} catch (DomainException e) {
			return Effect().reply(cmd.replyTo(), new ProjectResponse(false, e.getMessage()));
		}
	}

	private Effect<ProjectTeamEvent, ProjectTeam> onChangeRole(ProjectTeam state, ProjectTeamCommand.ChangeRole cmd) {
		try {
			List<ProjectTeamEvent> events = state.changeRole(cmd.userId(), cmd.newRole());
			return Effect().persist(events).thenReply(cmd.replyTo(), s -> new ProjectResponse(true, "角色變更成功"));
		} catch (DomainException e) {
			return Effect().reply(cmd.replyTo(), new ProjectResponse(false, e.getMessage()));
		}
	}

	private Effect<ProjectTeamEvent, ProjectTeam> onRemoveMember(ProjectTeam state,
			ProjectTeamCommand.RemoveMember cmd) {
		try {
			List<ProjectTeamEvent> events = state.removeMember(cmd.userId());
			return Effect().persist(events).thenReply(cmd.replyTo(), s -> new ProjectResponse(true, "成員移除成功"));
		} catch (DomainException e) {
			return Effect().reply(cmd.replyTo(), new ProjectResponse(false, e.getMessage()));
		}
	}

	// ==========================================
	// 3. Event 路由器
	// ==========================================
	@Override
	public EventHandler<ProjectTeam, ProjectTeamEvent> eventHandler() {
		return newEventHandlerBuilder().forAnyState()
				// 把事件無腦丟給 Domain 的 apply 引擎去推演狀態
				.onAnyEvent((state, event) -> state.apply(event));
	}
}