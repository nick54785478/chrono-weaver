import { ComponentFixture, TestBed } from '@angular/core/testing';

import { CoEditorComponent } from './co-editor.component';

describe('CoEditorComponent', () => {
  let component: CoEditorComponent;
  let fixture: ComponentFixture<CoEditorComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CoEditorComponent]
    })
    .compileComponents();

    fixture = TestBed.createComponent(CoEditorComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
