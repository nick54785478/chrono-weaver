import { Injectable } from '@angular/core';
import { Observable } from 'rxjs/internal/Observable';
import { TeamMember } from '../models/team-member';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { TeamMembersGottenResult } from '../../features/team/models/team-members-gotten-response';
import { map } from 'rxjs';

@Injectable({
  providedIn: 'root',
})
export class ProjectTeamService {
  private baseUrl = environment.apiEndpoint + '/projects';

  constructor(private http: HttpClient) {}

  private getHeaders(tenantId: string) {
    return { headers: new HttpHeaders({ 'X-Tenant-ID': tenantId }) };
  }

  // 取得成員名單 (Query)
  getMembers(projectId: string, tenantId: string): Observable<TeamMember[]> {
    return this.http
      .get<TeamMembersGottenResult>(
        `${this.baseUrl}/${projectId}/team/members`,
        this.getHeaders(tenantId),
      )
      .pipe(
        map((res) => {
          return res.data;
        }),
      );
  }

  // 加入成員 (Command)
  addMember(
    projectId: string,
    userId: string,
    role: string,
    tenantId: string,
  ): Observable<any> {
    return this.http.post(
      `${this.baseUrl}/${projectId}/team/members`,
      { userId, role },
      this.getHeaders(tenantId),
    );
  }

  // 變更角色 (Command)
  changeRole(
    projectId: string,
    userId: string,
    role: string,
    tenantId: string,
  ): Observable<any> {
    return this.http.put(
      `${this.baseUrl}/${projectId}/team/members/${userId}/role`,
      { role },
      this.getHeaders(tenantId),
    );
  }

  // 移除成員 (Command)
  removeMember(
    projectId: string,
    userId: string,
    tenantId: string,
  ): Observable<any> {
    return this.http.delete(
      `${this.baseUrl}/${projectId}/team/members/${userId}`,
      this.getHeaders(tenantId),
    );
  }
}
