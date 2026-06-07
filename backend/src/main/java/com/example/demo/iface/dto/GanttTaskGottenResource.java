package com.example.demo.iface.dto;

import java.util.List;

import com.example.demo.application.shared.dto.GanttTaskGottenResult;

public record GanttTaskGottenResource(String code, String message, List<GanttTaskGottenResult> data) {

}
