package com.example.demo.infra.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.demo.infra.projection.TeamMemberView;

@Repository
public interface TeamMemberRepository extends JpaRepository<TeamMemberView, String> {

	// 🌟 精準定位某一筆成員視圖紀錄
	Optional<TeamMemberView> findByProjectIdAndUserId(String projectId, String userId);

	// 🌟 供前端 REST 查詢 WBS 線上指派名單使用
	List<TeamMemberView> findAllByProjectId(String projectId);

	void deleteByProjectIdAndUserId(String projectId, String userId);
}