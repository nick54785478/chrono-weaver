import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { CommandResult } from '../models/command-result.model';
import { map } from 'rxjs/internal/operators/map';
import { ProjectCreatedResource } from '../models/project-created-response';

@Injectable({
  providedIn: 'root',
})
export class ProjectService {
  private baseUrl = `${environment.apiEndpoint}/projects`;

  constructor(private http: HttpClient) {}

  /**
   * 發送 Command 建立全新專案
   * @param projectCode 專案代號 (例如: MAR, JUP)
   * @param name 專案名稱
   * @param tenantId 由外部傳入的租戶識別碼
   * @param ownerId 專案擁有者 ID
   */
  public createProject(
    projectCode: string,
    name: string,
    tenantId: string,
    ownerId: string,
  ) {
    const headers = new HttpHeaders().set('X-Tenant-ID', tenantId);

    const body = {
      projectCode: projectCode.trim().toUpperCase(),
      name: name.trim(),
      ownerId: ownerId,
    };

    return this.http.post<ProjectCreatedResource>(this.baseUrl, body, {
      headers,
    });
  }

  /**
   * 實作：從 CQRS Read Model 獲取指定專案轄下的所有任務清單 (用於中轉輪詢)
   * @param projectId 目標專案唯一識別碼
   * @param tenantId 租戶識別碼
   */
  public getProjectTasks(projectId: string, tenantId: string) {
    // 1. 動態配置多租戶安全 Header
    const headers = new HttpHeaders().set('X-Tenant-ID', tenantId);

    // 2. 組裝路徑：/api/projects/{projectId}/tasks
    const url = `${this.baseUrl}/${projectId}/tasks`;

    // 3. 發送 GET 請求，並自動拆解後端回傳包裹
    return this.http
      .get<any>(url, { headers })
      .pipe(
        map((response) => response.data || response.tasks || response || []),
      );
  }
  /**
   * 意圖 1：更新任務排程時間 (PUT)
   */
  public updateTaskSchedule(
    projectId: string,
    taskId: string,
    tenantId: string,
    startDate: string | null,
    endDate: string | null,
  ) {
    const headers = new HttpHeaders().set('X-Tenant-ID', tenantId);
    const url = `${this.baseUrl}/${projectId}/tasks/${taskId}/schedule`;
    return this.http.put(
      url,
      { startDate, endDate },
      { headers, responseType: 'text' },
    );
  }

  /**
   * 意圖 2：更新任務進度百分比 (PUT)
   */
  public updateTaskProgress(
    projectId: string,
    taskId: string,
    tenantId: string,
    progress: number,
  ) {
    const headers = new HttpHeaders().set('X-Tenant-ID', tenantId);
    const url = `${this.baseUrl}/${projectId}/tasks/${taskId}/progress`;
    return this.http.put(url, { progress }, { headers, responseType: 'text' });
  }

  /**
   * 意圖 3：更新前置相依相依性 (PUT)
   */
  public updateTaskDependencies(
    projectId: string,
    taskId: string,
    tenantId: string,
    dependencyIds: string[],
  ) {
    const headers = new HttpHeaders().set('X-Tenant-ID', tenantId);
    const url = `${this.baseUrl}/${projectId}/tasks/${taskId}/dependencies`;
    return this.http.put(
      url,
      { dependencyIds },
      { headers, responseType: 'text' },
    );
  }

  /**
   * 意圖 4：指派任務負責人與審核者 (PATCH)
   */
  public updateTaskPersonnel(
    projectId: string,
    taskId: string,
    tenantId: string,
    assigneeId: string,
    reviewerId: string,
  ) {
    const headers = new HttpHeaders().set('X-Tenant-ID', tenantId);
    const url = `${this.baseUrl}/${projectId}/tasks/${taskId}/personnel`;
    return this.http.patch(
      url,
      { assigneeId, reviewerId },
      { headers, responseType: 'text' },
    );
  }

  /**
   * 意圖 5：變更任務標籤標記類型 (PATCH)
   */
  public updateTaskType(
    projectId: string,
    taskId: string,
    tenantId: string,
    taskType: string,
  ) {
    const headers = new HttpHeaders().set('X-Tenant-ID', tenantId);
    const url = `${this.baseUrl}/${projectId}/tasks/${taskId}/type`;
    return this.http.patch(
      url,
      { taskType },
      { headers, responseType: 'text' },
    );
  }

  /**
   * 意圖 6：更新任務名稱 (PATCH)
   */
  public updateTaskName(
    projectId: string,
    taskId: string,
    tenantId: string,
    name: string,
  ) {
    console.log('taskId:', taskId);
    const headers = new HttpHeaders().set('X-Tenant-ID', tenantId);
    // 對應後端 PatchMapping("/{projectId}/tasks/{taskId}/name")
    const url = `${this.baseUrl}/${projectId}/tasks/${taskId}/name`;
    return this.http.patch(
      url,
      { name }, // 後端接收的 JSON 鍵值為 'name'
      { headers, responseType: 'text' },
    );
  }

  /**
   * 意圖 7：更新任務所屬模組/Epic (PATCH)
   */
  public updateTaskModule(
    projectId: string,
    taskId: string,
    tenantId: string,
    module: string,
  ) {
    const headers = new HttpHeaders().set('X-Tenant-ID', tenantId);
    const url = `${this.baseUrl}/${projectId}/tasks/${taskId}/module`;
    return this.http.patch(url, { module }, { headers, responseType: 'text' });
  }
}
