package com.example.demo.application.shared.dto;

/**
 * 建立專案專屬的響應載體
 */
public record ProjectCreatedResult(boolean success, String projectId, String message) {
}