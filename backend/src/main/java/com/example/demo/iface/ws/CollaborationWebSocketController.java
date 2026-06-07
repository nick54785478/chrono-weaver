package com.example.demo.iface.ws;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import lombok.extern.slf4j.Slf4j;

/**
 * 即時共編 WebSocket 訊息控制器 (Track A - Ephemeral State)
 * <p>
 * 為了不讓高頻率的打字與滑鼠點擊壓垮 Pekko Actor 寫入端， 本控制器採取「記憶體直接轉發」策略，只負責盲傳二進位資料，不觸發 Event
 * Sourcing 流程。
 * </p>
 */
@Slf4j
@Controller
public class CollaborationWebSocketController {

	private final SimpMessagingTemplate messagingTemplate;

	public CollaborationWebSocketController(SimpMessagingTemplate messagingTemplate) {
		this.messagingTemplate = messagingTemplate;
	}

	/**
	 * 處理 Yjs 二進位變更增量 (Binary Update Relay)
	 */
	@MessageMapping("/projects/{projectId}/yjs")
	public void relayYjsUpdate(@DestinationVariable String projectId, @Payload byte[] payload) {
		log.trace("接收到專案 [{}] 的 Yjs 二進位增量更新，長度: {} bytes", projectId, payload.length);
		String destination = "/topic/projects/" + projectId + "/yjs";
		messagingTemplate.convertAndSend(destination, payload);
	}

	/**
	 * 處理 Yjs 游標感知與線上狀態 (Awareness Binary Relay)
	 * <p>
	 * 🌟 關鍵修正：y-protocols/awareness 傳輸的也是壓縮過的 byte[]，絕不能用 JSON DTO 去接！
	 * </p>
	 */
	@MessageMapping("/projects/{projectId}/awareness")
	public void relayAwareness(@DestinationVariable String projectId, @Payload byte[] payload) { // 🌟 這裡必須是 byte[]
		log.trace("接收到專案 [{}] 的 Awareness 游標二進位更新，長度: {} bytes", projectId, payload.length);
		String destination = "/topic/projects/" + projectId + "/awareness";
		messagingTemplate.convertAndSend(destination, payload);
	}
}