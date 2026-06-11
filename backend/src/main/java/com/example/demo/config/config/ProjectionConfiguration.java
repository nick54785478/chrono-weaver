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

@Configuration
public class ProjectionConfiguration {

	@Bean
    public CommandLineRunner startProjections(
            ActorSystem<?> system,
            ProjectProjectionHandler projectViewHandler, 
            ProjectCreationSagaHandler sagaHandler,
            // 🌟 1. 注入負責更新團隊成員視圖的 Handler
            ProjectTeamProjectionHandler teamViewHandler 
    ) {
        return args -> {
            // ==========================================
            // 第一條水管：監聽 Project 聚合的事件 (給專案 View 與 Saga 用)
            // ==========================================
            SourceProvider<Offset, EventEnvelope<ProjectEvent>> projectSourceProvider = 
                    EventSourcedProvider.eventsByTag(
                            system, CassandraReadJournal.Identifier(), ProjectEventSourcedActor.TAG
                    );

            Projection<EventEnvelope<ProjectEvent>> viewProjection = CassandraProjection.atLeastOnce(
                    ProjectionId.of("ProjectProjection", "ProjectView"), projectSourceProvider, () -> projectViewHandler
            );
            system.systemActorOf(ProjectionBehavior.create(viewProjection), "ProjectViewDaemon", Props.empty());

            Projection<EventEnvelope<ProjectEvent>> sagaProjection = CassandraProjection.atLeastOnce(
                    ProjectionId.of("ProjectSagaProjection", "ProjectCreationSaga"), projectSourceProvider, () -> sagaHandler
            );
            system.systemActorOf(ProjectionBehavior.create(sagaProjection), "ProjectSagaDaemon", Props.empty());

            // ==========================================
            // 🌟 第二條水管：監聽 ProjectTeam 聚合的事件 (給團隊成員 View 用)
            // ==========================================
            SourceProvider<Offset, EventEnvelope<ProjectTeamEvent>> teamSourceProvider = 
                    EventSourcedProvider.eventsByTag(
                            system, 
                            CassandraReadJournal.Identifier(), 
                            ProjectTeamEventSourcedActor.TAG // 確認你的 Actor 裡有定義這個常數 (通常是 "ProjectTeamEvent")
                    );

            Projection<EventEnvelope<ProjectTeamEvent>> teamViewProjection = CassandraProjection.atLeastOnce(
                    ProjectionId.of("ProjectTeamProjection", "TeamMemberView"), 
                    teamSourceProvider, 
                    () -> teamViewHandler
            );
            
            // 啟動第三個背景精靈！
            system.systemActorOf(ProjectionBehavior.create(teamViewProjection), "TeamViewDaemon", Props.empty());
            
            System.out.println("🚀 CQRS 所有 Projection (專案、Saga、團隊) 已全數成功啟動！");
        };
    }
}