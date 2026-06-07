import { Component } from '@angular/core';

import { RouterModule } from '@angular/router';
import { Ripple } from 'primeng/ripple'; // 水波紋特效
@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [RouterModule],
  providers: [Ripple],
  templateUrl: './sidebar.component.html',
  styleUrl: './sidebar.component.scss',
})
export class SidebarComponent {
  currentUser = '工程師 A';
}
