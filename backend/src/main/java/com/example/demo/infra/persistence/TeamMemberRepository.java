package com.example.demo.infra.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.demo.infra.projection.TeamMemberView;

@Repository
public interface TeamMemberRepository extends JpaRepository<TeamMemberView, String> {

	Optional<TeamMemberView> findByProjectIdAndUserId(String projectId, String userId);

	List<TeamMemberView> findAllByProjectId(String projectId);

	void deleteByProjectIdAndUserId(String projectId, String userId);

	List<TeamMemberView> findByTenantIdAndProjectIdOrderByJoinedAtAsc(String tenantId, String projectId);
}