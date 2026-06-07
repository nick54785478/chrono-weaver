package com.example.demo.infra.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.demo.infra.projection.ProjectView;

@Repository
public interface ProjectViewRepository extends JpaRepository<ProjectView, String> {
	// 供前端查詢該租戶所有專案的 API 使用
	List<ProjectView> findByTenantId(String tenantId);

	Optional<ProjectView> findByTenantIdAndProjectId(String tenantId, String projectId);

	boolean existsByTenantIdAndProjectId(String tenantId, String projectId);
}
