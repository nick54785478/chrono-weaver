import {
  ChangeDetectorRef,
  Component,
  ElementRef,
  NgZone,
  OnDestroy,
  OnInit,
  ViewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { TableModule } from 'primeng/table';
import { InputTextModule } from 'primeng/inputtext';
import { TagModule } from 'primeng/tag';
import { ProgressBarModule } from 'primeng/progressbar';
import { AvatarModule } from 'primeng/avatar';
import { AvatarGroupModule } from 'primeng/avatargroup';
import { GanttSyncService } from '../../../../shared/service/gantt-sync.service';
import { Task } from '../../models/task.model';
import { ActivatedRoute } from '@angular/router';
import { StorageService } from '../../../../core/services/storage.service';
import { SystemStorageKey } from '../../../../core/enums/system-storage.enum';
import { TooltipModule } from 'primeng/tooltip';
import { ButtonModule } from 'primeng/button';
import { SystemMessageService } from '../../../../shared/service/system-message.service';
import { CommandResult } from '../../../../shared/models/command-result.model';
import { Subject } from 'rxjs/internal/Subject';
import { Subscription } from 'rxjs/internal/Subscription';
import { ProjectService } from '../../../../shared/service/project.service';
import { MultiSelectModule } from 'primeng/multiselect';
import { TaskAddedResult } from '../../models/task-added-response.model';
import { filter, take, throttleTime } from 'rxjs/operators';
import { fromEvent } from 'rxjs/internal/observable/fromEvent';

@Component({
  selector: 'app-co-editor',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TableModule,
    InputTextModule,
    TagModule,
    ButtonModule,
    ProgressBarModule,
    AvatarModule,
    AvatarGroupModule,
    TooltipModule,
    MultiSelectModule,
  ],
  templateUrl: './co-editor.component.html',
  styleUrl: './co-editor.component.scss',
})
export class CoEditorComponent implements OnInit, OnDestroy {
  tasks: Task[] = [];

  projectId: string = '';
  currentTenantId: string = '';

  // 🌟 獨立細胞防抖計時器沙盒
  private saveTimers = new Map<string, any>();

  // 🌟 遠端鼠標追蹤所需變數
  @ViewChild('workspace', { static: true }) workspaceRef!: ElementRef;
  remoteCursors: any[] = [];
  private mouseMoveSub: Subscription | undefined;

  currentUser = {
    name:
      'User ' +
      Math.floor(Math.random() * 1000)
        .toString()
        .padStart(3, '0'),
    color:
      '#' +
      Math.floor(Math.random() * 16777215)
        .toString(16)
        .padStart(6, '0'),
  };

  activeSelections: Map<string, any> = new Map();
  private yjsObserver: (events: any, transaction: any) => void;
  isCreatingTask = false;

  constructor(
    private syncService: GanttSyncService,
    private projectService: ProjectService,
    private cdr: ChangeDetectorRef,
    private route: ActivatedRoute,
    private storageService: StorageService,
    private systemMessageService: SystemMessageService,
    private zone: NgZone,
  ) {
    this.yjsObserver = (events: any, transaction: any) => {
      if (
        transaction.local &&
        transaction.origin !== 'db-load' &&
        transaction.origin !== 'task-create'
      ) {
        return;
      }

      console.log(
        `[Yjs Observer] 允許渲染畫面！觸發來源: ${transaction.origin || '遠端協作者'}`,
      );
      this.syncYjsToAngular();
    };
  }

  ngOnInit() {
    this.currentTenantId =
      this.storageService.getLocalStorageItem(SystemStorageKey.TENANT) || 'WPG';

    // 🌟 啟動本地滑鼠軌跡監聽 (節流 50ms 避免高頻發送塞爆通道)
    this.mouseMoveSub = fromEvent<MouseEvent>(document, 'mousemove')
      .pipe(throttleTime(50))
      .subscribe((event) => {
        if (!this.workspaceRef) return;

        const rect = this.workspaceRef.nativeElement.getBoundingClientRect();
        const relativeX = event.clientX - rect.left;
        const relativeY = event.clientY - rect.top;

        // 限制只有在表格工作區範疇內移動時，才同步鼠標座標
        if (
          relativeX >= 0 &&
          relativeY >= 0 &&
          relativeX <= rect.width &&
          relativeY <= rect.height
        ) {
          this.syncService.awareness.setLocalStateField('cursor', {
            x: relativeX,
            y: relativeY,
          });
        }
      });

    this.route.paramMap.subscribe((params) => {
      const routeId = params.get('id');

      if (routeId) {
        console.log(`[Router] 偵測到專案切換！目標 ProjectId: ${routeId}`);
        this.cleanupCurrentRoom();
        this.projectId = routeId;
        this.storageService.setLocalStorageItem('last_project_id', routeId);
        this.initCollaborationRoom();
      } else {
        const lastSavedProjectId =
          this.storageService.getLocalStorageItem('last_project_id');

        if (lastSavedProjectId) {
          console.log(
            `[Router] 網址無 ID，自動啟用上次瀏覽的專案: ${lastSavedProjectId}`,
          );
          this.cleanupCurrentRoom();
          this.projectId = lastSavedProjectId;
          this.initCollaborationRoom();
        } else {
          console.warn(
            '[Router] 網址與本地快取皆無專案 ID，表格將維持完全空白。',
          );
          this.tasks = [];
        }
      }
    });
  }

