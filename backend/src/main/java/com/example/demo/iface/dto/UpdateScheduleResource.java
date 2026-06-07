package com.example.demo.iface.dto;

import java.time.LocalDate;

public record UpdateScheduleResource(LocalDate startDate, LocalDate endDate) {

}
