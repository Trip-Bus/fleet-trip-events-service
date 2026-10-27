package com.tripbus.fleet.domain;

public enum TripEventType {
    INICIO,
    LLEGADA_PARADA,
    RETRASO,
    DESVIO,
    FIN,
    /** US-176: novedades graves (crean una alerta para el operador). */
    AVERIA,
    ACCIDENTE,
    VIA_CERRADA,
    /** US-176: botón de pánico. Nunca va a la línea de tiempo pública. */
    SOS;

    /** US-176: SOS y ACCIDENTE son CRITICA; AVERIA y VIA_CERRADA, ALTA; el resto, NORMAL. */
    public Gravedad gravedad() {
        return switch (this) {
            case SOS, ACCIDENTE -> Gravedad.CRITICA;
            case AVERIA, VIA_CERRADA -> Gravedad.ALTA;
            default -> Gravedad.NORMAL;
        };
    }

    /** ¿Esta novedad crea una alerta para el operador? */
    public boolean generaAlerta() {
        return gravedad() != Gravedad.NORMAL;
    }

    public enum Gravedad {
        NORMAL,
        ALTA,
        CRITICA
    }
}
