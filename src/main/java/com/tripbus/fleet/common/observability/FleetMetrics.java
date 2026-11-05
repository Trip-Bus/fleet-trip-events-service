package com.tripbus.fleet.common.observability;

import com.tripbus.fleet.domain.TripEventType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * HT-10: métricas propias de fleet (en Prometheus, ver {@link MetricsConfig}):
 * <ul>
 *   <li>{@code tripbus_posiciones_total{resultado=aceptada|descartada, motivo}}</li>
 *   <li>{@code tripbus_alertas_total{tipo, gravedad}}</li>
 *   <li>{@code tripbus_procesar_posicion_segundos} (Timer del consumidor de telemetría)</li>
 * </ul>
 * El Gauge de sesiones de rastreo por estado está en {@link TrackingSessionGauges}.
 */
@Component
public class FleetMetrics {

    static final String POSICIONES = "tripbus.posiciones";
    static final String ALERTAS = "tripbus.alertas";
    static final String PROCESAR_POSICION = "tripbus.procesar.posicion";
    static final String ACEPTADA = "aceptada";
    static final String DESCARTADA = "descartada";
    /** Motivo de las aceptadas (Prometheus pide las mismas etiquetas en toda la serie). */
    static final String SIN_MOTIVO = "ninguno";

    private final MeterRegistry registry;
    private final Timer procesarPosicion;

    public FleetMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.procesarPosicion = Timer.builder(PROCESAR_POSICION)
                .description("Tiempo de procesar una posición del stream de telemetría")
                .register(registry);
    }

    public void posicionAceptada() {
        posiciones(ACEPTADA, SIN_MOTIVO).increment();
    }

    public void posicionDescartada(String motivo) {
        posiciones(DESCARTADA, motivo).increment();
    }

    public void alerta(TripEventType tipo, TripEventType.Gravedad gravedad) {
        Counter.builder(ALERTAS).description("Alertas creadas para el operador")
                .tag("tipo", tipo.name()).tag("gravedad", gravedad.name())
                .register(registry).increment();
    }

    /** Mide el procesamiento de una posición (también si falla). */
    public void procesarPosicion(Runnable tarea) {
        procesarPosicion.record(tarea);
    }

    private Counter posiciones(String resultado, String motivo) {
        return Counter.builder(POSICIONES).description("Posiciones GPS recibidas, aceptadas o descartadas")
                .tag("resultado", resultado).tag("motivo", motivo)
                .register(registry);
    }
}
