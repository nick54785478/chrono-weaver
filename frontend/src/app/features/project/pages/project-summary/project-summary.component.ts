import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { forkJoin } from 'rxjs';

// PrimeNG UI 模組
import { CardModule } from 'primeng/card';
import { ProgressBarModule } from 'primeng/progressbar';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { DividerModule } from 'primeng/divider';
import { AvatarModule } from 'primeng/avatar';
import { TeamMember } from '../../../../shared/models/team-member';
import { StorageService } from '../../../../core/services/storage.service';
import { ProjectTeamService } from '../../../../shared/services/project-team.service';
import { SystemStorageKey } from '../../../../core/enums/system-storage.enum';
import { ProjectService } from '../../../../shared/services/project.service';
import { SystemMessageService } from '../../../../shared/services/system-message.service';
import { TaskView } from '../../../../shared/models/task-view.model';
import { ProjectView } from '../../../../shared/models/project-view.model';

@Component({
  selector: 'app-project-summary',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    CardModule,
    ProgressBarModule,
    ButtonModule,
    TagModule,
    DividerModule,
    AvatarModule,
  ],
  templateUrl: './project-summary.component.html',
  styleUrls: ['./project-summary.component.scss'],
})
export class ProjectSummaryComponent implements OnInit {
  projectId: string = '';
  tenantId: string = '';
  loading: boolean = true;

  members: TeamMember[] = [];
  tasks: TaskView[] = [];
  project: ProjectView | null = null;

  overallProgress: number = 0;
  completedTasks: number = 0;

  constructor(
    private router: Router,
    // private route: ActivatedRoute,
    private storageService: StorageService,
    private teamService: ProjectTeamService,
    private projectService: ProjectService, // 🌟 注入專案服務
    private systemMessageService: SystemMessageService,
  ) {}

  ngOnInit(): void {
    this.projectId =
      this.storageService.getLocalStorageItem(
        SystemStorageKey.LAST_PROJECT_ID,
      ) || '';
    this.tenantId =
      this.storageService.getLocalStorageItem(SystemStorageKey.TENANT) || '';

    if (this.projectId) {
      this.loadDashboardData();
    } else {
      this.loading = false;
      this.systemMessageService.showError('未選取專案', '請先選擇一個工作專案');
    }
  }

  // ==========================================
  // 🌟 核心串接：使用 forkJoin 聚合平行請求
  // ==========================================
  loadDashboardData(): void {
    this.loading = true;

    forkJoin({
      project: this.projectService.getProjectById(
        this.projectId,
        this.tenantId,
      ),
      tasks: this.projectService.getProjectTasks(this.projectId, this.tenantId),
      members: this.teamService.getMembers(this.projectId, this.tenantId),
    }).subscribe({
      next: (result) => {
        // 解包並綁定資料
        this.project = result.project;
        this.tasks = result.tasks;
        this.members = result.members;

        // 計算 KPI 統計指標
        this.calculateMetrics();
        this.loading = false;
      },
      error: (err) => {
        console.log('Dashboard data fetch failed', err);
        this.systemMessageService.showError(
          '載入失敗',
          '無法取得專案摘要資訊，請確認網路連線',
        );
        this.loading = false;
      },
    });
  }

  calculateMetrics(): void {
    if (!this.tasks || this.tasks.length === 0) {
      this.overallProgress = 0;
      this.completedTasks = 0;
      return;
    }

    let totalProgress = 0;
    this.completedTasks = 0;

    this.tasks.forEach((t) => {
      totalProgress += t.progress;
      if (t.progress === 100) this.completedTasks++;
    });

    this.overallProgress = Math.round(totalProgress / this.tasks.length);
  }

  /**
   * 導頁到團隊管理頁面
   */
  navigateToTeams(): void {
    this.router.navigate(['/teams']);
  }

  /**
   * 導頁到甘特圖頁面
   */
  navigateToGantt(): void {
    this.router.navigate(['/gantt']);
  }

  // 🌟 新增跳轉至共編頁面的方法
  navigateToCollab(): void {
    // 請將這裡的字串替換成你實際的共編路由設定
    // 例如跳轉到 Yjs + 共同編輯表格的頁面
    this.router.navigate(['/co-editor']);
  }
}
