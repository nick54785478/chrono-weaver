import { isPlatformBrowser } from '@angular/common';
import { Inject, Injectable, PLATFORM_ID } from '@angular/core';
import { SystemStorageKey } from '../enums/system-storage.enum';

/**
 * StorageService
 * 是對 localStorage 及 SessionStorage 的抽象封裝，以確保代碼兼容多平台。
 * 避免伺服器端渲染使用 localStorage 及 SessionStorage
 * 檢測執行環境（使用 isPlatformBrowser）。
 */
@Injectable({
  providedIn: 'root',
})
export class StorageService {
  static getLocalStorageItem(REFRESH_TOKEN: SystemStorageKey): any {
    throw new Error('Method not implemented.');
  }
  constructor(@Inject(PLATFORM_ID) private platformId: Object) {}

  /**
   * 設置 LocalStorage
   * @param key
   * @param value
   */
  setLocalStorageItem(key: string, value: string): void {
    if (isPlatformBrowser(this.platformId)) {
      localStorage.setItem(key, value);
    }
  }

  /**
   * 取得 LocalStorage 的值
   * @param key
   * @returns
   */
  getLocalStorageItem(key: string): string | null {
    if (isPlatformBrowser(this.platformId)) {
      localStorage.setItem('timestamp', new Date().getTime().toString());
      let value = localStorage.getItem(key);
      return value ? value : '';
    }
    return null;
  }

  /**
   * 清除特定 LocalStorage 的值
   * @param key
   */
  removeLocalStorageItem(key: string): void {
    if (isPlatformBrowser(this.platformId)) {
      localStorage.removeItem(key);
    }
  }

  /**
   * 設定 sessionStorage
   * @param key sessionStorage key
   * @param value sessionStorage value
   */
  setSessionStorageItem(key: string, value: string): void {
    if (isPlatformBrowser(this.platformId)) {
      sessionStorage.setItem(key, value);
    }
  }

  /**
   * 取得 Session Storage 的值
   *
   * @param key sessionStorage key
   * @returns sessionStorage value
   */
  getSessionStorageItem(key: string): string {
    if (isPlatformBrowser(this.platformId)) {
      sessionStorage.setItem('timestamp', new Date().getTime().toString());
      let value = sessionStorage.getItem(key);
      return value ? value : '';
    }
    return '';
  }

  /**
   * 設置權限清單
   * @returns string[]
   */
  setPermissionList(permissions: string[]) {
    if (isPlatformBrowser(this.platformId)) {
      this.setSessionStorageItem(
        SystemStorageKey.PERMISSIONS,
        permissions.join(','),
      );
    }
  }

  /**
   * 取得權限清單
   * @returns string[]
   */
  getPermissionList(): string[] {
    if (isPlatformBrowser(this.platformId)) {
      let permissions = this.getSessionStorageItem(SystemStorageKey.PERMISSIONS)
        ? this.getSessionStorageItem(SystemStorageKey.PERMISSIONS)
        : '';
      if (permissions) {
        return permissions.split(',');
      }
      return [];
    }
    return [];
  }

  /**
   * 移除 sessionStorage
   * @param key sessionStorage key
   */
  removeSessionStorageItem(key: string) {
    if (isPlatformBrowser(this.platformId)) {
      sessionStorage.removeItem(key);
    }
  }
}
