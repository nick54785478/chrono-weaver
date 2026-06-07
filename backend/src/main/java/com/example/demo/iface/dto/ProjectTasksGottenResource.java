package com.example.demo.iface.dto;

import java.util.List;

import com.example.demo.infra.projection.TaskView;

public record ProjectTasksGottenResource(String code, String message, List<TaskView> data) {

}
