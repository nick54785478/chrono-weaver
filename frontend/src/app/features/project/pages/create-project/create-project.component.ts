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
import { DropdownModule } from 'primeng/dropdown';

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
    DropdownModule,
  ],
  templateUrl: './create-project.component.html',
  styleUrl: './create-project.component.scss',
})
export class CreateProjectComponent implements OnInit {
  projectForm!: FormGroup;
  loading: boolean = false;
  errorMessage: string = '';

  // 🌟 1. 新增：暫時寫死的專案負責人選項 (後續可替換為從 API 獲取)
  ownerOptions = [
    { label: '107054', value: '107054' },
    { label: '118056', value: '118056' },
  ];

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
      ownerId: ['', [Validators.required]],
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

    // 🌟 3. 解構賦值時，把 ownerId 一併拿出來
    const { projectCode, projectName, ownerId } = this.projectForm.value;

    const currentTenant =
      this.storageService.getLocalStorageItem(SystemStorageKey.TENANT) || 'WPG';

    // 🌟 4. 記得去 ProjectService 將 createProject 的參數補上 ownerId！
    this.projectService
      .createProject(projectCode, projectName, currentTenant, ownerId)
      .subscribe({
        next: (res: any) => {
          this.loading = false;
          if (res && res.success === false) {
            this.errorMessage = res.message || '專案建立失敗';
            this.systemMessageService.showError('操作拒絕', this.errorMessage);
            return;
          }

          const generatedProjectId = res.projectId || res.id || res.data || res;
          localStorage.setItem('currentProjectCode', projectCode);
          this.handleSuccessRedirect(projectCode, generatedProjectId);
        },
        error: (err: HttpErrorResponse) => {
          this.loading = false;
          console.error('[CQRS Error] 🚨 完整錯誤物件:', err);

          if (err.status === 200 || err.status === 201) {
            console.warn(
              '[CQRS] 後端回傳了成功狀態碼，但前端解析 JSON 失敗。嘗試強行提取 ID...',
            );
            const rawResponseText = err.error?.text || '';
            const generatedProjectId = rawResponseText.replace(/["']/g, '');

            if (generatedProjectId) {
              this.handleSuccessRedirect(projectCode, generatedProjectId);
              return;
            }
          }

          if (err.status === 0) {
            this.errorMessage =
              'CORS 跨網域請求被阻擋！請檢查 Spring Boot 的 @CrossOrigin 設定。';
            this.systemMessageService.showError(
              '連線被拒絕 (CORS)',
              '請按 F12 查看 Console 的紅色跨網域錯誤',
            );
            return;
          }

          let errorMsg = '系統發生預期外錯誤，請稍後再試';
          if (err.error) {
            const backendResponse = err.error;
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
