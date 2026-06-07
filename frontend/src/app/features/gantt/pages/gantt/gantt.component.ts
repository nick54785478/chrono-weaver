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
import { ProjectService } from '../../../../shared/service/project.service';
import { StorageService } from '../../../../core/services/storage.service';
import { SystemStorageKey } from '../../../../core/enums/system-storage.enum';
import { Task } from '../../../co-editor/models/task.model';

@Component({
  selector: 'app-gantt',
  standalone: true,
  imports: [CommonModule, ButtonModule, SelectButtonModule, FormsModule],
  templateUrl: './gantt.component.html',
  styleUrl: './gantt.component.scss',
})
export class GanttComponent implements OnInit, AfterViewInit, OnDestroy {
  @ViewChild('gantt_canvas', { static: true }) ganttCanvas!: ElementRef;

  projectId: string = '';
  tenantId: string = '';
  gantt: any;

  // 視圖模式選項
  // 🌟 1. 加上強制轉型，讓 TS 知道這不是普通的字串
  viewModes = [
    { label: '日', value: 'Day' },
    { label: '週', value: 'Week' },
    { label: '月', value: 'Month' },
  ];

  // 🌟 1. 新增存放圖例資料的變數
  epicLegend: { name: string; bgClass: string }[] = [];
  // 🌟 將變數型別鎖定為官方定義的 Gantt.viewMode
  selectedViewMode: Gantt.viewMode = 'Day';

  constructor(
    private route: ActivatedRoute,
    private projectService: ProjectService,
    private storageService: StorageService,
  ) {}

  ngOnInit() {
    this.tenantId =
      this.storageService.getLocalStorageItem(SystemStorageKey.TENANT) || 'WPG';

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
    // 確保 SVG 容器已準備好
  }

  loadGanttData() {
    this.projectService
      .getProjectTasks(this.projectId, this.tenantId)
      .subscribe((tasks) => {
        this.initGantt(tasks);
      });
  }

  private initGantt(tasks: Task[]) {
    const validTasks = tasks.filter(
      (t) =>
        t.startDate &&
        t.startDate.trim() !== '' &&
        t.endDate &&
        t.endDate.trim() !== '',
    );

    const validTaskIds = new Set(validTasks.map((t) => t.taskId));

    // ==========================================
    // 🌟 2. 動態提煉圖例 (Legend)
    // 抓出所有不重複的 module (Epic)，並計算出對應的顏色 class
    // ==========================================
    const uniqueModules = Array.from(
      new Set(
        validTasks.map((t) => t.module).filter((m) => m && m.trim() !== ''),
      ),
    );

    this.epicLegend = uniqueModules.map((moduleName) => ({
      name: moduleName as string,
      // 這裡我們加上 bg- 前綴，方便給 HTML 的 <div> 當背景色使用
      bgClass: 'bg-' + this.getCustomClassByModule(moduleName),
    }));
    // ==========================================

    const ganttTasks: Gantt.Task[] = validTasks.map((t) => {
      // ... (這裡維持你上一回合原本的 mapping 邏輯) ...
      const validDependencies = (t.dependencies || []).filter((depId) =>
        validTaskIds.has(depId),
      );

      let startDate = new Date(t.startDate as string);
      let endDate = new Date(t.endDate as string);

      if (endDate < startDate) {
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
        custom_class: this.getCustomClassByModule(t.module),
      };
    });

    if (ganttTasks.length === 0) {
      console.warn('目前沒有包含完整日期的任務可供繪製甘特圖');
      return;
    }

    if (this.gantt) {
      this.ganttCanvas.nativeElement.innerHTML = '';
    }

    this.gantt = new Gantt(this.ganttCanvas.nativeElement, ganttTasks, {
      view_mode: this.selectedViewMode,
      language: 'zh',
      on_click: (task: Gantt.Task) => console.log('點擊任務:', task),
      on_date_change: (task: Gantt.Task, start: Date, end: Date) => {
        console.log('日期變更:', task, start, end);
      },
      on_progress_change: (task: Gantt.Task, progress: number) => {
        console.log('進度變更:', task, progress);
      },
    });
  }

  // 🌟 參數型別也改為 Gantt.viewMode
  changeViewMode(mode: Gantt.viewMode) {
    this.selectedViewMode = mode;
    if (this.gantt) {
      this.gantt.change_view_mode(mode);
    }
  }

  // 🌟 根據模組名稱分配 CSS Class
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
    let hash = 0;
    for (let i = 0; i < moduleName.length; i++) {
      hash = moduleName.charCodeAt(i) + ((hash << 5) - hash);
    }
    return colors[Math.abs(hash) % colors.length];
  }

  ngOnDestroy() {
    // 清理資源
  }
}
