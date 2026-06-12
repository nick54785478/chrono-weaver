import { Injectable, OnDestroy } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { RxStomp } from '@stomp/rx-stomp';
import * as Y from 'yjs';
import {
  Awareness,
  applyAwarenessUpdate,
  encodeAwarenessUpdate,
} from 'y-protocols/awareness';
import { BehaviorSubject, Subject, Subscription } from 'rxjs';
import { debounceTime, tap, map, take } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import { CommandResult } from '../models/command-result.model';
import { TaskAddedResult } from '../../features/co-editor/models/task-added-response.model';

/**
 * GanttSyncService: 專案甘特圖與 WBS 任務的即時同步引擎
 * * 核心架構設計：雙軌並行
 * 1. Track A (共編軌): 透過 Yjs + WebSocket (STOMP) 達成毫秒級的記憶體狀態同步。
 * 2. Track B (持久軌): 透過 RxJS 防抖 (Debounce) 攔截本地變更，轉譯為 CQRS Command 發送至後端 API。
 */
@Injectable({
  providedIn: 'root',
})
export class GanttSyncService implements OnDestroy {
  private rxStomp = new RxStomp();
  public ydoc!: Y.Doc;
  public sharedTasks!: Y.Map<any>;
  public awareness!: Awareness;

  public isReady$ = new BehaviorSubject<boolean>(false);

  private baseUrl = environment.apiEndpoint;
  private currentProjectId: string | null = null;
  private currentTenantId: string = '';
  private subscriptions: Subscription[] = [];

  private cqrsSaveSubject = new Subject<{ taskId: string; field: string }>();

  constructor(private http: HttpClient) {
    this.setupCqrsPipeline();
  }

  ngOnDestroy() {
    this.leaveProjectRoom();
  }

  public joinProjectRoom(projectId: string, tenantId: string) {
    this.isReady$.next(false);

    if (this.currentProjectId === projectId) {
      return;
    }
    this.currentProjectId = projectId;
    this.currentTenantId = tenantId;

    // 🌟 徹底阻絕 CRDT 歷史錯亂問題：每次進房都建立全新的 Y.Doc
    this.ydoc = new Y.Doc();
    this.sharedTasks = this.ydoc.getMap('tasks');
    this.awareness = new Awareness(this.ydoc);

    this.rxStomp.configure({
      brokerURL: 'ws://localhost:8080/ws-collaborate/websocket',
      reconnectDelay: 5000,
    });
    this.rxStomp.activate();

    this.setupYjsSync(projectId);

    this.rxStomp.connected$.pipe(take(1)).subscribe(() => {
      console.log(
        '[WebSocket] STOMP 連線成功！發送同步請求等待老鳥派發資料...',
      );

      // 🌟 發送魔法封包，向房間內的老鳥索取最新完整狀態 (Late Joiner 機制)
      this.rxStomp.publish({
        destination: `/app/projects/${projectId}/yjs`,
        binaryBody: new Uint8Array([0, 0, 0, 0]),
      });

      setTimeout(() => {
        this.isReady$.next(true);
      }, 2500);
    });
  }

  public leaveProjectRoom() {
    this.subscriptions.forEach((sub) => sub.unsubscribe());
    this.subscriptions = [];
    this.currentProjectId = null;

    if (this.rxStomp.active) {
      this.rxStomp.deactivate();
    }

    if (this.ydoc) {
      this.ydoc.destroy();
      // @ts-ignore
      this.ydoc = null;
    }
  }

  private setupYjsSync(projectId: string) {
    // 1. 監聽遠端資料更新
    const topicSub = this.rxStomp
      .watch(`/topic/projects/${projectId}/yjs`)
      .subscribe((message) => {
        if (this.ydoc) {
          const payload = new Uint8Array(message.binaryBody);

          // 🌟 攔截同步請求信號
          if (
            payload.length === 4 &&
            payload[0] === 0 &&
            payload[1] === 0 &&
            payload[2] === 0 &&
            payload[3] === 0
          ) {
            if (
              this.sharedTasks &&
              Array.from(this.sharedTasks.keys()).length > 0
            ) {
              const fullState = Y.encodeStateAsUpdate(this.ydoc);
              this.rxStomp.publish({
                destination: `/app/projects/${projectId}/yjs`,
                binaryBody: fullState,
              });
            }
            return;
          }

          // 🌟 標記來源為 remote，打破無限迴圈反射風暴
          Y.applyUpdate(this.ydoc, payload, 'remote');
        }
      });
    this.subscriptions.push(topicSub);

    // 2. 本地更新推播
    this.ydoc.on('update', (update: Uint8Array, origin: any) => {
      if (origin !== 'remote') {
        this.rxStomp.publish({
          destination: `/app/projects/${projectId}/yjs`,
          binaryBody: update,
        });
      }
    });

    // 3. 監聽遠端 Awareness
    const awarenessSub = this.rxStomp
      .watch(`/topic/projects/${projectId}/awareness`)
      .subscribe((message) => {
        if (this.awareness) {
          applyAwarenessUpdate(
            this.awareness,
            new Uint8Array(message.binaryBody),
            this,
          );
        }
      });
    this.subscriptions.push(awarenessSub);

    // 4. 本地 Awareness 推播
    this.awareness.on('update', (changes: any, origin: any) => {
      if (origin !== this) {
        const update = encodeAwarenessUpdate(
          this.awareness,
          changes.added.concat(changes.updated, changes.removed),
        );
        this.rxStomp.publish({
          destination: `/app/projects/${projectId}/awareness`,
          binaryBody: update,
        });
      }
    });
  }

