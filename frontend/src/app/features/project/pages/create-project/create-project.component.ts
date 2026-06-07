import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  FormBuilder,
  FormGroup,
  Validators,
  ReactiveFormsModule,
} from '@angular/forms';
import { Router } from '@angular/router';

// 引入 PrimeNG 模組 (確保與你的 UI 體系一致)
import { CardModule } from 'primeng/card';
import { InputTextModule } from 'primeng/inputtext';
import { ButtonModule } from 'primeng/button';
import { MessagesModule } from 'primeng/messages';
import { MessageModule } from 'primeng/message';
import { ProjectService } from '../../../../shared/service/project.service';
import { StorageService } from '../../../../core/services/storage.service';
import { SystemStorageKey } from '../../../../core/enums/system-storage.enum';
import { SystemMessageService } from '../../../../shared/service/system-message.service';
import { CommandResult } from '../../../../shared/models/command-result.model';
import { HttpErrorResponse } from '@angular/common/http';

@Component({
  selector: 'app-create-project',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    CardModule,
    InputTextModule,
    ButtonModule,
    MessagesModule,
    MessageModule,
  ],
  templateUrl: './create-project.component.html',
  styleUrl: './create-project.component.scss',
})
export class CreateProjectComponent implements OnInit {
  projectForm!: FormGroup;
  loading: boolean = false;
  errorMessage: string = '';

  constructor(
    private fb: FormBuilder,
    private projectService: ProjectService,
    private router: Router,
    private storageService: StorageService,
    // 🌟 2. 注入 SystemMessageService
    private systemMessageService: SystemMessageService,
  ) {}

  ngOnInit(): void {
    this.projectForm = this.fb.group({
      // 專案代號驗證：必填，且只能是 2~6 碼的英數字大寫
      projectCode: [
        '',
        [Validators.required, Validators.pattern('^[A-Z0-9]{2,6}$')],
      ],
      // 專案名稱驗證：必填
      projectName: ['', [Validators.required, Validators.minLength(2)]],
    });
  }

  /**
   * 提交表單，發送創世 Command
   */
  onSubmit(): void {
    if (this.projectForm.invalid) {
      this.projectForm.markAllAsTouched();
      return;
    }

    this.loading = true;
    const { projectCode, projectName } = this.projectForm.value;

    const currentTenant =
      this.storageService.getLocalStorageItem(SystemStorageKey.TENANT) || 'WPG';

    this.projectService
      .createProject(projectCode, projectName, currentTenant)
      .subscribe({
        next: (res: any) => {
          this.loading = false;

          // 正常防禦：萬一後端回傳 HTTP 200 但 success 為 false
          if (res && res.success === false) {
            this.errorMessage = res.message || '專案建立失敗';
            this.systemMessageService.showError('操作拒絕', this.errorMessage);
            return;
          }

          // 提取 UUID
          const generatedProjectId = res.projectId || res.id || res.data || res;

          localStorage.setItem('currentProjectCode', projectCode); // 確保這裡存入了 ProjectCode 供後續取用
          this.handleSuccessRedirect(projectCode, generatedProjectId);
        },
        error: (err: HttpErrorResponse) => {
          this.loading = false;
          console.error(
            '[CQRS Error] 🚨 完整錯誤物件 (請點開看詳細內容):',
            err,
          );

          // ==========================================
          // 🕵️ 案情診斷 1：其實成功了，但 Angular 誤判為 JSON 解析錯誤
          // ==========================================
          if (err.status === 200 || err.status === 201) {
            console.warn(
              '[CQRS] 後端其實回傳了成功狀態碼，但前端解析 JSON 失敗。嘗試強行提取 ID...',
            );

            // 當 Angular 解析 JSON 失敗時，會把原始的純文字塞在 err.error.text
            const rawResponseText = err.error?.text || '';
            const generatedProjectId = rawResponseText.replace(/["']/g, ''); // 移除可能的雙引號

            if (generatedProjectId) {
              // 強制執行成功邏輯！
              this.handleSuccessRedirect(projectCode, generatedProjectId);
              return;
            }
          }

          // ==========================================
          // 🕵️ 案情診斷 2：CORS 跨網域阻擋
          // ==========================================
          if (err.status === 0) {
            this.errorMessage =
              'CORS 跨網域請求被阻擋！請檢查 Spring Boot 的 @CrossOrigin 設定。';
            this.systemMessageService.showError(
              '連線被拒絕 (CORS)',
              '請按 F12 查看 Console 的紅色跨網域錯誤',
            );
            return;
          }

          // ==========================================
          // 🕵️ 案情診斷 3：後端真正丟出的業務規則防呆 (HTTP 400/500)
          // ==========================================
          let errorMsg = '系統發生預期外錯誤，請稍後再試';

          if (err.error) {
            const backendResponse = err.error;
            // 精準抓取各種可能的錯誤屬性
            errorMsg =
              backendResponse.message ||
              backendResponse.error ||
              backendResponse.detail ||
              errorMsg;
          }

          this.errorMessage = errorMsg;
          this.systemMessageService.showError('專案建立失敗', errorMsg);
        },
      });
  }

  /**
   * 🌟 獨立抽出的成功導頁邏輯，讓 next 和 error(200 OK 誤判) 都能共用
   */
  private handleSuccessRedirect(
    projectCode: string,
    generatedProjectId: string,
  ) {
    console.log('[CQRS] 專案建立成功，生成的 UUID 為:', generatedProjectId);
    this.systemMessageService.showSuccess(
      '專案啟動成功',
      `代號 ${projectCode} 領域聚合根已建立`,
    );
    // 在 onSubmit 的 next 回調中
    this.router.navigate(['/project-processing', generatedProjectId]);
  }

  // 輔助檢查欄位是否被碰過且不合法
  isFieldInvalid(fieldName: string): boolean {
    const field = this.projectForm.get(fieldName);
    return !!(field && field.invalid && (field.dirty || field.touched));
  }
}
