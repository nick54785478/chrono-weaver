package com.example.demo.application.domain.project.aggregate.vo;

import java.time.LocalDate;
import java.util.Set;

public record Task(String taskId, String displayId, String name, LocalDate startDate, LocalDate endDate, int progress,
		Set<String> dependencies, String taskType, String assigneeId, String reviewerId, String module) {
}