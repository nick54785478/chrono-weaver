package com.example.demo.config;

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

@Configuration
public class PekkoConfig {

	// 1. 啟動 Pekko Actor 系統
	@Bean(destroyMethod = "terminate")
	public ActorSystem<Void> actorSystem() {
		// 實務上這裡會讀取 application.conf 來設定 Cassandra 與 Cluster 參數
		return ActorSystem.create(Behaviors.empty(), "ChronoWeaverSystem");
	}

	// 2. 註冊 Cluster Sharding
	@Bean
	public ClusterSharding clusterSharding(ActorSystem<Void> system) {
		ClusterSharding sharding = ClusterSharding.get(system);

		sharding.init(Entity.of(ProjectEventSourcedActor.ENTITY_TYPE_KEY, entityContext -> {
			String entityId = entityContext.getEntityId();
			// 💡 將 split("\\|") 改為 split("_")
			String[] parts = entityId.split("_");
			String tenantId = parts[0];
			String projectId = parts[1];

			return ProjectEventSourcedActor.create(tenantId, projectId);
		}));

		return sharding;
	}

	// 3. 啟動 CQRS 的讀取端 (Read Side) 同步背景任務
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