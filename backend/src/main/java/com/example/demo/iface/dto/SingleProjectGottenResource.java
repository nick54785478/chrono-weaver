package com.example.demo.iface.dto;

import com.example.demo.infra.projection.ProjectView;

public record SingleProjectGottenResource(String code, String message, ProjectView data) {

}
