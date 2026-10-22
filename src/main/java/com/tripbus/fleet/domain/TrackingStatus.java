package com.tripbus.fleet.domain;

/** US-174: estado del rastreo del bus en un viaje. */
public enum TrackingStatus {
    /** El conductor está compartiendo su ubicación. */
    ACTIVO,
    /** El conductor apagó el botón de compartir. */
    EN_PAUSA,
    /** Activo, pero sin posiciones por más de tripbus.fleet.signal-timeout. */
    SIN_SENAL,
    /** Terminó la ventana de rastreo o se canceló el viaje: no se acepta nada más. */
    FINALIZADO;

    /** ¿Se aceptan posiciones en este estado? */
    public boolean recibePosiciones() {
        return this == ACTIVO || this == SIN_SENAL;
    }
}