  /**
   * === 欄位變更與游標攔截 ===
   */
  onFieldChange(taskId: string, field: string, value: any) {
    // 1. Track A（協作軌）：零延遲寫入 Yjs 讓線上夥伴即時看到
    this.syncService.updateTaskField(taskId, field, value);

    // 2. Track B（持久軌）：細胞級別防抖保護，徹底解耦改 A 壞 B 問題
    const timerKey = `${taskId}-${field}`;

    if (this.saveTimers.has(timerKey)) {
      clearTimeout(this.saveTimers.get(timerKey));
    }

    this.saveTimers.set(
      timerKey,
      setTimeout(() => {
        this.dispatchPersistCommand(taskId, field);
        this.saveTimers.delete(timerKey);
      }, 1000),
    );
  }

  /**
   * 🌟 核心路由分流器
   */
  private dispatchPersistCommand(taskId: string, field: string) {
    const currentTask = this.tasks.find((t) => t.taskId === taskId);
    if (!currentTask) return;

    console.log(`[CQRS Pipeline] 意圖導向命令出發。欄位: [${field}]`);

    switch (field) {
      case 'name':
        this.projectService
          .updateTaskName(
            this.projectId,
            taskId,
            this.currentTenantId,
            currentTask.name,
          )
          .subscribe({
            error: (err) => console.error('[CQRS Error] 名稱持久化失敗:', err),
          });
        break;

      case 'module':
        this.projectService
          .updateTaskModule(
            this.projectId,
            taskId,
            this.currentTenantId,
            currentTask.module || '',
          )
          .subscribe({
            error: (err) => console.error('[CQRS Error] 模組持久化失敗:', err),
          });
        break;

      case 'startDate':
      case 'endDate':
        const safeStart = currentTask.startDate ? currentTask.startDate : null;
        const safeEnd = currentTask.endDate ? currentTask.endDate : null;

        this.projectService
          .updateTaskSchedule(
            this.projectId,
            taskId,
            this.currentTenantId,
            safeStart,
            safeEnd,
          )
          .subscribe({
            error: (err) => console.error('[CQRS Error] 時程持久化失敗:', err),
          });
        break;

      case 'progress':
        this.projectService
          .updateTaskProgress(
            this.projectId,
            taskId,
            this.currentTenantId,
            Number(currentTask.progress),
          )
          .subscribe({
            error: (err) => console.error('[CQRS Error] 進度持久化失敗:', err),
          });
        break;

      case 'dependencies':
        this.persistDependencies(taskId);
        break;

      case 'assigneeId':
      case 'reviewerId':
        this.projectService
          .updateTaskPersonnel(
            this.projectId,
            taskId,
            this.currentTenantId,
            currentTask.assigneeId || '',
            currentTask.reviewerId || '',
          )
          .subscribe({
            error: (err) => console.error('[CQRS Error] 人員持久化失敗:', err),
          });
        break;

      case 'taskType':
        this.projectService
          .updateTaskType(
            this.projectId,
            taskId,
            this.currentTenantId,
            currentTask.taskType || 'FEATURE',
          )
          .subscribe({
            error: (err) => console.error('[CQRS Error] 分類持久化失敗:', err),
          });
        break;
    }
  }

  ngOnDestroy() {
    this.cleanupCurrentRoom();
    if (this.mouseMoveSub) {
      this.mouseMoveSub.unsubscribe();
    }
  }

  private cleanupCurrentRoom() {
    console.log(`[System] 開始清理舊專案房間狀態... [${this.projectId}]`);

    if (this.syncService.sharedTasks && this.yjsObserver) {
      try {
        this.syncService.sharedTasks.unobserveDeep(this.yjsObserver);
      } catch (e) {}
    }

    this.syncService.leaveProjectRoom();
    this.tasks = [];
    this.activeSelections.clear();
    this.remoteCursors = [];
  }

