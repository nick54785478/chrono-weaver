import { Routes } from '@angular/router';
import { MainLayoutComponent } from './features/layout/pages/main-layout/main-layout.component';
import { CoEditorComponent } from './features/co-editor/pages/co-editor/co-editor.component';
import { GanttComponent } from './features/gantt/pages/gantt/gantt.component';
import { CreateProjectComponent } from './features/project/pages/create-project/create-project.component';
import { ProjectProcessingComponent } from './features/project/pages/project-processing/project-processing.component';
import { TeamManagementComponent } from './features/team/pages/team-management/team-management.component';
import { ProjectSummaryComponent } from './features/project/pages/project-summary/project-summary.component';
import { ProjectListComponent } from './features/project/pages/project-list/project-list.component';

export const routes: Routes = [
  {
    path: '',
    component: MainLayoutComponent,
    children: [
      // 🌟 關鍵修正：必須加上 /:id，這樣 Angular 才知道怎麼接收網址列的 UUID！
      { path: 'co-editor/:id', component: CoEditorComponent },

      // 💡 額外防呆：如果使用者單純點選 Sidebar 進入 /co-editor (沒帶 ID)
      // 我們一律讓它維持指向同一個元件，元件內部才能觸發 LocalStorage 記憶機制
      { path: 'co-editor', component: CoEditorComponent },

      { path: 'project-processing/:id', component: ProjectProcessingComponent },
      { path: 'create-project', component: CreateProjectComponent },
      { path: 'gantt', component: GanttComponent },
      { path: 'teams', component: TeamManagementComponent },
      { path: 'project-list', component: ProjectListComponent },
      { path: 'project-summary', component: ProjectSummaryComponent },
      { path: '', redirectTo: 'create-project', pathMatch: 'full' },
    ],
  },
  { path: '**', redirectTo: '/create-project' },
];
