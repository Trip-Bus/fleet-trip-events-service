# Fleet + Trip Events Service

Todo lo que ocurre durante el viaje: posiciones GPS continuas y novedades (inicio, llegada a
parada, retraso, desvío, fin). Incluye el **simulador GPS** del MVP.

**HU:** US-038, US-039, US-046, US-047 · **Puerto:** 16005 · **BD:** `tripbus_fleet`

## API

| Método | Endpoint | Descripción |
|---|---|---|
| POST | `/telemetry/position` | Ingesta GPS (CONDUCTOR/OPERADOR/ADMIN). Responde 202: se encola en `tripbus:telemetry` y un consumidor independiente la procesa. |
| GET | `/buses/{id}/position` | Última posición conocida (Redis). |
| GET | `/itineraries/{id}/bus-position` | Dónde va mi bus: posición + estado + paradas para el mapa. |
| POST | `/trips/{id}/events` `{tipo, descripcion, detalle}` | Novedad del viaje (conductor/operador **de la misma empresa**). |
| GET | `/trips/{id}/timeline` | Línea de tiempo de novedades. |

**Transactional outbox**: `PositionUpdated` y `TripEventReported` se guardan en la tabla `outbox`
en la misma transacción y un *relay* los publica (`FOR UPDATE SKIP LOCKED`, seguro con varias réplicas).

**Consume:** `ItineraryPublished` (bus ↔ itinerario), `ItineraryCancelled`.

## Simulador GPS

Con `SIMULATOR_ENABLED=true` mueve cada 5 s los buses de los viajes en curso siguiendo las
paradas de su ruta y genera las novedades INICIO / LLEGADA_PARADA / FIN. Usa la misma ruta de
ingesta que un GPS real y corre en un solo nodo. En Kubernetes es un Deployment aparte (`fleet-gps-simulator`).

## Ejecutar

Toda la plataforma: `cd ../API-gateway && docker compose up -d --build` (ver README de API-gateway).

Solo este servicio (con PostgreSQL y Redis del compose levantados):

```bash
mvn spring-boot:run          # puerto 16005
```

## Pruebas

```bash
mvn test                     # unitarias
TRIPBUS_IT=true mvn test     # + integración contra PostgreSQL/Redis reales (base tripbus_fleet_test)
```

El pipeline `.github/workflows/ci-cd.yml` corre todas las pruebas y publica la imagen en GHCR.
