package com.example.demo.infra.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.demo.infra.projection.TaskView;

@Repository
public interface TaskViewRepository extends JpaRepository<TaskView, String> {

	List<TaskView> findByProjectId(String projectId);

	List<TaskView> findByTenantIdAndProjectIdOrderByCreatedAtAsc(String tenantId, String projectId);
}