import { Injectable } from '@angular/core';
import { MessageService, ConfirmationService } from 'primeng/api';

/**
 * Message Service
 */
@Injectable({
  providedIn: 'root',
})
export class SystemMessageService {
  constructor(
    private messageService: MessageService,
    private confirmationService: ConfirmationService,
  ) {}

  /**
   * 顯示成功通知
   */
  showSuccess(summary: string, detail: string) {
    this.messageService.add({
      key: 'msg',
      severity: 'success',
      summary,
      detail,
      icon: 'pi-check',
    });
  }

  /**
   * 顯示警告通知
   */
  showWarn(summary: string, detail: string) {
    this.messageService.add({
      key: 'msg',
      severity: 'warn',
      summary,
      detail,
      icon: 'pi-check',
    });
  }

  /**
   * 顯示錯誤通知
   */
  showError(summary: string, detail: string) {
    this.messageService.add({
      key: 'msg',
      severity: 'error',
      summary,
      detail,
      icon: 'pi-times-circle',
    });
  }

  /**
   * 執行危險動作前的二次確認
   */
  confirmAction(header: string, message: string, onAccept: () => void) {
    this.confirmationService.confirm({
      header,
      message,
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: '確定',
      rejectLabel: '取消',
      // 💡 關鍵：將確定按鈕改為紅色警告色，並將取消按鈕設為次要樣式
      acceptButtonStyleClass: 'p-button-danger',
      rejectButtonStyleClass: 'p-button-secondary p-button-outlined',
      defaultFocus: 'reject', // 💡 處女座防呆：預設焦點在「取消」上
      accept: onAccept,
    });
  }
}
