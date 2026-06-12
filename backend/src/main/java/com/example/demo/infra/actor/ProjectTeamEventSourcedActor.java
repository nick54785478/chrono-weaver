package com.example.demo.infra.actor;

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
 * 基礎設施層：ProjectTeam 的 Event Sourcing 宿主 (Host) Actor
 * 
 * <pre>
 * <b>架構邊界：</b> 本元件專責處理「團隊成員」併發操作的持久化。將 Team 與 Project 拆分為兩個獨立的 Actor (聚合根)，
 * 是為了縮小鎖定範圍 (Locking Scope)，確保「修改專案名稱」與「增減團隊成員」這兩種不同頻率的操作
 * 能完全平行處理，不會發生鎖定衝突與效能瓶頸。
 * </pre>
 */
public class ProjectTeamEventSourcedActor
		extends EventSourcedBehavior<ProjectTeamCommand, ProjectTeamEvent, ProjectTeam> {

	// ==========================================
	// 1. 基礎設定與叢集生命週期 (Cluster Sharding)
	// ==========================================

	/** 定義事件日誌的標籤，供讀取端 (Read Side / Projection) 訂閱使用 */
	public static final String TAG = "ProjectTeamEvent";

	/**
	 * 核心設計：獨立的 EntityTypeKey 告訴 Pekko Cluster 這是一個「團隊實體」，讓叢集能在不同的 Node 上正確路由
	 * Command， 並且與 Project 實體互不干擾。
	 */
	public static final EntityTypeKey<ProjectTeamCommand> ENTITY_TYPE_KEY = EntityTypeKey
			.create(ProjectTeamCommand.class, "ProjectTeam");

	private ProjectTeamEventSourcedActor(PersistenceId persistenceId) {
		super(persistenceId);
	}

	/**
	 * 建立 Actor 行為的工廠方法。
	 * 
	 * @param tenantId  多租戶邊界識別碼
	 * @param projectId 隸屬專案的 ID (維持 1:1 的關聯對應)
	 * @return 定義好的 Actor 行為
	 */
	public static Behavior<ProjectTeamCommand> create(String tenantId, String projectId) {
		// 組合 PersistenceId: "ProjectTeam|tenantId_projectId"
		return new ProjectTeamEventSourcedActor(PersistenceId.of(ENTITY_TYPE_KEY.name(), tenantId + "_" + projectId));
	}

	@Override
	public Set<String> tagsFor(ProjectTeamEvent event) {
		return Set.of(TAG); // 替所有產出的事件貼上標籤，寫入 Cassandra 時供 Projection 撈取
	}

	@Override
	public ProjectTeam emptyState() {
		// 從 Domain (大腦) 取得最初始、尚未發生任何事件的空記憶狀態
		return ProjectTeam.empty();
	}

	// ==========================================
	// 2. Command 路由器 (處理寫入意圖)
	// ==========================================
	@Override
	public CommandHandler<ProjectTeamCommand, ProjectTeamEvent, ProjectTeam> commandHandler() {
		return newCommandHandlerBuilder().forAnyState()
				.onCommand(ProjectTeamCommand.InitializeTeam.class, this::onInitializeTeam)
				.onCommand(ProjectTeamCommand.AddMember.class, this::onAddMember)
				.onCommand(ProjectTeamCommand.ChangeRole.class, this::onChangeRole)
				.onCommand(ProjectTeamCommand.RemoveMember.class, this::onRemoveMember).build();
	}

	// --- Command 拆解與持久化實作 ---

	/**
	 * 處理：初始化團隊
	 */
	private Effect<ProjectTeamEvent, ProjectTeam> onInitializeTeam(ProjectTeam state,
			ProjectTeamCommand.InitializeTeam cmd) {
		try {
			// 💡 等冪性防禦 (Idempotency Guard)：
			// 分散式系統可能會收到重複的 Command (例如網路重試)，若已初始化過則直接回覆成功，不產生新事件
			if (state.projectId() != null) {
				return Effect().reply(cmd.replyTo(), new ProjectResponse(true, "團隊已初始化"));
			}

			// 1. 呼叫純領域邏輯進行校驗並產生「事實 (Events)」
			List<ProjectTeamEvent> events = ProjectTeam.initialize(cmd.tenantId(), cmd.projectId());

			// 2. 將事實寫入 Event Journal，寫入成功後再回覆前端
			return Effect().persist(events).thenReply(cmd.replyTo(), s -> new ProjectResponse(true, "團隊初始化成功"));
		} catch (DomainException e) {
			// 攔截業務規則防禦拋出的例外 (例如參數錯誤)，化為失敗回應
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
	// 3. Event 路由器 (狀態演化)
	// ==========================================
	@Override
	public EventHandler<ProjectTeam, ProjectTeamEvent> eventHandler() {
		return newEventHandlerBuilder().forAnyState()
				// 💡 狀態折疊 (State Folding)：
				// 無論是剛存入資料庫的最新事件，還是節點重啟時從資料庫 Replay 的歷史事件，
				// 都無腦交給 Domain 的 apply 方法去推進/還原狀態。
				.onAnyEvent((state, event) -> state.apply(event));
	}
}