  private initCollaborationRoom() {
    console.log(
      `[System] 正在初始化新專案房間: [${this.projectId}], 租戶: [${this.currentTenantId}]`,
    );

    this.syncService.joinProjectRoom(this.projectId, this.currentTenantId);
    this.syncService.sharedTasks.observeDeep(this.yjsObserver);

    this.syncService.awareness.on('change', () => {
      const states = Array.from(
        this.syncService.awareness.getStates().values(),
      );
      this.activeSelections.clear();
      this.remoteCursors = []; // 🌟 每次狀態變更重置遠端滑鼠陣列

      states.forEach((state: any) => {
        // 1. 處理單元格聚焦高亮
        if (
          state.selection &&
          state.user &&
          state.user.name !== this.currentUser.name
        ) {
          const key = `${state.selection.taskId}-${state.selection.field}`;
          this.activeSelections.set(key, state.user);
        }

        // 2. 🌟 處理遠端同步過來的實時鼠標座標
        if (
          state.cursor &&
          state.user &&
          state.user.name !== this.currentUser.name
        ) {
          this.remoteCursors.push({
            name: state.user.name,
            color: state.user.color,
            x: state.cursor.x,
            y: state.cursor.y,
          });
        }
      });
      this.cdr.detectChanges();
    });

    // ==========================================
    // 🌟 核心同步守衛：等 Yjs 回報握手與雲端同步完成，才可以查記憶體狀態
    // ==========================================
    this.syncService.isReady$
      .pipe(
        filter((isReady) => isReady === true),
        take(1),
      )
      .subscribe(() => {
        console.log(
          '[System] 🟢 WebSocket 遠端連線與同步已完成，開始檢查共享記憶體狀態...',
        );
        this.loadTasksFromDatabase();
      });
  }

  private syncYjsToAngular(): void {
    this.zone.run(() => {
      console.log('[Yjs Sync] 正在 Angular Zone 內執行資料同步...');

      const yjsTasksArray = Array.from(
        this.syncService.sharedTasks.values(),
      ) as Task[];

      // 🌟 核心排序邏輯：以開始日期 (startDate) 為第一主鍵排序
      yjsTasksArray.sort((a, b) => {
        const dateA = a.startDate ? new Date(a.startDate).getTime() : Infinity;
        const dateB = b.startDate ? new Date(b.startDate).getTime() : Infinity;

        if (dateA === dateB) {
          const idA = a.displayId || '';
          const idB = b.displayId || '';
          return idA.localeCompare(idB, undefined, { numeric: true });
        }

        return dateA - dateB;
      });

      this.tasks = [...yjsTasksArray];

      this.cdr.markForCheck();
      this.cdr.detectChanges();

      console.log(
        `[Yjs Sync] 同步完成，已依開始日期排序。筆數: ${this.tasks.length}`,
      );
    });
  }

  private loadTasksFromDatabase() {
    const currentYjsSize = Array.from(
      this.syncService.sharedTasks.keys(),
    ).length;
    console.log(
      `[System] 當前 Yjs 共享記憶體內的任務筆數為: ${currentYjsSize}`,
    );

    if (currentYjsSize === 0) {
      console.log(
        '[System] 偵測到全新的協作房間，正在從後端資料庫初始化共享記憶體...',
      );
      this.syncService
        .getProjectTasks(this.projectId, this.currentTenantId)
        .subscribe({
          next: (backendTasks: Task[]) => {
            this.syncService.ydoc.transact(() => {
              backendTasks.forEach((t) => {
                t.dependencies = t.dependencies || [];
                this.syncService.sharedTasks.set(t.taskId, t);
              });
            }, 'db-load');
          },
          error: (err) => {
            console.error('[Error] 無法從後端取得任務資料:', err);
          },
        });
    } else {
      console.log('[System] 協作房間內已有其他線上資料，直接同步至本地畫面。');
      this.syncYjsToAngular();
    }
  }

  onCellFocus(taskId: string, field: string) {
    this.syncService.setUserSelection(taskId, field, this.currentUser);
  }

  onCellBlur() {
    this.syncService.setUserSelection(null, null, this.currentUser);
  }

  getCollaborator(taskId: string, field: string) {
    return this.activeSelections.get(`${taskId}-${field}`);
  }

  getDerivedStatus(progress: number): {
    label: string;
    severity: 'success' | 'info' | 'warn';
  } {
    if (progress === 100) return { label: '已完成', severity: 'success' };
    if (progress > 0) return { label: '進行中', severity: 'info' };
    return { label: '待處理', severity: 'warn' };
  }

  getDependenciesText(dependencies: string[]): string {
    if (!dependencies || dependencies.length === 0) return '';

    return dependencies
      .map((taskId) => {
        const task = this.tasks.find((t) => t.taskId === taskId);
        return task ? task.displayId || taskId : taskId;
      })
      .join(', ');
  }

  updateDependencies(task: Task, inputValue: string) {
    const displayIdArray = inputValue
      ? inputValue
          .split(',')
          .map((id) => id.trim())
          .filter((id) => id.length > 0)
      : [];

    task.dependencies = displayIdArray;
    this.onFieldChange(task.taskId, 'dependencies', displayIdArray);
  }

