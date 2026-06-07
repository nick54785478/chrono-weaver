package com.example.demo.application.shared.dto;

public record GanttTaskGottenResult(String id, // taskId
		String text, // name
		String start_date, // ISO格式日期
		int duration, // 工期 (計算 endDate - startDate)
		float progress, // 0.0 - 1.0
		String parent, // Epic 名稱 (作為分組)
		boolean open // 是否預設展開
) {
}