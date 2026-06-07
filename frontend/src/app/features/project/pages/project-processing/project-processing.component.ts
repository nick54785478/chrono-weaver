import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { ProjectService } from '../../../../shared/service/project.service';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { timer, forkJoin } from 'rxjs'; // 🌟 引入 RxJS 工具
import { StorageService } from '../../../../core/services/storage.service';
import { SystemStorageKey } from '../../../../core/enums/system-storage.enum';

@Component({
  selector: 'app-project-summary', // 保持你目前的 selector 命名
  standalone: true,
  imports: [CommonModule, ProgressSpinnerModule],
  templateUrl: './project-processing.component.html', // 建議指向你的 HTML 檔案
})
export class ProjectProcessingComponent implements OnInit {
  // 🌟 定義最小顯示時間（毫秒），例如 1500 毫秒 (1.5秒)，可依喜好調整
  private readonly MIN_LOADING_TIME = 1500;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private projectService: ProjectService,
    private storageService: StorageService,
  ) {}

  ngOnInit() {
    const projectId = this.route.snapshot.paramMap.get('id');
    const tenantId =
      this.storageService.getLocalStorageItem(SystemStorageKey.TENANT) || 'WPG';

    if (projectId) {
      this.checkProjectReadyWithDelay(projectId, tenantId);
    }
  }

  /**
   * 帶有「最小有感時間」的輪詢檢查
   */
  private checkProjectReadyWithDelay(
    projectId: string,
    tenantId: string,
    retries = 5,
  ) {
    // 建立一個倒數計時的 Observable
    const minTimeTimer$ = timer(this.MIN_LOADING_TIME);
    // 建立請求後端任務的 Observable
    const fetchTasks$ = this.projectService.getProjectTasks(
      projectId,
      tenantId,
    );

    // 🌟 使用 forkJoin：必須【兩者皆完成】才會觸發 next
    // 這樣即使後端 50 毫秒就回傳，畫面也一定會優雅地轉滿 1.5 秒
    forkJoin([fetchTasks$, minTimeTimer$]).subscribe({
      next: () => {
        // 投影已就緒，且動畫播滿設定時間，完美滑入編輯器
        this.router.navigate(['/co-editor', projectId]);
      },
      error: (err) => {
        // 如果是第一次請求失敗 (Read Model 還沒好)，就進入重試邏輯
        if (retries > 0) {
          setTimeout(
            () =>
              this.checkProjectReadyWithDelay(projectId, tenantId, retries - 1),
            1000, // 每次重試間隔 1 秒
          );
        } else {
          // 超過重試次數，真的失敗了，回到建立頁面
          console.error('[CQRS] 投影重試失敗，Read Model 未能如期就緒:', err);
          this.router.navigate(['/create-project']);
        }
      },
    });
  }
}
