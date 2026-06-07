export interface TaskAddedResult {
  success: boolean;
  taskId: string; // 實體 UUID
  displayId: string; // 人類可讀編號 (如 MARS-1)
  message: string; // 提示訊息 (如 "任務新增成功")
}
