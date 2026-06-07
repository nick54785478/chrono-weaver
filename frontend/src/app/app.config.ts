import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import Lara from '@primeng/themes/lara';
import { routes } from './app.routes';
import { provideClientHydration } from '@angular/platform-browser';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { providePrimeNG } from 'primeng/config';
import { provideHttpClient } from '@angular/common/http';
import { ConfirmationService, MessageService } from 'primeng/api';

export const appConfig: ApplicationConfig = {
  providers: [
    // provideAnimations(),
    provideHttpClient(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideClientHydration(),
    provideAnimationsAsync(),
    providePrimeNG({
      theme: {
        preset: Lara, // 👈 這裡就決定了你的 UI 長相
        options: {
          darkModeSelector: '.my-app-dark', // 設定切換深色模式的 class
        },
      },
    }),
    MessageService, // 💡 負責 p-toast
    ConfirmationService, // 💡 負責 p-confirmDialog
  ],
};
