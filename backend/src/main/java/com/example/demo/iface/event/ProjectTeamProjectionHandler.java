package com.example.demo.iface.event;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.apache.pekko.Done;
import org.apache.pekko.projection.eventsourced.EventEnvelope;
import org.apache.pekko.projection.javadsl.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.demo.application.domain.team.event.ProjectTeamEvent;
import com.example.demo.application.port.TeamMemberViewUpdaterPort;
import com.example.demo.infra.projection.TeamMemberView;

/**
 * 專案團隊讀取模型更新引擎 (CQRS Read Model Projection)
 * <p>
 * <b>架構職責：</b> 本元件負責訂閱並消費來自 ProjectTeam 聚合根的 Event Journal 串流。
 * 收到事件後，將非同步地將最新的領域狀態投影（Project）至關聯式資料庫中的平坦唯讀視圖 {@link TeamMemberView}。
 * </p>
 * <p>
 * <b>設計特點：</b> 採用 Port-Adapter 架構，將資料庫交易 (Transaction) 與底層持久化邏輯 委託給
 * {@link TeamMemberViewUpdaterPort}，確保 Handler 保持為純粹的事件調度員。
 * </p>
 */
@Component
public class ProjectTeamProjectionHandler extends Handler<EventEnvelope<ProjectTeamEvent>> {

	private static final Logger log = LoggerFactory.getLogger(ProjectTeamProjectionHandler.class);

	// 🌟 注入 Port，完美達成依賴反轉，Handler 不再與 JPA Repository 綁死
	private final TeamMemberViewUpdaterPort viewUpdater;

	public ProjectTeamProjectionHandler(TeamMemberViewUpdaterPort viewUpdater) {
		this.viewUpdater = viewUpdater;
	}

	/**
	 * Pekko Projection 的核心串流處理入口
	 *
	 * @param envelope 封裝了領域事件與後設資料 (Metadata) 的信封
	 * @return CompletionStage 供 Pekko 框架進行非同步的基底 Offset 推進確認
	 */
	@Override
	public CompletionStage<Done> process(EventEnvelope<ProjectTeamEvent> envelope) {
		ProjectTeamEvent event = envelope.event();

		// ==========================================
		// 🌟 核心技巧：從 Pekko Persistence ID 解析多租戶邊界
		// 由於事件本體通常不攜帶聚合根 ID，我們必須從信封的 persistenceId 反向提煉。
		// 格式規範通常為: "ProjectTeam|tenantId_projectId"
		// ==========================================
		String persistenceId = envelope.persistenceId();
		String entityId = persistenceId.split("\\|")[1]; // 取得分流後的 "tenantId_projectId"
		String[] parts = entityId.split("_", 2);
		String tenantId = parts[0];
		String projectId = parts[1];

		try {
			// 使用 Java 21 Pattern Matching 強型別拆解事件，保證編譯期安全
			switch (event) {
			case ProjectTeamEvent.TeamInitialized e -> {
				// 【團隊初始化】：在平坦的關聯式唯讀視圖中，初始化房間通常不需要在 DB 插入空列。
				// 採取「樂觀延遲寫入」策略，等第一個 MemberAdded 事件抵達時再進行實體建立即可。
				log.info("[Projection] 接收到專案團隊初始化事件，目標專案: {}", projectId);
			}

			case ProjectTeamEvent.MemberAdded e -> {
				// 🌟 將狀態更新職責委託給 Adapter，享有完整的 @Transactional 保護
				viewUpdater.addMember(tenantId, projectId, e);
			}

			case ProjectTeamEvent.MemberRoleChanged e -> {
				// 委託更新角色
				viewUpdater.changeMemberRole(projectId, e);
			}

			case ProjectTeamEvent.MemberRemoved e -> {
				// 委託移除成員
				viewUpdater.removeMember(projectId, e);
			}
			}
		} catch (Exception ex) {
			// 🌟 錯誤防禦策略：
			// 這裡拋出例外會讓 Pekko Projection 觸發退避重試（Backoff Retry）機制。
			// 如果是暫時性的資料庫斷線，重試幾次就能自動回復，確保讀寫兩端的「最終一致性 (Eventual Consistency)」。
			log.error("[Projection] 處理事件失敗，Offset 暫停前進。事件詳情: {}", event, ex);
			throw new RuntimeException("Projection Pipeline 崩潰，請求框架發起重試", ex);
		}

		// 💡 回傳確認信號，通知 Pekko 此事件已成功投影，可以放行推進 Offset 檔案戳記
		return CompletableFuture.completedFuture(Done.getInstance());
	}
}