  private setupCqrsPipeline() {
    const saveSub = this.cqrsSaveSubject
      .pipe(
        debounceTime(1000),
        tap((update) =>
          console.log(
            `[CQRS] 準備持久化任務 [${update.taskId}] 的 [${update.field}] 變更`,
          ),
        ),
      )
      .subscribe((update) => {
        if (!this.currentProjectId || !this.sharedTasks) return;

        const taskId = update.taskId;
        const taskMap = this.sharedTasks.get(taskId) as Y.Map<any>;
        if (!taskMap) return;

        const taskUrl = `${this.baseUrl}/projects/${this.currentProjectId}/tasks/${taskId}`;
        const headers = new HttpHeaders().set(
          'X-Tenant-ID',
          this.currentTenantId,
        );

        // 從 Y.Map 提取屬性值
        const startDate = taskMap.get('startDate') || null;
        const endDate = taskMap.get('endDate') || null;
        const progress = taskMap.get('progress') || 0;
        const dependencies = taskMap.get('dependencies') || [];
        const assigneeId = taskMap.get('assigneeId') || '';
        const reviewerId = taskMap.get('reviewerId') || '';
        const taskType = taskMap.get('taskType') || '';

        switch (update.field) {
          case 'startDate':
          case 'endDate':
            this.http
              .put(`${taskUrl}/schedule`, { startDate, endDate }, { headers })
              .subscribe();
            break;
          case 'progress':
            this.http
              .put(`${taskUrl}/progress`, { progress }, { headers })
              .subscribe();
            break;
          case 'dependencies':
            this.http
              .put(
                `${taskUrl}/dependencies`,
                { dependencyIds: dependencies },
                { headers },
              )
              .subscribe();
            break;
          case 'assigneeId':
          case 'reviewerId':
            this.http
              .patch(
                `${taskUrl}/personnel`,
                { assigneeId, reviewerId },
                { headers },
              )
              .subscribe();
            break;
          case 'taskType':
            this.http
              .patch(`${taskUrl}/type`, { taskType }, { headers })
              .subscribe();
            break;
        }
      });

    this.subscriptions.push(saveSub);
  }

  public updateTaskField(taskId: string, field: string, value: any) {
    if (!this.sharedTasks) return;

    // 🌟 將整包覆寫改為細粒度的 Y.Map 欄位更新
    let taskMap = this.sharedTasks.get(taskId) as Y.Map<any>;
    if (!taskMap) {
      taskMap = new Y.Map();
      this.sharedTasks.set(taskId, taskMap);
    }

    taskMap.set(field, value);
    this.cqrsSaveSubject.next({ taskId, field });
  }

  public setUserSelection(
    taskId: string | null,
    field: string | null,
    userInfo: any,
  ) {
    if (!this.awareness) return;
    this.awareness.setLocalStateField('user', userInfo);
    this.awareness.setLocalStateField(
      'selection',
      taskId && field ? { taskId, field } : null,
    );
  }

  public getProjectTasks(projectId: string, tenantId: string) {
    const headers = new HttpHeaders().set('X-Tenant-ID', tenantId);
    return this.http
      .get<any>(`${this.baseUrl}/projects/${projectId}/tasks`, { headers })
      .pipe(map((response) => response.data || response.tasks || []));
  }

  public createTask(taskName: string) {
    if (!this.currentProjectId || this.currentProjectId.trim() === '') {
      throw new Error('無法建立任務：未指定有效的專案空間');
    }
    const url = `${this.baseUrl}/projects/${this.currentProjectId}/tasks`;
    const headers = new HttpHeaders().set('X-Tenant-ID', this.currentTenantId);
    return this.http.post<TaskAddedResult>(
      url,
      { name: taskName },
      { headers },
    );
  }
}
