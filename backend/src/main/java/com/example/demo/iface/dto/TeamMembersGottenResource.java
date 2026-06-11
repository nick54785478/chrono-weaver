package com.example.demo.iface.dto;

import java.util.List;

import com.example.demo.infra.projection.TeamMemberView;

public record TeamMembersGottenResource(String code, String message, List<TeamMemberView> data) {

}
