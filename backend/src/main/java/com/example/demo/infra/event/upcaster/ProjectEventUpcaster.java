package com.example.demo.infra.event.upcaster;

import java.util.Set;

import org.apache.pekko.persistence.journal.EventSeq;
import org.apache.pekko.persistence.journal.ReadEventAdapter;

import com.example.demo.application.domain.project.event.ProjectEvent.TaskDependenciesUpdated;
import com.example.demo.application.domain.project.event.ProjectEvent.TaskDependencyAdded;

/**
 * 專案領域事件向上轉型器 (Event Upcaster)
 * 
 * <pre>
 * * 當系統已經上線，我們絕對不可能清空客戶的正式資料庫。面對這種需求變更，企業級 DDD 系統會採用一種名為 Event Upcasting
 * (事件向上轉型) 的策略。
 * 
 * 實務上，我們不會刪除舊的 TaskDependencyAdded，而是會保留它（標記為 @Deprecated），並且在 Pekko 中註冊一個
 * EventAdapter。
 * 
 * 當底層從資料庫讀到舊的 TaskDependencyAdded(A, B) 時，
 * EventAdapter 會在記憶體中把它「翻譯/升級」成新的 TaskDependenciesUpdated(A, Set.of(B))，
 * 然後才交給你的聚合根去 apply()，這樣就能在不改動歷史資料庫的前提下，完美過渡到新的業務邏輯。
 * 
 * 負責攔截資料庫中已廢棄的舊版歷史事件，並將其即時轉換 (Upcast) 為最新版本的事件。 確保 Aggregate Root 與 CQRS
 * Projection 永遠只需要處理最新版本的領域合約，不受歷史包袱污染。
 * </pre>
 */
public class ProjectEventUpcaster implements ReadEventAdapter {

	@Override
	public EventSeq fromJournal(Object event, String manifest) {

		// 攔截舊版事件：單筆新增相依性
		if (event instanceof TaskDependencyAdded oldEvent) {

			// 升級邏輯：將單筆的 String 轉換為包含一個元素的 Set<String>
			TaskDependenciesUpdated newEvent = new TaskDependenciesUpdated(oldEvent.taskId(),
					Set.of(oldEvent.dependsOnTaskId()));

			// 回傳升級後的新版事件
			return EventSeq.single(newEvent);
		}

		// 如果是最新版或其他不需要升級的事件，原封不動直接放行
		return EventSeq.single(event);
	}
}