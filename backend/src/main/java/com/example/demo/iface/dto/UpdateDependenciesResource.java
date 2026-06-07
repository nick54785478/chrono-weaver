package com.example.demo.iface.dto;

import java.util.Set;

//接收 Set<String> 的 DTO
public record UpdateDependenciesResource(Set<String> dependencyIds) {
}
