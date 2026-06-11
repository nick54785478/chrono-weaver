import { Component, OnInit } from '@angular/core';
import { TeamMember } from '../../../../shared/models/team-member';
import {
  FormBuilder,
  FormGroup,
  FormsModule,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { SystemStorageKey } from '../../../../core/enums/system-storage.enum';
import { ActivatedRoute } from '@angular/router';
import { ProjectTeamService } from '../../../../shared/services/project-team.service';
import { StorageService } from '../../../../core/services/storage.service';
import { SystemMessageService } from '../../../../shared/services/system-message.service';
import { CommonModule } from '@angular/common';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { CardModule } from 'primeng/card';
import { TableModule } from 'primeng/table';
import { MultiSelectModule } from 'primeng/multiselect';
import { SharedModule } from 'primeng/api';
import { SelectModule } from 'primeng/select';
import { InputTextModule } from 'primeng/inputtext';

@Component({
  selector: 'app-team-management',
  standalone: true,
  templateUrl: './team-management.component.html',
  imports: [
    FormsModule,
    ReactiveFormsModule,
    MultiSelectModule,
    CardModule,
    InputTextModule,
    CommonModule,
    ButtonModule,
    DialogModule,
    TableModule,
    SelectModule,
  ],
  styleUrls: ['./team-management.component.scss'],
})
export class TeamManagementComponent implements OnInit {
  projectId: string = '';
  tenantId: string = '';

  members: TeamMember[] = [];
  loading: boolean = false;

  // 新增成員相關
  showAddDialog: boolean = false;
  addForm!: FormGroup;
  isSubmitting: boolean = false;

  // 角色選單 (供 p-select 使用)
  roleOptions = [
    { label: '專案負責人 (OWNER)', value: 'OWNER' },
    { label: '系統分析師 (SA)', value: 'SA' },
    { label: '開發人員 (DEVELOPER)', value: 'DEVELOPER' },
    { label: '一般成員 (MEMBER)', value: 'MEMBER' },
  ];

  constructor(
    private route: ActivatedRoute,
    private fb: FormBuilder,
    private teamService: ProjectTeamService,
    private storageService: StorageService,
    private systemMessageService: SystemMessageService,
  ) {}

  ngOnInit(): void {
    console.log();
    // 從路由取得 UUID (例如 /projects/ccbb9deb.../team)
    // this.projectId = this.route.snapshot.paramMap.get('id') || '';
    this.projectId =
      this.storageService.getLocalStorageItem(
        SystemStorageKey.LAST_PROJECT_ID,
      ) || '';
    this.tenantId =
      this.storageService.getLocalStorageItem(SystemStorageKey.TENANT) ||
      this.storageService.getLocalStorageItem(SystemStorageKey.TENANT) ||
      'DDD';

    this.initForm();
    this.loadMembers();
  }

  initForm(): void {
    this.addForm = this.fb.group({
      userId: ['', [Validators.required]],
      role: ['MEMBER', [Validators.required]],
    });
  }

  // --- 查詢清單 ---
  loadMembers(): void {
    this.loading = true;
    this.teamService.getMembers(this.projectId, this.tenantId).subscribe({
      next: (data) => {
        this.members = data;
        this.loading = false;
      },
      error: (err) => {
        this.systemMessageService.showError('載入失敗', '無法取得團隊名單');
        this.loading = false;
      },
    });
  }

  // --- 新增成員 ---
  openAddDialog(): void {
    this.addForm.reset({ role: 'MEMBER' });
    this.showAddDialog = true;
  }

  onSubmitAdd(): void {
    if (this.addForm.invalid) return;

    this.isSubmitting = true;
    const { userId, role } = this.addForm.value;

    this.teamService
      .addMember(this.projectId, userId, role, this.tenantId)
      .subscribe({
        next: () => {
          this.systemMessageService.showSuccess(
            '新增成功',
            `已將使用者 ${userId} 加入團隊`,
          );

          // ==========================================
          // 🌟 樂觀更新 (Optimistic Update)
          // ==========================================
          // 1. 建立一筆本地的虛擬成員資料
          const optimisticMember: TeamMember = {
            userId: userId,
            role: role,
            // 由於後端的加入時間還沒寫入，前端先拿當下時間墊檔，讓畫面能顯示
            joinedAt: new Date().toISOString(),
          };

          // 2. 更新陣列！
          // 小提示：使用展開運算子 [...] 賦予陣列全新的記憶體參考，
          // 這樣才能確實觸發 Angular 與 PrimeNG p-table 的變更偵測 (Change Detection) 更新畫面。
          this.members = [...this.members, optimisticMember];

          this.showAddDialog = false;
          this.isSubmitting = false;

          // (拔除了原本的 setTimeout loadMembers)
        },
        error: (err) => {
          this.systemMessageService.showError(
            '新增失敗',
            err.error || '無法加入團隊',
          );
          this.isSubmitting = false;
        },
      });
  }

  // --- 變更角色 (行內觸發) ---
  onRoleChange(member: TeamMember, newRole: string): void {
    if (member.role === newRole) return; // 角色沒變不發 API

    this.teamService
      .changeRole(this.projectId, member.userId, newRole, this.tenantId)
      .subscribe({
        next: () => {
          this.systemMessageService.showSuccess(
            '更新成功',
            `已變更 ${member.userId} 的角色`,
          );
          member.role = newRole; // 樂觀更新畫面
        },
        error: (err) => {
          this.systemMessageService.showError(
            '更新失敗',
            err.error || '無法變更角色',
          );
          this.loadMembers(); // 失敗則重撈資料還原畫面
        },
      });
  }

  // --- 移除成員 ---
  removeMember(member: TeamMember): void {
    if (!confirm(`確定要將 ${member.userId} 移出團隊嗎？`)) return;

    this.teamService
      .removeMember(this.projectId, member.userId, this.tenantId)
      .subscribe({
        next: () => {
          this.systemMessageService.showSuccess(
            '移除成功',
            `已將 ${member.userId} 移出團隊`,
          );
          this.members = this.members.filter((m) => m.userId !== member.userId); // 樂觀刪除
        },
        error: (err) => {
          this.systemMessageService.showError(
            '移除失敗',
            err.error || '無法移除成員',
          );
        },
      });
  }
}
