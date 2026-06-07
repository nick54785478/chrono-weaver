import { Button } from 'primeng/button';
import { InputText } from 'primeng/inputtext';
import { ScrollPanel } from 'primeng/scrollpanel';
import { ProgressSpinner } from 'primeng/progressspinner';
import { Card } from 'primeng/card';
import { Toast } from 'primeng/toast';
import { ConfirmDialog } from 'primeng/confirmdialog';
import { Tooltip } from 'primeng/tooltip';
import { Avatar } from 'primeng/avatar';
import { SelectButton } from 'primeng/selectbutton';
import { FileUpload } from 'primeng/fileupload';
import { Dialog } from 'primeng/dialog';
import { Chip } from 'primeng/chip';

/**
 * 將 Prime NG 組件打包成一個常數陣列匯出
 * Angular 18 + 推薦直接使用 Component 進行導入
 */
export const PRIME_COMPONENTS = [
  Button,
  Chip,
  InputText,
  ScrollPanel,
  ProgressSpinner,
  Card,
  Toast,
  ConfirmDialog,
  Tooltip,
  Avatar,
  SelectButton,
  FileUpload,
  Dialog,
] as const;
