package com.example.demo.iface.event;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.apache.pekko.Done;
import org.apache.pekko.projection.eventsourced.EventEnvelope;
import org.apache.pekko.projection.javadsl.Handler;
import org.springframework.stereotype.Component;

import com.example.demo.application.domain.project.event.ProjectEvent;
import com.example.demo.infra.linker.ProjectToTeamLinker;

import lombok.extern.slf4j.Slf4j;

/**
 * 專案建立 Saga 的技術外殼 (Pekko Projection Handler)
 * <p>
 * 架構職責：負責滿足 Pekko Event Sourced Projection 的介面規範。 攔截到 EventEnvelope
 * 後，剝開信封，將事件本體 (ProjectEvent) 轉交給領域聯動引擎 (ProjectToTeamLinker) 處理。
 * </p>
 */
@Slf4j
@Component
public class ProjectCreationSagaHandler extends Handler<EventEnvelope<ProjectEvent>> {

	// 核心整合：將業務心臟注入到這個外殼中
	private final ProjectToTeamLinker linker;

	public ProjectCreationSagaHandler(ProjectToTeamLinker linker) {
		this.linker = linker;
	}

	@Override
	public CompletionStage<Done> process(EventEnvelope<ProjectEvent> envelope) {
		ProjectEvent event = envelope.event();

		try {
			// 無腦轉交：把解析出來的事件直接丟給 Saga 引擎處理
			this.linker.processProjectEvent(event);

		} catch (Exception ex) {
			log.error("[Saga Shell] 跨聚合聯動執行失敗，準備觸發重試機制。事件: {}", event, ex);
			// 拋出例外讓 Pekko 的 Backoff 策略介入重試
			throw new RuntimeException("Saga 聯動失敗", ex);
		}

		// 💡 回傳 Done，通知 Pekko Projection 框架推進 Offset (確認信號)
		return CompletableFuture.completedFuture(Done.getInstance());
	}
}