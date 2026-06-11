import { TestBed } from '@angular/core/testing';

import { ProjectTeamService } from './project-team.service';

describe('ProjectTeamService', () => {
  let service: ProjectTeamService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(ProjectTeamService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
