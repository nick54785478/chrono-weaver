package com.example.demo.infra.projection;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "projects_view") // Read Side 的視圖表
public class ProjectView {

	@Id
	private String projectId;
	private String tenantId;
	private String name;
	private String ownerId;
	private String projectCode;

	// 未來我們可以在這裡加上甘特圖需要的彙總資料，例如：總任務數、整體進度等
}