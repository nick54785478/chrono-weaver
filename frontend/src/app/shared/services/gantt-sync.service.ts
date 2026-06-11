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
  // Yjs & WebSocket 基礎設施

  private rxStomp = new RxStomp();
  public ydoc = new Y.Doc();
  public sharedTasks!: Y.Map<any>; // 儲存任務資料的 CRDT 共享記憶體
  public awareness!: Awareness; // 處理游標、選取狀態、上線人員的協作狀態機

  // 同步守衛信號：通知 Component 何時可以安全地讀取 Yjs 記憶體或發送 DB 請求
  public isReady$ = new BehaviorSubject<boolean>(false);

  // 環境與狀態配置

  // 1. 將 environment 的 URL 抽成類別內部的主 baseUrl，集中管理
  private baseUrl = environment.apiEndpoint;
  private currentProjectId: string | null = null;

  // 2. 暫存外部傳入的租戶識別碼，供背景防抖管線 (CQRS Pipeline) 組合 HTTP Header 使用
  private currentTenantId: string = '';

  private subscriptions: Subscription[] = [];

  // CQRS 非同步寫入管線的入口
  private cqrsSaveSubject = new Subject<{ taskId: string; field: string }>();

  constructor(private http: HttpClient) {
    // 服務初始化時，架設好非同步寫入資料庫的防抖管線
    this.setupCqrsPipeline();
  }

  ngOnDestroy() {
    this.leaveProjectRoom();
    this.rxStomp.deactivate();
    this.ydoc.destroy();
  }

  /**
   * 加入專案協作房間 (初始化 WebSocket 與 Yjs)
   * @param projectId 專案 ID
   * @param tenantId 由外部傳入的租戶識別碼
   */
  public joinProjectRoom(projectId: string, tenantId: string) {
    // 每次切換房間時，先降下綠旗，避免 Component 讀到未同步的舊資料
    this.isReady$.next(false);

    if (this.currentProjectId === projectId) {
      return;
    }
    this.currentProjectId = projectId;
    this.currentTenantId = tenantId;

    // 配置並啟動 STOMP 協議的 WebSocket 連線
    this.rxStomp.configure({
      brokerURL: 'ws://localhost:8080/ws-collaborate/websocket',
      reconnectDelay: 5000, // 斷線後每 5 秒嘗試重連
    });
    this.rxStomp.activate();

    // 初始化 Yjs 結構
    this.sharedTasks = this.ydoc.getMap('tasks');
    this.awareness = new Awareness(this.ydoc);

    // 綁定 Yjs 與 WebSocket 的雙向通訊
    this.setupYjsSync(projectId);

    // 3. 核心：監聽 STOMP 連線成功事件與防禦延遲
    this.rxStomp.connected$.pipe(take(1)).subscribe(() => {
      console.log('[WebSocket] STOMP 連線成功！等待初始資料同步...');

      // 關鍵防禦機制：給予 2.5 秒的緩衝時間。
      // 確保第一台電腦廣播的龐大 Yjs 封包有足夠時間飛遞過來，
      // 避免新加入的視窗因為剛連線 Yjs 尚未填充，就誤判房間為空而跑去撈 DB 洗掉資料。
      setTimeout(() => {
        this.isReady$.next(true); // 舉起綠旗，通知 Component 可以開始渲染或查 DB 了
      }, 2500);
    });
  }

  /**
   * 離開房間並清理資源
   */
  public leaveProjectRoom() {
    this.subscriptions.forEach((sub) => sub.unsubscribe());
    this.subscriptions = [];
    this.currentProjectId = null;
  }

  /**
   * 建立 Yjs 與 STOMP 的雙向橋樑
   * 處理資料同步 (/yjs) 與游標/線上狀態同步 (/awareness)
   */
  private setupYjsSync(projectId: string) {
    // 1. 監聽遠端資料更新，寫入本地 Yjs
    const topicSub = this.rxStomp
      .watch(`/topic/projects/${projectId}/yjs`)
      .subscribe((message) => {
        Y.applyUpdate(this.ydoc, new Uint8Array(message.binaryBody));
      });
    this.subscriptions.push(topicSub);

    // 2. 監聽本地 Yjs 更新，推播至遠端 (過濾掉來自遠端的更新，避免無限迴圈)
    this.ydoc.on('update', (update: Uint8Array, origin: any) => {
      if (origin !== 'remote') {
        this.rxStomp.publish({
          destination: `/app/projects/${projectId}/yjs`,
          binaryBody: update,
        });
      }
    });

    // 3. 監聽遠端 Awareness (游標/線上名單) 更新
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

    // 4. 監聽本地 Awareness 更新，推播至遠端
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

  // Track B: API 對接與 CQRS 防抖管線

  /**
   * 建立背景非同步持久化管線
   * 負責攔截頻繁的 Yjs 變更，進行 1 秒防抖後再發送 HTTP 請求給後端，保護資料庫。
   */
  private setupCqrsPipeline() {
    const saveSub = this.cqrsSaveSubject
      .pipe(
        debounceTime(1000), // 💡 核心防抖：1 秒內同一個欄位的頻繁修改，只會發送最後一次
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
        if (!latestTaskData) {
          return;
        }
        // 3. 使用類別內部的 baseUrl 組裝 API 路徑
        const taskUrl = `${this.baseUrl}/projects/${this.currentProjectId}/tasks/${taskId}`;

        // 4. 使用外部傳入並暫存的 currentTenantId 設定 Header，確保多租戶資料隔離
        const headers = new HttpHeaders().set(
          'X-Tenant-ID',
          this.currentTenantId,
        );

        // 根據變更的欄位，呼叫對應的後端 Command 介面
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

  /**
   * 更新任務特定欄位
   * 同時觸發 Yjs 即時共編與後端 CQRS 持久化管線
   */
  public updateTaskField(taskId: string, field: string, value: any) {
    const currentTaskData = this.sharedTasks.get(taskId) || {};
    this.sharedTasks.set(taskId, { ...currentTaskData, [field]: value }); // 寫入 CRDT
    this.cqrsSaveSubject.next({ taskId, field }); // 拋入持久化管線
  }

  /**
   * 設定本地使用者的游標/儲存格聚焦狀態，廣播給其他協作者
   */
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
   * 從 CQRS Read Model (資料庫) 獲取專案的所有任務，通常作為房間初始化時的兜底方案
   * @param projectId 專案 ID
   * @param tenantId 🌟 新增：由外部傳入的租戶識別碼
   */
  public getProjectTasks(projectId: string, tenantId: string) {
    // 5. 將 Header 轉為由外部傳入的 tenantId 動態生成
    const headers = new HttpHeaders().set('X-Tenant-ID', tenantId);

    // 6. 使用類別內部的 baseUrl 組裝路徑
    return this.http
      .get<any>(`${this.baseUrl}/projects/${projectId}/tasks`, { headers })
      .pipe(map((response) => response.data || response.tasks || []));
  }

  /**
   * 建立任務 (CQRS Command)
   * 嚴格綁定 TaskAddedResult 泛型，要求後端必須回傳明確的成功狀態與 UUID
   */
  public createTask(taskName: string) {
    if (!this.currentProjectId || this.currentProjectId.trim() === '') {
      throw new Error('無法建立任務：未指定有效的專案空間');
    }

    const url = `${this.baseUrl}/projects/${this.currentProjectId}/tasks`;
    const headers = new HttpHeaders().set('X-Tenant-ID', this.currentTenantId);

    // 乾淨俐落：直接回傳 JSON 物件，交由 Component 後續建立 Yjs 實體
    return this.http.post<TaskAddedResult>(
      url,
      { name: taskName },
      { headers },
    );
  }
}
