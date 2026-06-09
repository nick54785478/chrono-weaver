package com.example.demo.application.domain.team.aggregate.vo;

import java.time.Instant;

/**
 * 團隊成員 (Value Object / Local Entity)
 * <p>
 * 紀錄該成員在專案中的角色與加入時間。 這裡不需要記錄 Name 或 Email，因為那是 User 聚合的職責。
 * </p>
 */
public record TeamMember(String userId, Role role, Instant joinedAt) {
}