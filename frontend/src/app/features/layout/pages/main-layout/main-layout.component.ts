import { Component } from '@angular/core';
import { NavigationEnd, Router, RouterModule } from '@angular/router';
import { SidebarComponent } from '../sidebar/sidebar.component';
import { ButtonModule } from 'primeng/button';
import { PRIME_COMPONENTS } from '../../../../shared/shared-primeng';
import { SidebarModule } from 'primeng/sidebar';
import { filter } from 'rxjs/internal/operators/filter';
@Component({
  selector: 'app-main-layout',
  standalone: true,
  imports: [
    RouterModule,
    ButtonModule,
    SidebarComponent,
    SidebarModule,
    PRIME_COMPONENTS,
  ],
  templateUrl: './main-layout.component.html',
  styleUrl: './main-layout.component.scss',
})
export class MainLayoutComponent {
  // 控制手機版側邊欄顯示與否的狀態
  isMobileSidebarVisible = false;

  constructor(private router: Router) {
    // 🌟 專業 UX 技巧：監聽路由切換事件
    // 當使用者在手機版側邊欄點擊了任何一個連結，跳轉完成後就自動把側邊欄關閉
    this.router.events
      .pipe(filter((event) => event instanceof NavigationEnd))
      .subscribe(() => {
        this.isMobileSidebarVisible = false;
      });
  }
}
