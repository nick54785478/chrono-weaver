import { Component, OnInit } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { PRIME_COMPONENTS } from './shared/shared-primeng';
import { CommonModule, NgClass } from '@angular/common';
import { ToastModule } from 'primeng/toast';
import { StorageService } from './core/services/storage.service';
import { SystemStorageKey } from './core/enums/system-storage.enum';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, PRIME_COMPONENTS, NgClass, CommonModule],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent implements OnInit {
  title = 'chrono-weaver';

  constructor(private storageService: StorageService) {}

  ngOnInit(): void {
    // ==========================================
    // 開發期應急處理：系統啟動時寫入預設 Tenant
    // ==========================================
    const currentTenant = this.storageService.getLocalStorageItem(
      SystemStorageKey.TENANT,
    );

    // 防呆機制：如果 LocalStorage 裡面還沒有 Tenant，才幫它寫入預設值 'DDD'
    // 這樣如果你之後想要手動在瀏覽器 F12 修改 LocalStorage 測試其他租戶，就不會一重新整理又被蓋掉
    if (!currentTenant) {
      console.warn(
        '[System Init] 偵測到 LocalStorage 無 Tenant 資訊，自動寫入預設值: DDD',
      );
      this.storageService.setLocalStorageItem(SystemStorageKey.TENANT, 'DDD');
    }
    
  }
}
