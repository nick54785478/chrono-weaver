# Chrono Weaver : Enterprise WBS & Gantt Collaboration Platform

Chrono Weaver 是一款專為中大型團隊設計的敏捷專案管理與 WBS (工作分解結構) 即時共編平台。
本專案致力於解決多人在高併發環境下協作時的資料衝突與鎖定問題。
>* 後端捨棄了傳統的 CRUD 與關聯式資料庫鎖定，全面導入 CQRS (命令與查詢職責分離) 與 Event Sourcing (事件溯源) 架構。
>* 前端則結合 Yjs CRDT 技術，實現如 Google Docs 般的毫秒級即時共編體驗。

## 核心架構設計 (Core Architecture)

本系統嚴格遵循 領域驅動設計 (DDD) 與 Clean Architecture / 六角形架構，確保業務邏輯的純潔性與高度可測試性。

### 1. 讀寫分離與事件溯源 (CQRS & Event Sourcing)
系統底層基於 Apache Pekko (Akka 的開源分支) 實作 Actor 模型，徹底分離了寫入 (Command) 與讀取 (Query) 的資料流：

* **Write Side (命令端 - 絕對的真實)：**

每個專案 (Project) 與團隊 (ProjectTeam) 都是一個獨立的 Event Sourced Actor (聚合根)。
透過 **Actor Mailbox** 保證單一實體的指令是循序執行的，**完全免除了關聯式資料庫的悲觀鎖 (Pessimistic Lock) 或樂觀鎖 (Optimistic Lock) 帶來的效能瓶頸**。
所有的狀態變更不直接 Update 資料庫，而是化為不可變的「領域事件 (Domain Events)」附加至 Event Journal (如 Cassandra) 中，提供完美的稽核軌跡 (Audit Trail)。
>* Event Sourced Aggregates： 每個專案 (Project) 與團隊 (ProjectTeam) 都是獨立的 Actor。狀態變更不直接 UPDATE 資料庫，而是化為不可變的「領域事件 (Domain Events)」附加至 Cassandra Event Journal 中。
>* 無鎖併發防禦 (Lock-free Concurrency)： 透過 Actor Mailbox 保證單一實體的指令循序執行。在高併發下，衝突在進入 Actor 前就被排解為先後順序，完全免除資料庫悲觀鎖導致的效能瓶頸。

* **Read Side (查詢端 - 最終一致性)：**

透過 Pekko Projection 非同步訂閱 Event Journal，收到事件後，將狀態投影 (Project) 到關聯式資料庫 (PostgreSQL) 中高度平坦化的視圖表 (ProjectView, TaskView)。
前端的列表與甘特圖查詢直接對接這些平坦化視圖，達成極致的查詢吞吐量 (High Throughput)，徹底消滅 ORM N+1 查詢地雷。
  
>* 平坦化視圖： 透過 Pekko Projection 非同步訂閱事件，將狀態投影至 PostgreSQL 的視圖表。前端查詢直接對接平坦化視圖，徹底消滅 ORM N+1 查詢地雷，達成極致的查詢吞吐量。

### 2. 分散式環境的一致性保證 (Pekko Cluster Sharding)

為了支援橫向擴展 (Horizontal Scaling)，系統導入了 Cluster Sharding 技術：
>* 位置透明性 (Location Transparency)： 即便系統部署於多個 Pod，特定 Entity ID 的 Actor 在整個叢集中永遠只會存在唯一一個活躍實例。
>* 精準路由： 無論用戶請求打在哪個 Pod，叢集會自動將指令轉發至代管該實體的節點，保證分散式環境下「單一事實來源」的原則，確保強一致性。


### 3. 六角形架構與彈性投影 (Resilient Projections)

為了保護核心業務與投影邏輯不被底層基礎設施污染，系統在邊界處理上導入了 Port-Adapter 模式：

* 投影層的防護： 在處理 Pekko Event Envelope 時，Handler (Primary Adapter) 本身不依賴任何 Spring Data JPA 或資料庫交易機制。

* 依賴反轉 (DIP)： Handler 透過 ProjectViewUpdaterPort (Secondary Port) 呼叫實作層。實際的資料庫 @Transactional 邊界被嚴格限制在 ProjectViewUpdaterAdapter 中。

* atLeastOnce 語意： 投影管線採用「至少一次」處理語意。配合分散式 Offset 追蹤，即使某個負責投影的 Pod 崩潰，重啟後精靈會自動從舊位移處 Replay 事件，確保讀取端資料絕對不遺失。

* 並行精靈 (Projection Daemons)： 系統切分多條獨立水管，讓「檢視表更新」與「分散式 Saga 交易」平行處理，互不阻塞。

**優勢**： 這種設計讓事件分發路由 (Pekko) 與持久化交易 (Spring JPA) 完美解耦。若未來將關聯式資料庫替換為 MongoDB 或 Elasticsearch，核心 Handler 程式碼完全無須修改。

### 3. 現代化 Java 領域建模 (Modern Java DDD)

* Sealed Interfaces & Pattern Matching： 善用 Java 21 的 sealed interface 定義領域事件，配合 switch 模式匹配，達成編譯期的窮舉防禦 (Exhaustiveness Check)。若領域層新增了事件而 Handler 忘記處理，系統將直接拒絕編譯，從根源消滅 Runtime 例外。

* 意圖導向 (Intent-driven)： 捨棄無腦的 Setter，所有的領域變更皆透過具備明確商業語意的 Command (如 UpdateTaskDependencies, ChangeRole) 來觸發。

## 系統亮點功能 (Key Features)

**WBS 任務即時共編 (Real-time Collaboration)**： 前端導入 Yjs (CRDT) 共享資料類型，配合 RxStomp (WebSocket) 打造雙軌同步機制。
>* Track A (共編軌)： P2P 記憶體狀態同步，提供遠端游標、聚焦高亮、線上名單等毫秒級協作體驗。
>* Track B (持久軌)： 透過 RxJS 實作 1 秒防抖 (Debounce) 管線，將高頻繁的打字操作轉譯為 CQRS Command 發送至後端，有效保護資料庫。

**多房間隔離與歷史防護**： 
> 實作完善的 Y.Doc 生命週期管理與 Late Joiner (全量狀態索取) 機制，確保使用者在不同專案間切換時，記憶體資料絕對隔離，防止 CRDT Vector Clock 歷史錯亂。

**互動式專案甘特圖**： 
> 自動解析任務的相依性 (Dependencies) 與模組 (Epic) 關聯，動態渲染時程推進圖。

**樂觀更新 (Optimistic UI)**： 
> 在等待 CQRS 最終一致性的時間差內，前端畫面預先渲染成功狀態，提供極致絲滑的操作體感。

### 技術堆疊 (Tech Stack)

**Backend (後端架構)**
>* Language: Java 21
>* Framework: Spring Boot 3
>* Distributed Systems: Apache Pekko (Akka), Pekko Persistence, Pekko Projection
>* Architecture: CQRS, Event Sourcing, Domain-Driven Design (DDD), Hexagonal Architecture

**Frontend (前端應用)**
>* Framework: Angular 17+ (Standalone Components)
>* UI Library: PrimeNG, PrimeFlex
>* Real-time & State: Yjs (CRDT), y-protocols, RxStomp (WebSocket), RxJS

**Infrastructure (基礎設施)**
>* Database (Write Side): Apache Cassandra (Event Journal)
>* Database (Read Side): PostgreSQL (Read Models / Views)
>* Message Broker: Spring STOMP Broker
