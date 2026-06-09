package com.example.demo.application.service;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.demo.application.domain.team.aggregate.vo.Role;
import com.example.demo.application.shared.command.ProjectTeamCommand;
import com.example.demo.application.shared.response.ProjectResponse;
import com.example.demo.infra.actor.ProjectTeamEventSourcedActor;

/**
 * 專案團隊指令服務 (Application Command Service)
 * <p>
 * 架構定位： 這是外部世界 (REST Controller) 進入領域模型 (Domain / Actor) 的閘口。 負責將 DTO 轉換為 Pekko
 * Command，並透過 Cluster Sharding 將訊息精準路由到對應的 Actor。
 * </p>
 */
@Service
public class ProjectTeamCommandService {

	private static final Logger log = LoggerFactory.getLogger(ProjectTeamCommandService.class);

	private final ClusterSharding sharding;

	// 預設的 Ask Pattern 等待超時時間。
	// 因為 Actor 在記憶體中處理極快，通常毫秒級就能回覆，設定 5 秒作為網路與資料庫的寬容值。
	private final Duration askTimeout = Duration.ofSeconds(5);

	public ProjectTeamCommandService(ActorSystem<?> system) {
		this.sharding = ClusterSharding.get(system);
	}

	/**
	 * 取得特定 ProjectTeam 聚合根的 Actor 參照
	 */
	private EntityRef<ProjectTeamCommand> getTeamActor(String tenantId, String projectId) {
		return sharding.entityRefFor(ProjectTeamEventSourcedActor.ENTITY_TYPE_KEY, tenantId + "_" + projectId);
	}

	// ==========================================
	// 🌟 業務操作 (Command Dispatchers)
	// ==========================================

	/**
	 * 加入新成員至專案團隊
	 */
	public CompletionStage<ProjectResponse> addMember(String tenantId, String projectId, String userId, String role) {
		log.info("[Command Service] 請求將使用者 {} 加入專案團隊 {}，角色: {}", userId, projectId, role);
		EntityRef<ProjectTeamCommand> teamActor = getTeamActor(tenantId, projectId);

		// 使用 Ask Pattern：傳遞 replyTo 讓 Actor 可以把結果回傳回來
		return teamActor.ask(replyTo -> new ProjectTeamCommand.AddMember(userId, Role.valueOf(role), replyTo),
				askTimeout);
	}

	/**
	 * 變更團隊成員的角色
	 */
	public CompletionStage<ProjectResponse> changeRole(String tenantId, String projectId, String userId, String newRole) {
		log.info("[Command Service] 請求變更專案團隊 {} 中使用者 {} 的角色為: {}", projectId, userId, newRole);
		EntityRef<ProjectTeamCommand> teamActor = getTeamActor(tenantId, projectId);

		return teamActor.ask(replyTo -> new ProjectTeamCommand.ChangeRole(userId, Role.valueOf(newRole), replyTo), askTimeout);
	}

	/**
	 * 將成員移出專案團隊
	 */
	public CompletionStage<ProjectResponse> removeMember(String tenantId, String projectId, String userId) {
		log.info("[Command Service] 請求將使用者 {} 移出專案團隊 {}", userId, projectId);
		EntityRef<ProjectTeamCommand> teamActor = getTeamActor(tenantId, projectId);

		return teamActor.ask(replyTo -> new ProjectTeamCommand.RemoveMember(userId, replyTo), askTimeout);
	}

	/*
	 * * 💡 為什麼這裡沒有 initializeTeam() 方法？ 因為我們在前面的架構設計中，已經把「初始化團隊」的職責交給了
	 * ProjectToTeamLinker (Saga) 自動處理。 外部 API 不應該，也不需要手動呼叫初始化，以確保領域邏輯的嚴謹性與一致性。
	 */
}