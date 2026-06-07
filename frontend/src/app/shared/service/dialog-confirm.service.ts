import { Injectable } from '@angular/core';
import { ConfirmationService } from 'primeng/api';

@Injectable({
  providedIn: 'root',
})
export class DialogConfirmService {
  constructor(private confirmationService: ConfirmationService) {}

  /**
   * 顯示確認是否刪除的對話框。
   *
   * @param acceptCallback 按下是之後要執行的 function
   * @param hint 要刪除的資料關鍵字
   * @param rejectCallback 按下否之後要執行的 function
   */
  /**
   * 顯示確認是否刪除的對話框。
   */
  confirmDelete(
    acceptCallback: Function,
    hint?: string,
    rejectCallback?: Function,
  ) {
    // PrimeNG 內建的樣式類別
    const dangerBtnClass = 'p-button-danger'; // 讓確認按鈕變成紅色
    const textBtnClass = 'p-button-text p-button-secondary'; // 讓取消按鈕變成低調的純文字按鈕
    const warningIcon = 'pi pi-exclamation-triangle text-red-500'; // 警告三角形 (加上紅色)

    if (hint) {
      this.confirm(
        '刪除確認',
        `您確定要刪除「${hint}」這筆資料嗎？此動作無法復原。`,
        warningIcon,
        acceptCallback,
        { hint: hint },
        '確認刪除', // 🌟 UX 優化：明確的破壞性動作提示
        rejectCallback,
        '取消', // 🌟 UX 優化
        dangerBtnClass,
        textBtnClass,
      );
    } else {
      this.confirm(
        '刪除確認',
        '您確定要刪除這筆資料嗎？此動作無法復原。',
        warningIcon,
        acceptCallback,
        undefined,
        '確認刪除',
        rejectCallback,
        '取消',
        dangerBtnClass,
        textBtnClass,
      );
    }
  }

  /**
   * 顯示確認變更未儲存的對話框。
   *
   * @param acceptCallback 按下是之後要執行的 function
   * @param rejectCallback 按下否之後要執行的 function
   */
  confirmUnsaved(acceptCallback: Function, rejectCallback?: Function) {
    this.confirm(
      '確認',
      '您確定要確認變更未儲存的資料嗎 ?',
      'pi pi-info-circle',
      acceptCallback,
      undefined,
      '離開',
      rejectCallback,
    );
  }

  /**
   * 顯示確認放棄未儲存的對話框。
   *
   * @param acceptCallback 按下是之後要執行的 function
   * @param rejectCallback 按下否之後要執行的 function
   */
  confirmDiscardChanges(acceptCallback: Function, rejectCallback?: Function) {
    this.confirm(
      '確認',
      '您確定要放棄所有未儲存的變更嗎 ?',
      'pi pi-info-circle',
      acceptCallback,
      undefined,
      '放棄',
      rejectCallback,
    );
  }

  /**
   * 顯示確認對話框。
   *
   * @param header 對話框的 Header
   * @param message 要確認的訊息
   * @param iconClass 訊息前 icon 的 style class
   * @param acceptCallback 按下是之後要執行的 function
   * @param parameters 要確認的訊息要傳遞的參數
   * @param acceptLabel 接受按鈕的文字
   * @param rejectCallback 按下否之後要執行的 function
   * @param rejectLabel 拒絕按鈕的文字
   */
  confirm(
    header: string,
    message: string,
    iconClass: string,
    acceptCallback: Function,
    parameters?: Object,
    acceptLabel?: string,
    rejectCallback?: Function,
    rejectLabel?: string,
    acceptButtonStyleClass?: string, // 🌟 新增：確認按鈕的樣式
    rejectButtonStyleClass?: string, // 🌟 新增：取消按鈕的樣式
  ) {
    this.confirmationService.confirm({
      message: message,
      header: header,
      icon: iconClass,
      acceptLabel: acceptLabel,
      rejectLabel: rejectLabel,
      acceptButtonStyleClass: acceptButtonStyleClass, // 🌟 綁定設定
      rejectButtonStyleClass: rejectButtonStyleClass, // 🌟 綁定設定
      accept: () => {
        acceptCallback();
      },
      reject: () => {
        if (typeof rejectCallback === 'function') {
          rejectCallback();
        }
      },
    });
  }
}
