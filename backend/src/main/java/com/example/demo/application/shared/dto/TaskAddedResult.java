package com.example.demo.application.shared.dto;

/**
 * 新增任務專屬的響應載體，徹底解決前端的痛點：明確拆分實體 UUID (taskId) 與人類可讀編號 (displayId)
 */
public record TaskAddedResult(boolean success, String taskId, String message) {
}
//public record TaskAddedResult(boolean success, String taskId, String displayId, String message) {
//}