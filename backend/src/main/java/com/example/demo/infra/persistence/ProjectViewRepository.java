package com.example.demo.infra.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.demo.infra.projection.ProjectView;

@Repository
public interface ProjectViewRepository extends JpaRepository<ProjectView, String> {
	// 供前端查詢該租戶所有專案的 API 使用
	List<ProjectView> findByTenantIdAndOwnerId(String tenantId, String ownerId);

	Optional<ProjectView> findByTenantIdAndProjectId(String tenantId, String projectId);

	boolean existsByTenantIdAndProjectId(String tenantId, String projectId);
	
	/**
     * 核心唯讀查詢：撈出指定租戶下，特定使用者作為團隊成員參與的所有專案
     * * <pre>
     * <b>效能與架構考量：</b>
     * 這裡不採用 JPA Entity 之間的 @OneToMany 關聯導向導航，
     * 而是利用 JPQL 子查詢直接進行資料庫層級的 IN 篩選。
     * 這能確保 ProjectView 與 TeamMemberView 在 ORM 對象圖上維持完全解耦，提供極高的查詢吞吐量。
     * </pre>
     */
    @Query("""
        SELECT p FROM ProjectView p 
        WHERE p.tenantId = :tenantId 
        AND p.projectId IN (
            SELECT tm.projectId FROM TeamMemberView tm 
            WHERE tm.userId = :userId
        )
    """)
    List<ProjectView> findByTenantIdAndMemberUserId(
            @Param("tenantId") String tenantId, 
            @Param("userId") String userId
    );
}
