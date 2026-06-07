import { TestBed } from '@angular/core/testing';

import { GanttSyncService } from './gantt-sync.service';

describe('GanttSyncService', () => {
  let service: GanttSyncService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(GanttSyncService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
