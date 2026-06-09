package com.example.demo.application.domain.team.aggregate.vo;

/**
 * 專案角色定義 (Value Object)
 */
public enum Role {
	OWNER,        // 專案擁有者 PO
    DEVELOPER,     // 開發人員
    SA,            // 系統分析師
    QA,            // 測試人員
    SCRUM_MASTER,  // 敏捷大師
    VIEWER         // 純檢視者 (例如外部長官)
}