package com.example.demo.application.shared.dto;

/**
 * 任務更新操作共用的響應載體 (適用於 PUT/PATCH 等異動操作)
 */
public record TaskUpdatedResult(boolean success, String taskId, String message) {
}