import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterModule } from '@angular/router';

// PrimeNG UI 模組
import { TableModule } from 'primeng/table';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { AvatarModule } from 'primeng/avatar';
import { TagModule } from 'primeng/tag';
import { TooltipModule } from 'primeng/tooltip';
import { ProjectService } from '../../../../shared/services/project.service';
import { StorageService } from '../../../../core/services/storage.service';
import { SystemStorageKey } from '../../../../core/enums/system-storage.enum';

interface ProjectView {
  projectId: string;
  name: string;
  projectCode?: string; // Jira 風格的專案代號 (例如: CHR, DDD)
  ownerId: string;
  // 以下為前端顯示用的輔助欄位
  status?: string;
}

@Component({
  selector: 'app-project-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    TableModule,
    ButtonModule,
    InputTextModule,
    AvatarModule,
    TagModule,
    TooltipModule,
  ],
  templateUrl: './project-list.component.html',
  styleUrls: ['./project-list.component.scss'],
})
export class ProjectListComponent implements OnInit {
  projects: ProjectView[] = [];
  loading: boolean = true;

  tenantId: string = '';
  userId: string = '';

  constructor(
    private projectService: ProjectService,
    private storageService: StorageService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.tenantId =
      this.storageService.getLocalStorageItem(SystemStorageKey.TENANT) || 'DDD';
    // 實務上會從 Token 解析，這裡假設使用 Storage 中的 UserID 或預設值
    this.userId =
      this.storageService.getLocalStorageItem('USER_ID') || '107054';

    this.loadProjects();
  }

  loadProjects(): void {
    this.loading = true;
    this.projectService
      .getMyParticipatedProjects(this.tenantId, this.userId)
      .subscribe({
        next: (data) => {
          this.projects = data.map((p) => ({
            ...p,
            // 🌟 修正點：優先使用後端傳來的 projectCode (如 MAR, JUP)，沒有的話才用 UUID 前三碼墊檔
            projectCode:
              p.projectCode || p.projectId.substring(0, 3).toUpperCase(),
            status: '進行中',
          }));
          this.loading = false;
        },
        error: (err) => {
          console.error('無法取得專案列表', err);
          this.loading = false;
        },
      });
  }

  // 點擊專案列，進入專案總覽 (Summary) 頁面
  goToProjectSummary(project: ProjectView): void {
    // 將選中的專案 ID 存入 Storage，供其他頁面 (如 Summary, Team, Gantt) 使用
    this.storageService.setLocalStorageItem(
      SystemStorageKey.LAST_PROJECT_ID,
      project.projectId,
    );

    // 導向專案總覽頁面
    this.router.navigate(['/project-summary']);
  }

  // 取得專案名稱的第一個字作為 Avatar
  getProjectInitials(name: string): string {
    return name ? name.substring(0, 1).toUpperCase() : 'P';
  }

  // 根據專案代號產生固定的 Avatar 背景顏色 (Jira 的小彩蛋)
  getAvatarColor(code: string | undefined): string {
    const colors = [
      '#0052CC',
      '#00875A',
      '#FF991F',
      '#DE350B',
      '#5243AA',
      '#00B8D9',
    ];
    if (!code) return colors[0];
    const charCodeSum = code
      .split('')
      .reduce((sum, char) => sum + char.charCodeAt(0), 0);
    return colors[charCodeSum % colors.length];
  }
}
