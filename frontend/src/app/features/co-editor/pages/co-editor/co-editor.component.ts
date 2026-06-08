import {
  ChangeDetectorRef,
  Component,
  ElementRef,
  HostListener,
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
import { Subscription } from 'rxjs/internal/Subscription';
import { ProjectService } from '../../../../shared/service/project.service';
import { MultiSelectModule } from 'primeng/multiselect';
import { TaskAddedResult } from '../../models/task-added-response.model';
import { filter, take, throttleTime } from 'rxjs/operators';
import { fromEvent } from 'rxjs/internal/observable/fromEvent';

/**
 * 共編編輯器元件 (CoEditorComponent)
 * 負責處理 WBS 任務的即時協作。結合了 Yjs (CRDT) 處理前端即時狀態同步，
 * 並透過 CQRS 模式將意圖非同步寫入後端。
 */
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
  // 核心狀態變數
  tasks: Task[] = [];
  projectId: string = '';
  currentTenantId: string = '';

  // 用來記憶 PrimeNG 表格目前的分頁位置 (第一筆資料的 Index)，防止 Yjs 同步時表格跳回第一頁  firstOffset: number = 0;
  activeCollaborators: { name: string; color: string; initials: string }[] = [];
  private saveTimers = new Map<string, any>();

  // 基礎設施與計時器
  @ViewChild('workspace', { static: true }) workspaceRef!: ElementRef;
  remoteCursors: any[] = [];
  private mouseMoveSub: Subscription | undefined;

  // 取得或建立使用者 Session 身分，確保 F5 刷新頁面時不會產生幽靈分身
  currentUser = this.getOrCreateSessionUser();

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
    // 初始化 Yjs 監聽器
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
      this.syncYjsToAngular(); // 將 Yjs 的共享狀態推入 Angular 渲染循環
    };
  }

  ngOnInit() {
    this.currentTenantId =
      this.storageService.getLocalStorageItem(SystemStorageKey.TENANT) || 'WPG';

    // 啟動滑鼠軌跡監聽 (節流 50ms 防止高頻座標發送塞爆 WebSocket)
    this.mouseMoveSub = fromEvent<MouseEvent>(document, 'mousemove')
      .pipe(throttleTime(50))
      .subscribe((event) => {
        if (!this.workspaceRef) {
          return;
        }

        // 計算滑鼠相對於工作區 (workspace) 的相對座標
        const rect = this.workspaceRef.nativeElement.getBoundingClientRect();
        const relativeX = event.clientX - rect.left;
        const relativeY = event.clientY - rect.top;

        // 判斷是否在界內
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
        } else {
          // 關鍵驅魔：一但滑鼠出界，立刻向所有人廣播「隱藏我的鼠標」
          this.syncService.awareness.setLocalStateField('cursor', null);
        }
      });

    // 當使用者切換瀏覽器分頁 (例如去看 YouTube)，自動隱藏鼠標
    fromEvent(document, 'visibilitychange').subscribe(() => {
      if (document.hidden && this.syncService.awareness) {
        this.syncService.awareness.setLocalStateField('cursor', null);
      }
    });

    // 路由訂閱與切換專案邏輯
    this.route.paramMap.subscribe((params) => {
      const routeId = params.get('id');
      const targetId =
        routeId || this.storageService.getLocalStorageItem('last_project_id');

      if (!targetId) {
        console.warn(
          '[Router] 網址與本地快取皆無專案 ID，表格將維持完全空白。',
        );
        this.tasks = [];
        return;
      }

      // 核心防禦二：阻斷 Angular 路由雙重觸發
      // 如果要切換的房間 ID 與當前一致，直接阻擋，嚴防錯誤的 cleanup 導致畫面自毀消失！
      if (this.projectId === targetId) {
        return;
      }

      console.log(`[Router] 偵測到專案切換！目標 ProjectId: ${targetId}`);
      this.cleanupCurrentRoom();
      this.projectId = targetId;
      this.storageService.setLocalStorageItem('last_project_id', targetId);
      this.initCollaborationRoom();
    });
  }

  ngOnDestroy() {
    this.cleanupCurrentRoom();
    if (this.mouseMoveSub) {
      this.mouseMoveSub.unsubscribe();
    }
  }

  // 核心防禦一：攔截瀏覽器重新整理 (F5) 或關閉分頁
  // 確保在 WebSocket 斷線前，主動把自己的 Awareness (頭像/鼠標) 從房間內抹除
  @HostListener('window:beforeunload', ['$event'])
  unloadHandler(event: Event) {
    this.forceRemoveSelfFromRoom();
  }

  private forceRemoveSelfFromRoom() {
    if (this.syncService && this.syncService.awareness) {
      try {
        this.syncService.awareness.setLocalState(null);
      } catch (e) {}
    }
  }

  /**
   * 雙軌寫入機制 (Track A / Track B)
   * 處理畫面上任何一個輸入框或儲存格的變更
   */
  onFieldChange(taskId: string, field: string, value: any) {
    // Track A (共編軌)：零延遲寫入 Yjs 記憶體，讓線上夥伴立刻看到變化
    this.syncService.updateTaskField(taskId, field, value);

    // Track B (持久軌)：獨立儲存格別的防抖計時器，延遲 1 秒後才打後端 API (CQRS)
    const timerKey = `${taskId}-${field}`;
    if (this.saveTimers.has(timerKey))
      clearTimeout(this.saveTimers.get(timerKey));

    this.saveTimers.set(
      timerKey,
      setTimeout(() => {
        this.dispatchPersistCommand(taskId, field);
        this.saveTimers.delete(timerKey);
      }, 1000),
    );
  }

  /**
   * 意圖導向命令分流器 (CQRS Command Dispatcher)
   * 負責將使用者的操作轉譯為後端對應的更新 API
   */
  private dispatchPersistCommand(taskId: string, field: string) {
    const currentTask = this.tasks.find((t) => t.taskId === taskId);
    if (!currentTask) {
      return;
    }

    switch (field) {
      case 'name':
        this.projectService
          .updateTaskName(
            this.projectId,
            taskId,
            this.currentTenantId,
            currentTask.name,
          )
          .subscribe();
        break;
      case 'module':
        this.projectService
          .updateTaskModule(
            this.projectId,
            taskId,
            this.currentTenantId,
            currentTask.module || '',
          )
          .subscribe();
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
          .subscribe();
        break;
      case 'progress':
        this.projectService
          .updateTaskProgress(
            this.projectId,
            taskId,
            this.currentTenantId,
            Number(currentTask.progress),
          )
          .subscribe();
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
          .subscribe();
        break;
      case 'taskType':
        this.projectService
          .updateTaskType(
            this.projectId,
            taskId,
            this.currentTenantId,
            currentTask.taskType || 'FEATURE',
          )
          .subscribe();
        break;
    }
  }

  /**
   * 清理當前協作房間，釋放記憶體並重置狀態
   */
  private cleanupCurrentRoom() {
    console.log(`[System] 開始清理舊專案房間狀態... [${this.projectId}]`);
    this.forceRemoveSelfFromRoom(); // 離開時也確保抹除狀態

    if (this.syncService.sharedTasks && this.yjsObserver) {
      try {
        this.syncService.sharedTasks.unobserveDeep(this.yjsObserver);
      } catch (e) {}
    }

    this.syncService.leaveProjectRoom();
    this.tasks = [];
    this.activeSelections.clear();
    this.remoteCursors = [];
    this.activeCollaborators = [];
  }

  /**
   * 初始化協作房間
   */
  private initCollaborationRoom() {
    console.log(
      `[System] 正在初始化新專案房間: [${this.projectId}], 租戶: [${this.currentTenantId}]`,
    );

    this.syncService.joinProjectRoom(this.projectId, this.currentTenantId);
    this.syncService.sharedTasks.observeDeep(this.yjsObserver);

    // 一進房就向大家廣播自己的身分 (包含姓名與隨機指派的顏色)
    this.syncService.awareness.setLocalStateField('user', this.currentUser);

    // 監聽房間內任何 Awareness (游標/選取/身分) 的變更
    this.syncService.awareness.on('change', () => {
      const states = Array.from(
        this.syncService.awareness.getStates().values(),
      );

      this.activeSelections.clear();
      this.remoteCursors = [];
      this.activeCollaborators = [];
      const seenUsers = new Set<string>();

      states.forEach((state: any) => {
        // 1. 處理「儲存格聚焦」高亮
        if (
          state.selection &&
          state.user &&
          state.user.name !== this.currentUser.name
        ) {
          this.activeSelections.set(
            `${state.selection.taskId}-${state.selection.field}`,
            state.user,
          );
        }

        // 2. 處理「遠端鼠標」渲染座標
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

        // 3. 處理「右上角線上名單」去重顯示
        if (state.user && state.user.name !== this.currentUser.name) {
          if (!seenUsers.has(state.user.name)) {
            seenUsers.add(state.user.name);
            this.activeCollaborators.push({
              name: state.user.name,
              color: state.user.color,
              initials: state.user.name.substring(0, 2).toUpperCase(),
            });
          }
        }
      });
      this.cdr.detectChanges(); // 通知 Angular 刷新畫面
    });

    // 同步守衛 (Sync Guard)：等待 WebSocket 連線且 Yjs 狀態合併完成後，才進行 DB 檢查
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

  /**
   * 負責將 Yjs 的記憶體資料同步推入 Angular 的綁定陣列，並執行排序
   */
  private syncYjsToAngular(): void {
    this.zone.run(() => {
      const yjsTasksArray = Array.from(
        this.syncService.sharedTasks.values(),
      ) as Task[];

      //  防禦機制：萬一遭遇異常封包導致 Yjs 回傳空陣列，攔截並保護現有畫面
      if (yjsTasksArray.length === 0 && this.tasks.length > 0) {
        console.warn(
          '[System] 攔截到異常的空陣列覆寫事件，為保護畫面已忽略此次同步。',
        );
        return;
      }

      // 預設以開始日期 (startDate) 排序，若無則依 displayId
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

      this.tasks = [...yjsTasksArray]; // 產生新參考以觸發 PrimeNG 重新渲染
      this.cdr.markForCheck();
      this.cdr.detectChanges();
    });
  }

  /**
   * 資料庫載入與雙重鎖定防禦
   */
  private loadTasksFromDatabase() {
    const currentYjsSize = Array.from(
      this.syncService.sharedTasks.keys(),
    ).length;

    if (currentYjsSize === 0) {
      console.log('[System] 本地 Yjs 為空，向資料庫發出請求...');
      this.syncService
        .getProjectTasks(this.projectId, this.currentTenantId)
        .subscribe({
          next: (backendTasks: Task[]) => {
            // 核心防禦三：雙重檢查鎖定 (Double-Checked Locking)
            // 檢查在等待 HTTP API 回傳的時間差內，WebSocket 是否已搶先同步資料
            const sizeAfterHttp = Array.from(
              this.syncService.sharedTasks.keys(),
            ).length;
            if (sizeAfterHttp > 0) {
              console.warn(
                '[System] ⚠️ 遠端 Yjs 資料已在 HTTP 回傳前抵達！自動放棄資料庫樂觀覆寫。',
              );
              this.syncYjsToAngular();
              return;
            }

            console.log('[System] 寫入資料庫初始資料至 Yjs 共享記憶體...');
            this.syncService.ydoc.transact(() => {
              backendTasks.forEach((t) => {
                t.dependencies = t.dependencies || [];
                this.syncService.sharedTasks.set(t.taskId, t);
              });
            }, 'db-load');
          },
          error: (err) => console.error('[Error] 無法從後端取得任務資料:', err),
        });
    } else {
      console.log('[System] 協作房間內已有其他線上資料，直接同步至本地畫面。');
      this.syncYjsToAngular();
    }
  }

  // Awareness 輔助方法：儲存格焦點追蹤

  onCellFocus(taskId: string, field: string) {
    this.syncService.setUserSelection(taskId, field, this.currentUser);
  }

  onCellBlur() {
    this.syncService.setUserSelection(null, null, this.currentUser);
  }

  getCollaborator(taskId: string, field: string) {
    return this.activeSelections.get(`${taskId}-${field}`);
  }

  // UI 輔助方法

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
    // 支援以逗號分隔輸入多筆依賴
    const displayIdArray = inputValue
      ? inputValue
          .split(',')
          .map((id) => id.trim())
          .filter((id) => id.length > 0)
      : [];
    task.dependencies = displayIdArray;
    this.onFieldChange(task.taskId, 'dependencies', displayIdArray);
  }

  /**
   *  建立新任務機制 (預測編號 + CQRS 同步)
   * */
  addNewTask(): void {
    if (this.isCreatingTask) return;
    this.isCreatingTask = true;
    const defaultName = '新任務 ' + (this.tasks.length + 1);

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
        if (!serverTaskId) {
          return;
        }

        // 計算下一個預測的流水號 (DisplayID)
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
        // 寫入 Yjs 讓 UI 顯示
        this.handleTaskCreationSuccess(
          serverTaskId,
          predictedDisplayId,
          defaultName,
        );
        this.systemMessageService.showSuccess('建立成功', res.message);
      },
      error: (err) => {
        this.isCreatingTask = false;
        const realErrorMsg =
          err.error?.message || err.error || '後端拒絕受理新任務建立';
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

  /**
   * 轉譯 DisplayID 為 UUID 以供後端儲存
   * @param displayIds
   */
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
    if (!currentTask) {
      return;
    }
    const taskIds = this.resolveDisplayIdsToTaskIds(
      currentTask.dependencies || [],
    );
    this.projectService
      .updateTaskDependencies(
        this.projectId,
        taskId,
        this.currentTenantId,
        taskIds,
      )
      .subscribe();
  }

  /**
   * 取得或建立 Session 級別的使用者，確保重整網頁時不會被認定為新進房間的使用者
   */
  private getOrCreateSessionUser() {
    try {
      const cached = sessionStorage.getItem('chrono_weaver_user');
      if (cached) return JSON.parse(cached);
    } catch (e) {}
    const newUser = {
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
    try {
      sessionStorage.setItem('chrono_weaver_user', JSON.stringify(newUser));
    } catch (e) {}
    return newUser;
  }

  // 漏斗篩選器所需之 Getter (動態擷取不重複選項)

  /**
   * 取得 Module 漏斗篩選器下拉選單
   */
  get moduleOptions() {
    const modules = Array.from(
      new Set(
        this.tasks.map((t) => t.module).filter((m) => m && m.trim() !== ''),
      ),
    );
    return modules.map((m) => ({ label: m, value: m }));
  }

  /**
   * 取得 任務類型 漏斗篩選器下拉選單
   */
  get taskTypeOptions() {
    const types = Array.from(
      new Set(
        this.tasks.map((t) => t.taskType).filter((t) => t && t.trim() !== ''),
      ),
    );
    return types.map((t) => ({ label: t, value: t }));
  }

  /**
   * 取得 指派人 漏斗篩選器下拉選單
   */
  get assigneeOptions() {
    const assignees = Array.from(
      new Set(
        this.tasks.map((t) => t.assigneeId).filter((a) => a && a.trim() !== ''),
      ),
    );
    return assignees.map((a) => ({ label: a, value: a }));
  }
}
