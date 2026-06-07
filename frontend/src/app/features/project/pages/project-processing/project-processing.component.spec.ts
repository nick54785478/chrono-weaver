import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ProjectProcessingComponent } from './project-processing.component';

describe('ProjectProcessingComponent', () => {
  let component: ProjectProcessingComponent;
  let fixture: ComponentFixture<ProjectProcessingComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProjectProcessingComponent]
    })
    .compileComponents();

    fixture = TestBed.createComponent(ProjectProcessingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
