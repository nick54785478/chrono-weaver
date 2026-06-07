package com.example.demo.iface.dto;

import java.util.List;

import com.example.demo.infra.projection.ProjectView;

public record ProjectGottenResource(String code, String message, List<ProjectView> data) {

}
