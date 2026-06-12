package com.example.demo.config.config;

import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.persistence.cassandra.query.javadsl.CassandraReadJournal;
import org.apache.pekko.persistence.query.Offset;
import org.apache.pekko.projection.ProjectionBehavior;
import org.apache.pekko.projection.ProjectionId;
import org.apache.pekko.projection.cassandra.javadsl.CassandraProjection;
import org.apache.pekko.projection.eventsourced.EventEnvelope;
import org.apache.pekko.projection.eventsourced.javadsl.EventSourcedProvider;
import org.apache.pekko.projection.javadsl.AtLeastOnceProjection;
import org.apache.pekko.projection.javadsl.SourceProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.demo.application.domain.project.event.ProjectEvent;
import com.example.demo.iface.event.ProjectProjectionHandler;
import com.example.demo.infra.actor.ProjectEventSourcedActor;
import com.example.demo.infra.actor.ProjectTeamEventSourcedActor;

@Configuration
public class PekkoConfiguration {

	/**
	 * 1. 啟動 Pekko Actor 系統
	 */
	@Bean(destroyMethod = "terminate")
	public ActorSystem<Void> actorSystem() {
		// 實務上這裡會讀取 application.conf 來設定 Cassandra 與 Cluster 參數
		return ActorSystem.create(Behaviors.empty(), "ChronoWeaverSystem");
	}

	/**
	 * 2. 配置並初始化 Pekko 分散式叢集分片系統。
	 * 
	 * <pre>
	 * <b>架構職責：</b> 本方法負責向 Pekko 叢集宣告 {@link ProjectEventSourcedActor} 與
	 * {@link ProjectTeamEventSourcedActor} 的生命週期生成工廠。
	 * 
	 * 當叢集內任一節點（Pod）收到發往特定專案的 Command 時， Sharding 系統會依據此處註冊的 Key，將訊息精準導向該特定 Actor 的唯一信箱（Mailbox）。
	 * </pre>
	 */
	@Bean
	public ClusterSharding clusterSharding(ActorSystem<Void> system) {
		// 取得當前 ActorSystem 的 Cluster Sharding 控制主體
		ClusterSharding sharding = ClusterSharding.get(system);

		// 1. 註冊專案核心業務 Actor (Project Aggregate)
		sharding.init(Entity.of(ProjectEventSourcedActor.ENTITY_TYPE_KEY, entityContext -> {
			// 從訊息包裹中提取全域唯一的實體識別碼 (格式: tenantId_projectId)
			String entityId = entityContext.getEntityId();

			// ⚠️ 關鍵防禦修正：
			// 捨棄傳統 Akka 預設的管線符號 "\\|"，改用底線 "_" 分隔，
			// 嚴防實體識別碼在 Persistent 傳輸或與 Cassandra/PostgreSQL 交互時踩到字串轉義地雷。
			String[] parts = entityId.split("_", 2);
			String tenantId = parts[0];
			String projectId = parts[1];

			// 呼叫純領域層的靜態工廠，無中生有地還原或建立專案 Actor 實體
			return ProjectEventSourcedActor.create(tenantId, projectId);
		}));

		// 2. 註冊團隊成員管理 Actor (ProjectTeam Aggregate)
		sharding.init(Entity.of(ProjectTeamEventSourcedActor.ENTITY_TYPE_KEY, entityContext -> {
			// 執行多租戶安全物理邊界解包 ( tenantId_projectId )
			String[] parts = entityContext.getEntityId().split("_", 2);
			String tenantId = parts[0];
			String projectId = parts[1];

			// 建立與專案 Actor 完全隔離的獨立團隊 Actor 實體，
			// 將「成員權限異動」與「甘特圖 WBS 編輯」併發鎖定範圍完全切開。
			return ProjectTeamEventSourcedActor.create(tenantId, projectId);
		}));

		return sharding;
	}

	/**
	 * 3. 啟動 CQRS 的讀取端 (Read Side) 同步背景任務
	 */
	@Bean
	public CommandLineRunner startProjection(ActorSystem<Void> system, ProjectProjectionHandler handler) {
		return args -> {
			// 1. 定義資料來源：在 eventsByTag 前面加上 <ProjectEvent> 強制指定新的泛型型別
			SourceProvider<Offset, EventEnvelope<ProjectEvent>> sourceProvider = EventSourcedProvider
					.<ProjectEvent>eventsByTag(system, CassandraReadJournal.Identifier(), ProjectEventSourcedActor.TAG);

			// 2. 明確宣告 Supplier 的型別，防止 Java 編譯器在 Lambda () -> handler 中迷失方向
			java.util.function.Supplier<org.apache.pekko.projection.javadsl.Handler<EventEnvelope<ProjectEvent>>> handlerFactory = () -> handler;

			// 3. 建立 Projection (帶入我們明確定義好的 handlerFactory)
			AtLeastOnceProjection<Offset, EventEnvelope<ProjectEvent>> projection = CassandraProjection
					.atLeastOnce(ProjectionId.of("ProjectProjection", "ProjectView"), sourceProvider, handlerFactory);

			// 4. 啟動 Projection 背景任務
			system.systemActorOf(ProjectionBehavior.create(projection), "ProjectProjection",
					org.apache.pekko.actor.typed.Props.empty());

			System.out.println("✅ CQRS Projection 背景同步服務已成功啟動！");
		};
	}
}