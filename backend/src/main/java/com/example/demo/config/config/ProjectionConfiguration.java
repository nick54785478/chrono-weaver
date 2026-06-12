package com.example.demo.config.config;

import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.persistence.cassandra.query.javadsl.CassandraReadJournal;
import org.apache.pekko.persistence.query.Offset;
import org.apache.pekko.projection.Projection;
import org.apache.pekko.projection.ProjectionBehavior;
import org.apache.pekko.projection.ProjectionId;
import org.apache.pekko.projection.cassandra.javadsl.CassandraProjection;
import org.apache.pekko.projection.eventsourced.EventEnvelope;
import org.apache.pekko.projection.eventsourced.javadsl.EventSourcedProvider;
import org.apache.pekko.projection.javadsl.SourceProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.demo.application.domain.project.event.ProjectEvent;
import com.example.demo.application.domain.team.event.ProjectTeamEvent;
import com.example.demo.iface.event.ProjectCreationSagaHandler;
import com.example.demo.iface.event.ProjectProjectionHandler;
import com.example.demo.iface.event.ProjectTeamProjectionHandler;
import com.example.demo.infra.actor.ProjectEventSourcedActor;
import com.example.demo.infra.actor.ProjectTeamEventSourcedActor;

/**
 * <h3>基礎設施層：CQRS 讀取端投影架構配置核心</h3>
 * <p>
 * 本類別負責在 Spring Boot 啟動時，架設並啟動所有的 **Pekko Projection (事件投影管線)**。
 * </p>
 * <p>
 * <b>架構定位與運作機制：</b><br>
 * 1. <b>技術外殼 (Imperative Shell)：</b> 本配置充當 Spring 容器與 Pekko 響應式叢集之間的膠水層。<br>
 * 2. <b>事件分流 (Event Streaming)：</b> 透過訂閱寫入端 (Write Side) 存入 Cassandra Journal
 * 的領域事件標籤 (Tags)， 將資料非同步、響應式地串流至讀取端 (Read Side) 的各個 Handler。<br>
 * 3. <b>最終一致性防禦：</b> 採用 {@code atLeastOnce} (至少一次) 語意配合分散式 Offset (偏移量) 追蹤，
 * 確保即使在 Pod 崩潰或網路震盪的極端併發環境下，讀取端的 PostgreSQL 視圖與 Saga 狀態也絕對不丟失事件。
 * </p>
 *
 * @author Chrono Weaver Architecture Team
 */
@Configuration
public class ProjectionConfiguration {

	/**
	 * 在 Spring Boot 應用程式啟動完成後，自動架設並啟動全域的背景投影精靈 (Projection Daemons)。
	 * 
	 * <pre>
	 * <b>並行流設計 (Asymmetric Parallel Streaming)：</b><br>
	 * 系統在此處切分了兩條高獨立性的「主水管 (Source Providers)」，並延伸出三個背景 Actor 守護進程，
	 * 確保「專案視圖更新」、「團隊成員變更」與「分散式 Saga 交易管理」在硬體層級完全平行處理，互不阻塞。
	 * </pre>
	 *
	 * @param system             Pekko 核心 Actor 系統，提供執行環境與守護程序宿主
	 * @param projectViewHandler 專責將專案與任務事件投影至 PostgreSQL Read Model 的處理器
	 * @param sagaHandler        專責攔截專案建立事件、驅動跨聚合分散式事務的 Saga 協調器
	 * @param teamViewHandler    專責處理團隊成員權限、多租戶隔離名單投影的處理器
	 * @return CommandLineRunner Spring Boot 啟動回呼勾子
	 */
	@Bean
	public CommandLineRunner startProjections(ActorSystem<?> system, ProjectProjectionHandler projectViewHandler,
			ProjectCreationSagaHandler sagaHandler, ProjectTeamProjectionHandler teamViewHandler) {

		return args -> {

			// =========================================================================
			// 第一條水管：監聽 Project 聚合事件串流 (Tag: "ProjectEvent")
			// =========================================================================
			// 從 Cassandra 讀取日誌 (Event Journal) 中，根據指定的 Tag 拉取連續不間斷的事件信封 (Event Envelopes)
			SourceProvider<Offset, EventEnvelope<ProjectEvent>> projectSourceProvider = EventSourcedProvider
					.eventsByTag(system, CassandraReadJournal.Identifier(), ProjectEventSourcedActor.TAG);

			// ── 核心分流 A：專案與 WBS 任務 Read Model 投影 ──
			// 語意：atLeastOnce 保證事件至少處理一次。當 Handler 執行成功，Pekko 會自動在 Cassandra 中推進該
			// ProjectionId 的 Offset。
			Projection<EventEnvelope<ProjectEvent>> viewProjection = CassandraProjection.atLeastOnce(
					ProjectionId.of("ProjectProjection", "ProjectView"), projectSourceProvider,
					() -> projectViewHandler);

			// 將定義好的管線包裝成一個系統級 Actor (Daemon) 丟入背景運行，命名為 "ProjectViewDaemon"
			system.systemActorOf(ProjectionBehavior.create(viewProjection), "ProjectViewDaemon", Props.empty());

			// ── 核心分流 B：專案建立分散式事務協調器 (Saga Pattern) ──
			// 共享同一個 SourceProvider，但擁有獨立的 ProjectionId。這意味著檢視表掛掉時，Saga 依然能平行推進，互不干擾！
			Projection<EventEnvelope<ProjectEvent>> sagaProjection = CassandraProjection.atLeastOnce(
					ProjectionId.of("ProjectSagaProjection", "ProjectCreationSaga"), projectSourceProvider,
					() -> sagaHandler);

			// 啟動第二個背景精靈，專職守護分散式事務狀態機
			system.systemActorOf(ProjectionBehavior.create(sagaProjection), "ProjectSagaDaemon", Props.empty());

			// =========================================================================
			// 第二條水管：監聽 ProjectTeam 聚合事件串流 (Tag: "ProjectTeamEvent")
			// =========================================================================
			// 物理隔離：團隊成員的操作頻率與 WBS 共編不同，走獨立的主水管，確保並發隔離，徹底消除技術邊界破壞
			SourceProvider<Offset, EventEnvelope<ProjectTeamEvent>> teamSourceProvider = EventSourcedProvider
					.eventsByTag(system, CassandraReadJournal.Identifier(), ProjectTeamEventSourcedActor.TAG);

			// ── 核心分流 C：團隊成員權限檢視表投影 ──
			Projection<EventEnvelope<ProjectTeamEvent>> teamViewProjection = CassandraProjection.atLeastOnce(
					ProjectionId.of("ProjectTeamProjection", "TeamMemberView"), teamSourceProvider,
					() -> teamViewHandler);

			// 啟動第三個背景精靈！
			system.systemActorOf(ProjectionBehavior.create(teamViewProjection), "TeamViewDaemon", Props.empty());

			System.out.println("[CQRS Pipeline] 所有背景 Projection 精靈 (ProjectView, Saga, TeamView) 已全數成功啟動並常駐記憶體！");
		};
	}
}