  addNewTask(): void {
    if (this.isCreatingTask) return;

    this.isCreatingTask = true;
    const defaultName = '新任務 ' + (this.tasks.length + 1);

    console.log('[CQRS] 正在發送建立任務 Command 至後端 Actor...');

    this.syncService.createTask(defaultName).subscribe({
      next: (res: TaskAddedResult) => {
        this.isCreatingTask = false;

        if (res && res.success === false) {
          this.systemMessageService.showError(
            '同步失敗',
            res.message || '領域層拒絕此操作',
          );
          return;
        }

        const serverTaskId = res.taskId;
        const successMessage = res.message;

        if (!serverTaskId) {
          console.error('[CQRS Error] 後端回傳的 ID 異常！', res);
          return;
        }

        const cachedCode = localStorage.getItem('currentProjectCode');
        const prefix = cachedCode || 'TSK';
        let maxNumber = 0;

        if (this.tasks.length > 0) {
          for (const t of this.tasks) {
            if (t.displayId && t.displayId.includes('-')) {
              const parts = t.displayId.split('-');
              const num = parseInt(parts[1], 10);
              if (!isNaN(num) && num > maxNumber) {
                maxNumber = num;
              }
            }
          }
        }

        const predictedDisplayId = `${prefix}-${maxNumber + 1}`;

        console.log(
          `[CQRS] 任務建立成功！ID: ${serverTaskId}, 預測編號: ${predictedDisplayId}`,
        );

        this.handleTaskCreationSuccess(
          serverTaskId,
          predictedDisplayId,
          defaultName,
        );

        this.systemMessageService.showSuccess('建立成功', successMessage);
      },
      error: (err) => {
        this.isCreatingTask = false;
        const realErrorMsg =
          err.error?.message || err.error || '後端拒絕受理新任務建立';
        console.error('[CQRS Error] 伺服器錯誤:', err);
        this.systemMessageService.showError(
          '建立失敗',
          typeof realErrorMsg === 'string'
            ? realErrorMsg
            : '發生未預期的系統錯誤',
        );
      },
    });
  }

  private handleTaskCreationSuccess(
    taskId: string,
    displayId: string,
    taskName: string,
  ) {
    console.log(
      `[CQRS] 準備將任務實體對接至協作記憶體。ID: ${taskId}, 編號: ${displayId}`,
    );

    const newTaskData: Task = {
      taskId: taskId,
      displayId: displayId,
      name: taskName,
      module: null,
      taskType: 'FEATURE',
      startDate: null,
      endDate: null,
      progress: 0,
      dependencies: [],
    };

    this.syncService.ydoc.transact(() => {
      this.syncService.sharedTasks.set(taskId, newTaskData);
    }, 'task-create');

    this.cdr.detectChanges();
  }

  private resolveDisplayIdsToTaskIds(displayIds: string[]): string[] {
    return displayIds
      .map((dId) => {
        const trimmedId = dId.trim();
        const task = this.tasks.find((t) => t.displayId === trimmedId);
        return task ? task.taskId : null;
      })
      .filter((id): id is string => id !== null);
  }

  private persistDependencies(taskId: string) {
    const currentTask = this.tasks.find((t) => t.taskId === taskId);
    if (!currentTask) return;

    const rawDisplayIds = currentTask.dependencies || [];
    const taskIds = this.resolveDisplayIdsToTaskIds(rawDisplayIds);

    console.log(
      `[CQRS] 轉譯 DisplayID [${rawDisplayIds}] -> TaskID [${taskIds}]`,
    );

    this.projectService
      .updateTaskDependencies(
        this.projectId,
        taskId,
        this.currentTenantId,
        taskIds,
      )
      .subscribe({
        next: () => console.log(`[CQRS Success] 相依性更新成功`),
        error: (err) => console.error('[CQRS Error] 相依性更新失敗:', err),
      });
  }

  // ==========================================
  // 🌟 動態提煉表格 Excel 篩選選項 (Excel-like Filters)
  // ==========================================
  get moduleOptions() {
    const modules = Array.from(
      new Set(
        this.tasks.map((t) => t.module).filter((m) => m && m.trim() !== ''),
      ),
    );
    return modules.map((m) => ({ label: m, value: m }));
  }

  get taskTypeOptions() {
    const types = Array.from(
      new Set(
        this.tasks.map((t) => t.taskType).filter((t) => t && t.trim() !== ''),
      ),
    );
    return types.map((t) => ({ label: t, value: t }));
  }

  get assigneeOptions() {
    const assignees = Array.from(
      new Set(
        this.tasks.map((t) => t.assigneeId).filter((a) => a && a.trim() !== ''),
      ),
    );
    return assignees.map((a) => ({ label: a, value: a }));
  }
}
