package com.example.demo.config.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * 企業級表格即時共編 WebSocket 配置 (STOMP 協定) *
 * 
 * <pre>
 * <b>架構設計重點：</b>
 * 1. <b>高頻二進位優化：</b> 由於 Yjs 傳輸採用封裝的 byte[]，預設的 64KB 限制極易在高並發或大快照時爆掉，此處將上限提升至 512KB。
 * 2. <b>多租戶動態訂閱：</b> 頻道設計採用 /topic/projects/{projectId}/*，讓前端依專案動態訂閱，達成資料邊界隔離。
 * </pre>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfiguration implements WebSocketMessageBrokerConfigurer {

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		// 註冊供前端（如 Angular rx-stomp）連線的端點
		registry.addEndpoint("/ws-collaborate").setAllowedOriginPatterns("*") // 開放跨域，實務上請依安全政策調整
				.withSockJS(); // 啟用 SockJS 降級相容機制
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		// 內建簡單消息代理，前端訂閱 /topic 開頭的頻道可直接接收廣播
		registry.enableSimpleBroker("/topic");

		// 前端發送訊息給後端處理器（Controller）時的前綴
		registry.setApplicationDestinationPrefixes("/app");
	}

	@Override
	public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
		// 🌟 關鍵效能優化：調整傳輸緩衝上限，防止 Yjs 大快照（Snapshot）造成連線中斷
		registration.setMessageSizeLimit(512 * 1024); // 單次訊息上限 512 KB
		registration.setSendBufferSizeLimit(1024 * 1024); // 發送緩衝區上限 1 MB
		registration.setSendTimeLimit(20 * 1000); // 發送逾時 20 秒
	}
}