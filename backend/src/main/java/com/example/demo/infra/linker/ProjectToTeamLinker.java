package com.example.demo.infra.linker;

import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;
import org.springframework.stereotype.Component;

import com.example.demo.application.domain.project.event.ProjectEvent;
import com.example.demo.application.domain.team.aggregate.vo.Role;
import com.example.demo.application.shared.command.ProjectTeamCommand;
import com.example.demo.infra.actor.ProjectTeamEventSourcedActor;

import lombok.extern.slf4j.Slf4j;

/**
 * 專案建立聯動引擎 (Saga Core / Process Manager)
 * <p>
 * 架構職責：純粹的業務協調者。負責解讀 Project 事件，並驅動 ProjectTeam 的初始化與人員編制。 這裡完全沒有 Pekko
 * Projection 串流的技術細節，極度容易進行單元測試。
 * </p>
 */
@Slf4j
@Component
public class ProjectToTeamLinker {

	private final ClusterSharding sharding;
	private final ActorSystem<?> system;

	public ProjectToTeamLinker(ActorSystem<?> system) {
		this.system = system;
		this.sharding = ClusterSharding.get(system);
	}

	/**
	 * 處理領域事件的核心業務邏輯
	 */
	public void processProjectEvent(ProjectEvent event) {

		// 🌟 使用 Java 21 Pattern Matching 攔截創世事件
		if (event instanceof ProjectEvent.ProjectCreated createdEvent) {

			// 1. 取得目標 ProjectTeam 的 Actor 參照 (1:1 對齊 projectId)
			EntityRef<ProjectTeamCommand> teamActor = sharding.entityRefFor(
					ProjectTeamEventSourcedActor.ENTITY_TYPE_KEY,
					createdEvent.tenantId() + "_" + createdEvent.projectId());

			// 2. 發出初始化團隊的指令 (Fire and Forget，依賴聚合內的等冪性防護)
			teamActor.tell(new ProjectTeamCommand.InitializeTeam(createdEvent.tenantId(), createdEvent.projectId(),
					this.system.ignoreRef()));

			// 3. 發出加入創始人 (PO) 為 OWNER 的指令
			teamActor.tell(
					new ProjectTeamCommand.AddMember(createdEvent.ownerId(), Role.OWNER, this.system.ignoreRef()));

			log.info("[Saga Core] 成功攔截 ProjectCreated，已發出團隊初始化與 PO 加入指令給專案: {}", createdEvent.projectId());
		}
	}
}