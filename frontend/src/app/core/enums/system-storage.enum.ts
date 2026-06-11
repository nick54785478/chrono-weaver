/**
 * 系統把資料儲存到 LocalStorage 內用的 key 值。
 */
export enum SystemStorageKey {
  JWT_TOKEN = 'token',
  USERNAME = 'username',
  TENANT = 'tenant',
  NAME = 'name',
  REFRESH_TOKEN = 'refreshToken',
  REDIRECT_URL = 'redirectUrl',
  QUERY_PARAMS = 'queryParams',
  PERMISSIONS = 'permissions',
  LAST_PROJECT_ID = 'last_project_id',
}
