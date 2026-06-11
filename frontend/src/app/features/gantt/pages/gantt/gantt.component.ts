import {
  Component,
  OnInit,
  AfterViewInit,
  ElementRef,
  ViewChild,
  OnDestroy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import Gantt from 'frappe-gantt';

// PrimeNG 模組（用於工具列）
import { ButtonModule } from 'primeng/button';
import { SelectButtonModule } from 'primeng/selectbutton';
import { FormsModule } from '@angular/forms';
import { ProjectService } from '../../../../shared/services/project.service';
import { StorageService } from '../../../../core/services/storage.service';
import { SystemStorageKey } from '../../../../core/enums/system-storage.enum';
import { Task } from '../../../co-editor/models/task.model';

/**
 * 專案甘特圖元件 (GanttComponent)
 * 負責將 WBS 任務資料轉換為視覺化的甘特圖。
 * 包含動態圖例生成、任務依賴防呆、以及 Epic 自動著色等進階功能。
 */
@Component({
  selector: 'app-gantt',
  standalone: true,
  imports: [CommonModule, ButtonModule, SelectButtonModule, FormsModule],
  templateUrl: './gantt.component.html',
  styleUrl: './gantt.component.scss',
})
export class GanttComponent implements OnInit, AfterViewInit, OnDestroy {
  // 綁定 HTML 中的 SVG 畫布容器
  @ViewChild('gantt_canvas', { static: true }) ganttCanvas!: ElementRef;
  // --- 專案環境狀態 ---
  projectId: string = '';
  tenantId: string = '';
  gantt: any; // Frappe Gantt 的實體參考

  // --- UI 控制與圖例狀態 ---
  // 定義視圖切換的選項，使用明確的型別避免與套件定義衝突
  viewModes = [
    { label: '日', value: 'Day' },
    { label: '週', value: 'Week' },
    { label: '月', value: 'Month' },
  ];

  // 動態生成的 Epic (模組) 圖例清單
  epicLegend: { name: string; bgClass: string }[] = [];
  // 鎖定型別為官方定義的 Gantt.viewMode，確保編譯安全
  selectedViewMode: Gantt.viewMode = 'Day';

  constructor(
    private route: ActivatedRoute,
    private projectService: ProjectService,
    private storageService: StorageService,
  ) {}

  ngOnInit() {
    // 初始化租戶環境
    this.tenantId =
      this.storageService.getLocalStorageItem(SystemStorageKey.TENANT) || 'WPG';

    // 訂閱路由變更，確保能在不同專案間切換甘特圖
    this.route.paramMap.subscribe((params) => {
      this.projectId =
        params.get('id') ||
        this.storageService.getLocalStorageItem('last_project_id') ||
        '';
      if (this.projectId) {
        this.loadGanttData();
      }
    });
  }

  ngAfterViewInit() {
    // 生命週期保留：確保 SVG 容器已掛載完成
  }

  /**
   * 向後端請求專案任務，並觸發甘特圖渲染
   */
  loadGanttData() {
    this.projectService
      .getProjectTasks(this.projectId, this.tenantId)
      .subscribe((tasks) => {
        this.initGantt(tasks);
      });
  }

  /**
   * 初始化與渲染 Frappe Gantt 圖表
   * @param tasks 來自後端的原始任務陣列
   */
  private initGantt(tasks: Task[]) {
    // 1. 嚴格過濾：甘特圖只能繪製「擁有明確開始與結束日期」的任務
    const validTasks = tasks.filter(
      (t) =>
        t.startDate &&
        t.startDate.trim() !== '' &&
        t.endDate &&
        t.endDate.trim() !== '',
    );
    // 建立安全名單 (Set 效能較佳)，用於後續過濾「幽靈依賴」
    const validTaskIds = new Set(validTasks.map((t) => t.taskId));

    // 2. 動態提煉圖例 (Legend)
    // 自動掃描當前有效的任務，提煉出不重複的 Epic (模組) 清單
    const uniqueModules = Array.from(
      new Set(
        validTasks.map((t) => t.module).filter((m) => m && m.trim() !== ''),
      ),
    );

    // 組合圖例顯示所需的資料結構與 CSS Class
    this.epicLegend = uniqueModules.map((moduleName) => ({
      name: moduleName as string,
      // 加上 bg- 前綴，對應 SCSS 中供 HTML <div> 渲染背景色的樣式
      bgClass: 'bg-' + this.getCustomClassByModule(moduleName),
    }));

    // 3. 資料轉譯 (Mapping)
    // 將後端 Task 格式轉譯為 Frappe Gantt 要求的格式
    const ganttTasks: Gantt.Task[] = validTasks.map((t) => {
      // 依賴防呆：過濾掉指向「沒有日期的任務」或「已被刪除的任務」的依賴 ID，防止套件崩潰
      const validDependencies = (t.dependencies || []).filter((depId) =>
        validTaskIds.has(depId),
      );

      let startDate = new Date(t.startDate as string);
      let endDate = new Date(t.endDate as string);

      // 日期防呆：Frappe Gantt 無法繪製工期 (duration) 為 0 或負數的任務。
      // 若起訖日期為同一天或結束早於開始，強制將結束日期向後延展一天。
      if (endDate.getTime() <= startDate.getTime()) {
        endDate = new Date(startDate);
        endDate.setDate(endDate.getDate() + 1);
      }

      return {
        id: t.taskId,
        name: `[${t.displayId || t.taskId}] ${t.name}`,
        start: startDate.toISOString().split('T')[0],
        end: endDate.toISOString().split('T')[0],
        progress: t.progress || 0,
        dependencies:
          validDependencies.length > 0 ? validDependencies.join(', ') : '',
        // 將計算出的顏色 Class 綁定到 SVG 的最外層 Wrapper 上
        custom_class: this.getCustomClassByModule(t.module),
      };
    });

    // 若過濾後無任何任務，停止渲染避免白畫面錯誤
    if (ganttTasks.length === 0) {
      console.warn('目前沒有包含完整日期的任務可供繪製甘特圖');
      return;
    }

    // 重繪防呆：若是重新載入資料，必須先清空舊的 SVG 內容，否則畫面會重疊破版
    if (this.gantt) {
      this.ganttCanvas.nativeElement.innerHTML = '';
    }

    // 4. 實體化甘特圖引擎
    this.gantt = new Gantt(this.ganttCanvas.nativeElement, ganttTasks, {
      view_mode: this.selectedViewMode,
      language: 'zh',
      // 日後若要實作「拖拉甘特圖即時連動共編」，可在此處將事件發送給 GanttSyncService
      on_click: (task: Gantt.Task) => console.log('點擊任務:', task),
      on_date_change: (task: Gantt.Task, start: Date, end: Date) => {
        console.log('日期變更:', task, start, end);
      },
      on_progress_change: (task: Gantt.Task, progress: number) => {
        console.log('進度變更:', task, progress);
      },
    });
  }

  /**
   * 切換甘特圖的顯示刻度 (日/週/月)
   * @param mode 目標視圖模式
   */
  changeViewMode(mode: Gantt.viewMode) {
    this.selectedViewMode = mode;
    if (this.gantt) {
      this.gantt.change_view_mode(mode);
    }
  }

  /**
   * 根據模組 (Epic) 名稱，動態分配並固定一種 CSS 顏色 Class
   * @param moduleName 模組名稱
   * @returns 對應的 SCSS Class 名稱 (e.g., 'epic-blue')
   */
  private getCustomClassByModule(
    moduleName: string | null | undefined,
  ): string {
    if (!moduleName) return 'gantt-default';
    // 簡單 Hash 演算法，讓不同 Epic 呈現不同顏色
    const colors = [
      'epic-blue',
      'epic-green',
      'epic-purple',
      'epic-orange',
      'epic-teal',
    ];
    // 將字串透過簡易 Hash 演算法轉換為整數，確保相同的模組名稱永遠對應同一種顏色
    let hash = 0;
    for (let i = 0; i < moduleName.length; i++) {
      hash = moduleName.charCodeAt(i) + ((hash << 5) - hash);
    }
    return colors[Math.abs(hash) % colors.length];
  }

  ngOnDestroy() {
    // 釋放記憶體資源
  }
}
