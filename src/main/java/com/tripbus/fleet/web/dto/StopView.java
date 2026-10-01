package com.tripbus.fleet.web.dto;

import java.time.Instant;

public record StopView(int orden, String nombre, double lat, double lng, Instant horaProgramada) {
}
