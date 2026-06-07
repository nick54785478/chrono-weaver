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

@Injectable({
  providedIn: 'root',
})
export class GanttSyncService implements OnDestroy {
  private rxStomp = new RxStomp();
  public ydoc = new Y.Doc();
  public sharedTasks!: Y.Map<any>;
  public awareness!: Awareness;
  // 在 GanttSyncService 內部的示意調整
  public isReady$ = new BehaviorSubject<boolean>(false);
  // 🌟 1. 將 environment 的 URL 抽成類別內部的主 baseUrl
  private baseUrl = environment.apiEndpoint;

  private currentProjectId: string | null = null;

  // 🌟 2. 新增私有屬性，用來暫存由外部傳入的租戶識別碼，供背景防抖管線使用
  private currentTenantId: string = '';

  private subscriptions: Subscription[] = [];
  private cqrsSaveSubject = new Subject<{ taskId: string; field: string }>();

  constructor(private http: HttpClient) {
    this.setupCqrsPipeline();
  }

  ngOnDestroy() {
    this.leaveProjectRoom();
    this.rxStomp.deactivate();
    this.ydoc.destroy();
  }

  /**
   * 加入專案房間
   * @param projectId 專案 ID
   * @param tenantId 由外部傳入的租戶識別碼
   */
  public joinProjectRoom(projectId: string, tenantId: string) {
    // 🌟 2. 每次切換房間時，先降下綠旗
    this.isReady$.next(false);

    if (this.currentProjectId === projectId) {
      return;
    }
    this.currentProjectId = projectId;
    this.currentTenantId = tenantId;

    this.rxStomp.configure({
      brokerURL: 'ws://localhost:8080/ws-collaborate/websocket',
      reconnectDelay: 5000,
    });
    this.rxStomp.activate();

    this.sharedTasks = this.ydoc.getMap('tasks');
    this.awareness = new Awareness(this.ydoc);

    this.setupYjsSync(projectId);

    // ==========================================
    // 🌟 3. 核心：監聽 STOMP 連線成功事件
    // ==========================================
    this.rxStomp.connected$.pipe(take(1)).subscribe(() => {
      console.log('[WebSocket] STOMP 連線成功！等待初始資料同步...');
      // 💡 加上微小延遲 (500ms)，確保 setupYjsSync 有足夠時間把伺服器的初始封包寫進 ydoc
      setTimeout(() => {
        this.isReady$.next(true); // 舉起綠旗，通知 Component 可以開始做事了
      }, 500);
    });
  }

  public leaveProjectRoom() {
    this.subscriptions.forEach((sub) => sub.unsubscribe());
    this.subscriptions = [];
    this.currentProjectId = null;
  }

  private setupYjsSync(projectId: string) {
    const topicSub = this.rxStomp
      .watch(`/topic/projects/${projectId}/yjs`)
      .subscribe((message) => {
        Y.applyUpdate(this.ydoc, new Uint8Array(message.binaryBody));
      });
    this.subscriptions.push(topicSub);

    this.ydoc.on('update', (update: Uint8Array, origin: any) => {
      if (origin !== 'remote') {
        this.rxStomp.publish({
          destination: `/app/projects/${projectId}/yjs`,
          binaryBody: update,
        });
      }
    });

    const awarenessSub = this.rxStomp
      .watch(`/topic/projects/${projectId}/awareness`)
      .subscribe((message) => {
        applyAwarenessUpdate(
          this.awareness,
          new Uint8Array(message.binaryBody),
          this,
        );
      });
    this.subscriptions.push(awarenessSub);

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

  // ==========================================
  // Track B: API 對接與 CQRS 防抖管線
  // ==========================================
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
        if (!this.currentProjectId) return;

        const taskId = update.taskId;
        const latestTaskData = this.sharedTasks.get(taskId);
        if (!latestTaskData) return;

        // 🌟 3. 使用類別內部的 baseUrl 組裝 API 路徑
        const taskUrl = `${this.baseUrl}/projects/${this.currentProjectId}/tasks/${taskId}`;

        // 🌟 4. 使用外部傳入並暫存的 currentTenantId 設定 Header，不再寫死
        const headers = new HttpHeaders().set(
          'X-Tenant-ID',
          this.currentTenantId,
        );

        switch (update.field) {
          case 'startDate':
          case 'endDate':
            this.http
              .put(
                `${taskUrl}/schedule`,
                {
                  startDate: latestTaskData.startDate || null,
                  endDate: latestTaskData.endDate || null,
                },
                { headers },
              )
              .subscribe();
            break;
          case 'progress':
            this.http
              .put(
                `${taskUrl}/progress`,
                { progress: latestTaskData.progress },
                { headers },
              )
              .subscribe();
            break;
          case 'dependencies':
            this.http
              .put(
                `${taskUrl}/dependencies`,
                { dependencyIds: latestTaskData.dependencies || [] },
                { headers },
              )
              .subscribe();
            break;
          case 'assigneeId':
          case 'reviewerId':
            this.http
              .patch(
                `${taskUrl}/personnel`,
                {
                  assigneeId: latestTaskData.assigneeId || '',
                  reviewerId: latestTaskData.reviewerId || '',
                },
                { headers },
              )
              .subscribe();
            break;
          case 'taskType':
            this.http
              .patch(
                `${taskUrl}/type`,
                { taskType: latestTaskData.taskType || '' },
                { headers },
              )
              .subscribe();
            break;
        }
      });

    this.subscriptions.push(saveSub);
  }

  public updateTaskField(taskId: string, field: string, value: any) {
    const currentTaskData = this.sharedTasks.get(taskId) || {};
    this.sharedTasks.set(taskId, { ...currentTaskData, [field]: value });
    this.cqrsSaveSubject.next({ taskId, field });
  }

  public setUserSelection(
    taskId: string | null,
    field: string | null,
    userInfo: any,
  ) {
    this.awareness.setLocalStateField('user', userInfo);
    this.awareness.setLocalStateField(
      'selection',
      taskId && field ? { taskId, field } : null,
    );
  }

  /**
   * 從 CQRS Read Model 獲取專案的所有任務
   * @param projectId 專案 ID
   * @param tenantId 🌟 新增：由外部傳入的租戶識別碼
   */
  public getProjectTasks(projectId: string, tenantId: string) {
    // 🌟 5. 將 Header 轉為由外部傳入的 tenantId 動態生成
    const headers = new HttpHeaders().set('X-Tenant-ID', tenantId);

    // 🌟 6. 使用類別內部的 baseUrl 組裝路徑
    return this.http
      .get<any>(`${this.baseUrl}/projects/${projectId}/tasks`, { headers })
      .pipe(map((response) => response.data || response.tasks || []));
  }

  /**
   * 🌟 建立任務 (強制綁定 TaskAddedResult 泛型)
   */
  public createTask(taskName: string) {
    if (!this.currentProjectId || this.currentProjectId.trim() === '') {
      throw new Error('無法建立任務：未指定有效的專案空間');
    }

    const url = `${this.baseUrl}/projects/${this.currentProjectId}/tasks`;
    const headers = new HttpHeaders().set('X-Tenant-ID', this.currentTenantId);

    // 💡 乾淨俐落：直接回傳 JSON，不需任何 responseType 降級設定
    return this.http.post<TaskAddedResult>(
      url,
      { name: taskName },
      { headers },
    );
  }
}
