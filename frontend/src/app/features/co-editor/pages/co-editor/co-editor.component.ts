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
import * as Y from 'yjs';
import { TableModule } from 'primeng/table';
import { InputTextModule } from 'primeng/inputtext';
import { TagModule } from 'primeng/tag';
import { ProgressBarModule } from 'primeng/progressbar';
import { AvatarModule } from 'primeng/avatar';
import { AvatarGroupModule } from 'primeng/avatargroup';
import { GanttSyncService } from '../../../../shared/services/gantt-sync.service';
import { Task } from '../../models/task.model';
import { ActivatedRoute, Router } from '@angular/router';
import { StorageService } from '../../../../core/services/storage.service';
import { SystemStorageKey } from '../../../../core/enums/system-storage.enum';
import { TooltipModule } from 'primeng/tooltip';
import { ButtonModule } from 'primeng/button';
import { SystemMessageService } from '../../../../shared/services/system-message.service';
import { Subscription } from 'rxjs/internal/Subscription';
import { ProjectService } from '../../../../shared/services/project.service';
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
  tasks: any[] = [];
  projectId: string = '';
  currentTenantId: string = '';
  firstOffset: number = 0;
  activeCollaborators: { name: string; color: string; initials: string }[] = [];
  private saveTimers = new Map<string, any>();

  @ViewChild('workspace', { static: true }) workspaceRef!: ElementRef;
  remoteCursors: any[] = [];
  private mouseMoveSub: Subscription | undefined;

  currentUser = this.getOrCreateSessionUser();
  activeSelections: Map<string, any> = new Map();
  private yjsObserver: (events: any, transaction: any) => void;
  isCreatingTask = false;

  constructor(
    private router: Router,
    private syncService: GanttSyncService,
    private projectService: ProjectService, // 替換為實際型別
    private cdr: ChangeDetectorRef,
    private route: ActivatedRoute,
    private storageService: StorageService, // 替換為實際型別
    private systemMessageService: SystemMessageService, // 替換為實際型別
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
      this.syncYjsToAngular();
    };
  }

  ngOnInit() {
    this.currentTenantId =
      this.storageService.getLocalStorageItem(SystemStorageKey.TENANT) || '';

    this.mouseMoveSub = fromEvent<MouseEvent>(document, 'mousemove')
      .pipe(throttleTime(50))
      .subscribe((event) => {
        if (!this.workspaceRef) return;
        const rect = this.workspaceRef.nativeElement.getBoundingClientRect();
        const relativeX = event.clientX - rect.left;
        const relativeY = event.clientY - rect.top;

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
          this.syncService.awareness.setLocalStateField('cursor', null);
        }
      });

    fromEvent(document, 'visibilitychange').subscribe(() => {
      if (document.hidden && this.syncService.awareness) {
        this.syncService.awareness.setLocalStateField('cursor', null);
      }
    });

    this.route.paramMap.subscribe((params) => {
      const routeId = params.get('id');
      const targetId =
        routeId || this.storageService.getLocalStorageItem('last_project_id');

      if (!targetId) {
        this.tasks = [];
        return;
      }

      if (this.projectId === targetId) return;

      this.cleanupCurrentRoom();
      this.projectId = targetId;
      this.storageService.setLocalStorageItem('last_project_id', targetId);
      this.initCollaborationRoom();
    });
  }

  ngOnDestroy() {
    this.cleanupCurrentRoom();
    if (this.mouseMoveSub) this.mouseMoveSub.unsubscribe();
  }

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

  onFieldChange(taskId: string, field: string, value: any) {
    this.syncService.updateTaskField(taskId, field, value);

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

  private dispatchPersistCommand(taskId: string, field: string) {
    const currentTask = this.tasks.find((t) => t.taskId === taskId);
    if (!currentTask) return;

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
        this.projectService
          .updateTaskSchedule(
            this.projectId,
            taskId,
            this.currentTenantId,
            currentTask.startDate || null,
            currentTask.endDate || null,
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

  private cleanupCurrentRoom() {
    this.forceRemoveSelfFromRoom();
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

  private initCollaborationRoom() {
    this.syncService.joinProjectRoom(this.projectId, this.currentTenantId);
    this.syncService.sharedTasks.observeDeep(this.yjsObserver);
    this.syncService.awareness.setLocalStateField('user', this.currentUser);

    this.syncService.awareness.on('change', () => {
      const states = Array.from(
        this.syncService.awareness.getStates().values(),
      );
      this.activeSelections.clear();
      this.remoteCursors = [];
      this.activeCollaborators = [];
      const seenUsers = new Set<string>();

      states.forEach((state: any) => {
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
      this.cdr.detectChanges();
    });

    this.syncService.isReady$
      .pipe(
        filter((isReady) => isReady === true),
        take(1),
      )
      .subscribe(() => {
        this.loadTasksFromDatabase();
      });
  }

  private syncYjsToAngular(): void {
    this.zone.run(() => {
      // 🌟 Y.Map 解析為 JSON
      const yjsTasksArray = Array.from(
        this.syncService.sharedTasks.values(),
      ).map((val: any) => {
        return typeof val.toJSON === 'function' ? val.toJSON() : val;
      }) as any[];

      if (yjsTasksArray.length === 0 && this.tasks.length > 0) return;

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
    });
  }

  private loadTasksFromDatabase() {
    const currentYjsSize = Array.from(
      this.syncService.sharedTasks.keys(),
    ).length;

    if (currentYjsSize === 0) {
      this.syncService
        .getProjectTasks(this.projectId, this.currentTenantId)
        .subscribe({
          next: (backendTasks: any[]) => {
            const sizeAfterHttp = Array.from(
              this.syncService.sharedTasks.keys(),
            ).length;
            if (sizeAfterHttp > 0) {
              this.syncYjsToAngular();
              return;
            }

            this.syncService.ydoc.transact(() => {
              backendTasks.forEach((t) => {
                t.dependencies = t.dependencies || [];
                // 🌟 將 JSON 資料封裝為 Y.Map 存入
                const taskYMap = new Y.Map();
                Object.keys(t).forEach((k) => taskYMap.set(k, (t as any)[k]));
                this.syncService.sharedTasks.set(t.taskId, taskYMap);
              });
            }, 'db-load');
          },
        });
    } else {
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

  updateDependencies(task: any, inputValue: string) {
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

    this.syncService.createTask(defaultName).subscribe({
      next: (res: any) => {
        this.isCreatingTask = false;
        if (res && res.success === false) {
          this.systemMessageService.showError(
            '同步失敗',
            res.message || '領域層拒絕此操作',
          );
          return;
        }

        const serverTaskId = res.taskId;
        if (!serverTaskId) return;

        let prefix = localStorage.getItem('currentProjectCode') || 'TSK';
        let maxNumber = 0;

        if (this.tasks.length > 0) {
          // 🌟 TypeScript 嚴格模式安全檢查
          const firstValidTask = this.tasks.find(
            (t) => t.displayId && t.displayId.includes('-'),
          );
          if (firstValidTask && firstValidTask.displayId) {
            prefix = firstValidTask.displayId.split('-')[0];
          }

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
    const newTaskData: any = {
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

    // 🌟 新建時也封裝為 Y.Map 存入
    const taskYMap = new Y.Map();
    Object.keys(newTaskData).forEach((k) => taskYMap.set(k, newTaskData[k]));

    this.syncService.ydoc.transact(() => {
      this.syncService.sharedTasks.set(taskId, taskYMap);
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

  navigateToSummary(): void {
    this.router.navigate(['/project-summary']); // 確認你的路由路徑
  }

  navigateToGantt(): void {
    this.router.navigate(['/gantt']); // 確認你的路由路徑
  }
}
