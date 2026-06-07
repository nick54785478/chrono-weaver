// 🌟 1. 嚴格對齊後端 TaskView 的 DTO 介面
export interface Task {
  taskId: string;
  tenantId?: string;
  projectId?: string;
  displayId?: string;
  name: string;
  module?: string | null;
  startDate: string | null; // 對應 LocalDate
  endDate: string | null; // 對應 LocalDate
  progress: number;
  dependencies: string[]; // 對應 Set<String>
  createdAt?: string; // 對應 Instant
  taskType?: string;
  assigneeId?: string;
  reviewerId?: string;
}
