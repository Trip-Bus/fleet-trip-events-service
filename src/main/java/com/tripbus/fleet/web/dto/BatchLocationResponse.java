package com.tripbus.fleet.web.dto;

import java.util.List;

/** US-173: resultado del lote, con cada lectura en orden de timestamp. */
public record BatchLocationResponse(int aceptadas, int descartadas, List<LocationResult> lecturas) {
}
