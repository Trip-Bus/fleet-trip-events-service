# Fleet + Trip Events Service

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=Trip-Bus_fleet-trip-events-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=Trip-Bus_fleet-trip-events-service)

Todo lo que ocurre durante el viaje: posiciones GPS continuas y novedades (inicio, llegada a
parada, retraso, desvío, fin). Incluye el **simulador GPS** del MVP.

**Stack:** Java 21 · Spring Boot 3.5.16 · PostgreSQL 16 · Redis 7

**HU:** US-038, US-039, US-046, US-047, US-121, US-170, US-171, US-173, US-174, US-175, US-176, US-177, US-178, US-180, US-181, US-182, US-193, HT-08, HT-10, HT-16 · **Puerto:** 18005 · **BD:** `tripbus_fleet` (puerto QA 18105)

## API

| Método | Endpoint | Descripción |
|---|---|---|
| POST | `/driver/tracking/start` | US-173 (CONDUCTOR): enciende el rastreo de su viaje activo (`ACTIVO`). Responde `{itineraryId, busId, estado, ultimaPosicionEn}`. |
| POST | `/driver/tracking/stop` | US-173 (CONDUCTOR): pone el rastreo en pausa (`EN_PAUSA`). |
| POST | `/driver/location` `{lat, lng, precision, velocidad, rumbo, timestamp}` | US-173 (CONDUCTOR): ubicación del celular, **sin** `busId` ni `itineraryId` (fleet los resuelve con la asignación activa). 202 `{aceptada, eventId}` o `{aceptada: false, motivo}`. |
| POST | `/driver/location/batch` `[hasta 50 lecturas]` | US-173 (CONDUCTOR): lo que el celular guardó sin conexión, procesado en orden de timestamp. 202 `{aceptadas, descartadas, lecturas[]}`. |
| POST | `/telemetry/position` | Ingesta GPS manual, **solo ADMIN** (pruebas). Responde 202: se encola en `tripbus:telemetry` y un consumidor independiente la procesa. |
| GET | `/buses/{id}/position` | **Deprecado (se retira en el Sprint 5):** usar `/itineraries/{id}/bus-position`. Última posición conocida (Redis). Pública, solo si el bus tiene un viaje en ventana activa; si no, 404 `NO_ACTIVE_TRIP`. |
| GET | `/itineraries/{id}/bus-position` | Dónde va mi bus: estado + paradas para el mapa y la posición (en `null` fuera de la ventana activa o si está cancelado), más `estadoRastreo` y `ultimaPosicionEn` (US-174; `null` si nunca se encendió) y `eta` (US-180; `null` fuera de la ventana, cancelado, finalizado o sin posiciones aún). Pública. |
| GET | `/itineraries/{id}/eta` | US-180: `{itineraryId, retrasoMinutos, calculadaEn, paradas: [{orden, nombre, programada, estimada, alcanzada}]}`. Pública. 404 `NO_ETA` fuera de la ventana, cancelado, finalizado o sin posiciones aún; 404 `TRIP_NOT_FOUND` si el viaje no existe. |
| POST | `/trips/{id}/events` `{tipo, descripcion, detalle, lat, lng, puedeContinuar}` | Novedad del viaje (operador **de la misma empresa**, o ADMIN). Un CONDUCTOR, además, debe estar **asignado** al viaje (si no, 403 `NOT_ASSIGNED`). US-176: las graves crean una alerta; `SOS` no va a la línea de tiempo y responde la alerta (201 nueva, 200 si ya había una abierta). Ver [tipos de novedad](#tipos-de-novedad-post-tripsidevents). |
| POST | `/driver/trips/{itineraryId}/start` `{checklist, observacion}` | US-171 (CONDUCTOR asignado): inicia el viaje. Solo dentro de la ventana (409 `OUTSIDE_TRACKING_WINDOW`), con todos los ítems de `tripbus.fleet.checklist-items` en `true` (400 `CHECKLIST_INCOMPLETE` con `details.faltantes`) y una sola vez (409 `TRIP_ALREADY_STARTED`). Registra `INICIO` (sin duplicar la del simulador) y enciende el rastreo (`ACTIVO`). Responde `{itineraryId, iniciadoEn, finalizadoEn, estadoRastreo}`. |
| POST | `/driver/trips/{itineraryId}/finish` | US-171 (CONDUCTOR asignado): finaliza el viaje si ya inició (409 `TRIP_NOT_STARTED` / `TRIP_ALREADY_FINISHED`). Registra `FIN` y el rastreo pasa a `FINALIZADO`. |
| POST | `/trips/{itineraryId}/finish` | US-171: fin forzado por el OPERADOR de la empresa (otra empresa: 404) o un ADMIN. Misma lógica. |
| POST | `/driver/sos` `{lat, lng, mensaje}` | US-176 (CONDUCTOR): botón SOS de un toque sobre su viaje activo (409 `NO_ACTIVE_TRIP` / `OUTSIDE_TRACKING_WINDOW`). Una sola alerta SOS abierta por viaje: si se repite, 200 con la misma; si es nueva, 201. Nunca va a la línea de tiempo pública. Sin `lat`/`lng` usa la última posición del viaje. |
| GET | `/trips/{id}/alerts` | US-176: alertas del viaje, las más recientes primero. OPERADOR de la empresa (otra empresa: 404) o ADMIN. |
| POST | `/trips/{id}/alerts/{alertId}/resolve` `{nota}` | US-176: pasa la alerta a `RESUELTA` y publica `TripAlertResolved`. OPERADOR de la empresa o ADMIN; alerta de otra empresa u otro viaje: 404 `ALERT_NOT_FOUND`; ya resuelta: 409 `ALERT_ALREADY_RESOLVED`. |
| GET | `/fleet/live?companyId=` | US-178: mapa de la flota. OPERADOR: los viajes de su empresa (el `companyId` de la consulta se ignora); ADMIN: la empresa de `companyId` o, sin él, todas. Ver [mapa de la flota](#mapa-de-la-flota-us-178). |
| GET | `/alerts?estado=ABIERTA\|RESUELTA` | US-176: alertas de la empresa del operador (un ADMIN ve todas), hasta 200, las más recientes primero. Sin `estado`: todas. |
| GET | `/trips/{id}/timeline` | Línea de tiempo de novedades. Pública. |
| POST | `/share-links` `{itineraryId}` | US-181 (cualquier usuario autenticado): crea un enlace para compartir el viaje con un familiar. 201 `{codigo, itineraryId, venceEn}`. Ver [compartir mi viaje](#compartir-mi-viaje-us-181). |
| GET | `/share/{codigo}` | US-181: pública. `{itineraryId, origen, destino, fechaSalida, venceEn}`; vencido o inexistente: 404 `SHARE_LINK_NOT_FOUND`. |
| GET | `/itineraries/{id}/track` | US-182: pública, **solo viajes terminados** (finalizados o pasada su ventana de rastreo; no cancelados; si no, 404 `TRIP_NOT_FINISHED`). Recorrido (máximo un punto por minuto), paradas con hora programada y real y resumen de puntualidad. Ver [puntualidad](#puntualidad-us-182-us-193). |
| GET | `/reports/punctuality?desde=&hasta=&agrupar=ruta\|conductor&companyId=` | US-193: OPERADOR (su empresa; el `companyId` se ignora) o ADMIN (`companyId` o, sin él, todas). Fechas `YYYY-MM-DD` (días de `America/Bogota` por salida programada), máximo 366 días. |
| PUT | `/trips/{itineraryId}/driver` `{conductorId, rol}` | US-121: asigna o reemplaza el conductor del rol (`PRINCIPAL` por defecto, o `RELEVO`). OPERADOR de la empresa del viaje o ADMIN. |
| DELETE | `/trips/{itineraryId}/driver/{rol}` | US-121: quita la asignación (204; 404 `ASSIGNMENT_NOT_FOUND` si no hay). OPERADOR de la empresa o ADMIN. |
| GET | `/trips/{itineraryId}/driver` | US-121: conductores asignados. OPERADOR de la empresa, ADMIN o un conductor asignado al viaje. |
| GET | `/driver/trips?fecha=YYYY-MM-DD` | US-170 (CONDUCTOR): mis viajes del día (por defecto hoy en `America/Bogota`) ordenados por salida, con `rol`, `estado`, `ventanaInicio`/`ventanaFin`, `puedeCompartirUbicacion` e `iniciado`/`finalizado` (US-171). |
| GET | `/driver/trips/active` | US-170 (CONDUCTOR): el viaje asignado que está ahora en su ventana de rastreo, o 404 `NO_ACTIVE_TRIP`. |

**Asignación de conductores (US-121):** máximo un `PRINCIPAL` y un `RELEVO` por viaje (tabla
`trip_assignments`, único `(itinerary_id, rol)`). Validaciones:

- El viaje existe, es de la empresa del operador (otra empresa → 404 `TRIP_NOT_FOUND`), no está
  cancelado (409 `TRIP_CANCELLED`) ni terminó (409 `TRIP_FINISHED`).
- El conductor se consulta en identity (`GET /internal/users/{id}`, `X-Internal-Token`, timeouts
  300 ms / 800 ms): debe tener rol CONDUCTOR (400 `INVALID_DRIVER`, también si no existe), estar
  activo (400 `DRIVER_INACTIVE`) y ser de la empresa del viaje (400 `DRIVER_OTHER_COMPANY`). Si
  identity no responde: 503 `IDENTITY_UNAVAILABLE`.
- Sin cruces: un conductor no puede tener dos viajes cuyas ventanas de rastreo
  (`fechaSalida - 30 min` a `fechaLlegada + 30 min`) se solapen; tampoco ser principal y relevo del
  mismo viaje. Si chocan: 409 `DRIVER_SCHEDULE_CONFLICT` con `details.itineraryId` del viaje en
  conflicto. Para que dos asignaciones simultáneas no se cuelen, la transacción toma
  `pg_advisory_xact_lock` sobre el conductor antes de verificar (y bloquea la fila del viaje).
- Si el viaje se cancela (`ItineraryCancelled`) sus asignaciones se eliminan (con `DriverUnassigned`).

**Posición solo durante el viaje (US-039, US-047):** la ventana activa de un itinerario va de
`fechaSalida - 30 min` a `fechaLlegada + 30 min` (`tripbus.fleet.tracking-window-before` / `-after`)
y nunca aplica si está cancelado. Fuera de ella la posición se guarda en `bus_positions` (auditoría)
pero no se expone ni se publica `PositionUpdated`. Estado: `CANCELADO` > `FINALIZADO` (después de
`fechaLlegada`) > `PROGRAMADO` > `SIN_SENAL` > `EN_RUTA`. US-171: si el conductor marcó inicio o fin,
mandan esos datos: `FINALIZADO` tras el fin y `EN_RUTA` tras el inicio, aunque no haya llegado la hora
estimada (sin ellos, por ejemplo con el simulador, sigue el cálculo por horario).

### Rastreo con el celular del conductor (US-173 a US-175)

El bus se rastrea con la ubicación del celular del conductor asignado (US-121):

1. **Solo dentro de la ventana del viaje asignado (US-175).** `start`, `stop`, `location` y `batch`
   buscan el viaje asignado al conductor, no cancelado, cuya ventana de rastreo contiene este
   instante. Sin viaje asignado → 409 `NO_ACTIVE_TRIP`; con un viaje asignado a menos de 24 h pero
   fuera de su ventana → 409 `OUTSIDE_TRACKING_WINDOW`. Con la sesión en pausa, sin encender o
   finalizada → 409 `TRACKING_NOT_ACTIVE` y no se guarda nada.
2. **Filtros por lectura (US-173).** Una lectura mala se descarta y se responde 202 con
   `aceptada: false` y el `motivo`, sin romper el envío: `PRECISION_BAJA` (precisión mayor que
   `tripbus.fleet.max-accuracy-m`, 100 m), `TIMESTAMP_FUTURO` (más de 30 s en el futuro),
   `POSICION_ANTIGUA` (más de 10 min), `FUERA_DE_VENTANA` (antes de que empezara la ventana),
   `COORDENADAS_INVALIDAS` (0,0), `POSICION_DESORDENADA` (no es posterior a la última aceptada) y
   `SALTO_IMPOSIBLE` (velocidad implícita respecto a la última posición mayor a 150 km/h).
3. **Misma ingesta asíncrona.** Las aceptadas van al stream `tripbus:telemetry` con el `busId` y el
   `itineraryId` del viaje. `bus_positions` guarda además `conductor_id` y `precision`
   (`conductor_id` es `NULL` para el simulador y los datos anteriores). `conductorId` **nunca** va en
   `PositionUpdated` (es un tópico público).
4. **Estados (US-174)**, tabla `tracking_sessions` (una por viaje):

| Estado | Cuándo |
|---|---|
| `ACTIVO` | El conductor encendió el rastreo (`start`), o llegó una posición estando en `SIN_SENAL`. |
| `EN_PAUSA` | El conductor apagó el botón (`stop`). |
| `SIN_SENAL` | Estaba `ACTIVO` y no llegan posiciones hace más de `tripbus.fleet.signal-timeout` (60 s). |
| `FINALIZADO` | Terminó la ventana del viaje o se canceló. Desde ahí no se acepta nada más de ese viaje. |

El job `TrackingStatusJob` corre cada 15 s (`tripbus.jobs.tracking-status-ms`) en un solo nodo
(`LeaderLock`) y hace los pasos a `SIN_SENAL` y `FINALIZADO`. Cada cambio de estado publica
`TrackingStatusChanged` por el outbox en la misma transacción.

### Mapa de la flota (US-178)

`GET /fleet/live` devuelve los viajes de la empresa que están **ahora** dentro de su ventana de rastreo
y no cancelados (máximo 300, por `fechaSalida`), con un resumen arriba:

```json
{
  "generadoEn": "2026-10-29T15:00:00Z",
  "resumen": {"total": 4, "enRuta": 1, "activos": 1, "enPausa": 1, "sinSenal": 1, "retrasados": 1, "conAlerta": 2},
  "viajes": [{
    "itineraryId": "…", "busId": "…", "origen": "Bogotá", "destino": "Tunja",
    "fechaSalida": "…", "fechaLlegada": "…", "iniciado": true, "finalizado": false,
    "estadoRastreo": "ACTIVO",
    "posicion": {"lat": 4.66, "lng": -74.10, "velocidad": 35.5, "rumbo": 45.0, "timestamp": "…"},
    "retrasoMinutos": 20,
    "siguienteParada": {"orden": 2, "nombre": "Chocontá", "programada": "…", "estimada": "…"},
    "ultimaParadaAlcanzada": {"orden": 1, "nombre": "Bogotá", "programada": "…", "estimada": null},
    "alertas": {"abiertas": 2, "mayorGravedad": "CRITICA"},
    "conductor": {"conductorId": "…", "nombre": "Ana"}
  }]
}
```

- `enRuta`: iniciados y no finalizados; `activos`/`enPausa`/`sinSenal`: por estado de rastreo;
  `retrasados`: más de `delay-threshold-min` (10) minutos; `conAlerta`: con alguna alerta `ABIERTA`.
- `estadoRastreo` es `null` si el viaje aún no tiene sesión; `posicion`, `retrasoMinutos` y las paradas
  son `null` mientras no haya posición o ETA en Redis; `conductor` es el `PRINCIPAL` (o `null`).
- Pocas consultas sin importar cuántos viajes haya: una de viajes, una de sesiones, una de alertas
  abiertas, una de conductores y **un solo `MGET`** en Redis con `fleet:itinerary:{id}:last` y
  `fleet:itinerary:{id}:eta` de todos. Si Redis falla, el mapa carga sin posiciones.
- El nombre del conductor sale de identity (`GET /internal/users/{id}`) con caché en memoria de 10 min.
  Si identity no responde, `nombre` es `null`, el mapa carga igual y durante 30 s no se vuelve a llamar.
- El tiempo real del mapa lo dan `PositionUpdated`, `EtaUpdated` y `TrackingStatusChanged`, que traen
  `companyId` (nunca datos del conductor).

**Transactional outbox**: los eventos se guardan en la tabla `outbox` en la misma transacción que
el cambio y un *relay* los publica (`FOR UPDATE SKIP LOCKED`, seguro con varias réplicas).

**Publica:**

| Evento | Cuándo | Payload |
|---|---|---|
| `PositionUpdated` | Posición GPS de un viaje en ventana activa (sin `conductorId`: es público). US-178: con `companyId` para el mapa de la flota | `{busId, itineraryId, companyId, lat, lng, velocidad, rumbo, timestamp}` |
| `TrackingStatusChanged` | US-174: cambia el estado de rastreo del viaje | `{itineraryId, busId, companyId, estado, ultimaPosicionEn}` |
| `TripEventReported` | Novedad del viaje | `{tripEventId, itineraryId, busId, tipo, descripcion, payload, timestamp}` |
| `DriverAssigned` | US-121: se asigna un conductor (también al reemplazar) | `{itineraryId, companyId, conductorId, rol}` |
| `DriverUnassigned` | US-121: se quita, se reemplaza o se cancela el viaje (también al quitarla por un cruce tras reprogramar) | `{itineraryId, companyId, conductorId, rol}` |
| `DriverAssignmentConflict` | Se le quita la asignación al conductor para que el operador asigne otro. HT-16: tras `ItineraryRescheduled` queda con un cruce de horario (`motivo: CRUCE_DE_HORARIO`); HT-10: identity eliminó al usuario (`UserDeleted`, `motivo: CONDUCTOR_ELIMINADO`, `conflictoCon: null`) | `{itineraryId, companyId, conductorId, rol, motivo, conflictoCon}` |
| `TripAlertRaised` | US-176: novedad grave (`AVERIA`, `ACCIDENTE`, `VIA_CERRADA`) o SOS. Para el tópico **privado** de la empresa; sin datos del conductor | `{alertId, itineraryId, companyId, busId, tipo, gravedad, lat, lng, mensaje, puedeContinuar}` |
| `TripAlertResolved` | US-176: el operador resuelve la alerta | `{alertId, itineraryId, companyId, busId, tipo, nota, resueltoEn}` |
| `TripInterrupted` | US-176: la novedad trae `puedeContinuar: false`. El operador decide en catalog si reprograma o cancela (no es automático) | `{itineraryId, companyId, tipo}` |
| `EtaUpdated` | US-180: alguna ETA cambió 1 minuto o más; como máximo una vez cada 30 s por viaje. US-178: con `companyId` para el mapa de la flota | `{itineraryId, companyId, retrasoMinutos, paradas: [{orden, nombre, programada, estimada, alcanzada}]}` |

`TripEventReported` sale también para las novedades automáticas (`LLEGADA_PARADA`, `RETRASO`, `DESVIO`)
y `TripAlertRaised` para el desvío detectado (gravedad `ALTA`).

**Consume** (de catalog e identity; idempotente por `eventId` con `processed_events`):

| Evento | Efecto |
|---|---|
| `ItineraryPublished` | Crea el viaje (bus ↔ itinerario). |
| `ItineraryCancelled` | Marca el viaje cancelado y quita sus conductores. |
| `ItineraryRescheduled` `{itineraryId, fechaSalida, fechaLlegada}` | HT-16: actualiza las fechas (la ventana de rastreo se mueve sola). Si un conductor asignado queda con un cruce (regla de US-121), se le quita la asignación y se publica `DriverAssignmentConflict`. |
| `ItineraryBusChanged` `{itineraryId, busNuevo}` | HT-16: cambia el bus; la sesión de rastreo y las asignaciones se mantienen. |
| `RouteCreated` / `RouteUpdated` `{routeId, paradas}` | HT-16: reemplaza las paradas de la ruta en `route_stops` (la copia local que usa el mapa). |
| `UserDeleted` `{userId}` (de identity) | HT-10: si era conductor, se quita de sus asignaciones en viajes que aún no terminan (no cancelados, sin finalizar y con llegada futura) y se publica `DriverAssignmentConflict` (y `DriverUnassigned`) por cada una. El historial de los viajes terminados no se toca. |

### Tipos de novedad (`POST /trips/{id}/events`)

| Tipo | Gravedad | Línea de tiempo pública | Alerta al operador |
|---|---|---|---|
| `INICIO`, `LLEGADA_PARADA`, `RETRASO`, `DESVIO`, `FIN` | `NORMAL` | ✔ | — |
| `AVERIA`, `VIA_CERRADA` | `ALTA` | ✔ (sin datos del conductor) | ✔ |
| `ACCIDENTE` | `CRITICA` | ✔ (sin datos del conductor) | ✔ |
| `SOS` | `CRITICA` | **nunca** | ✔ (una sola abierta por viaje) |

`lat`/`lng` son opcionales: si no vienen se usa la última posición del viaje. Con `puedeContinuar: false`
se publica además `TripInterrupted`. `LLEGADA_PARADA`, `RETRASO` y `DESVIO` también las genera fleet
solo (ver abajo): esas llevan `origen: "AUTOMATICO"` en el detalle (y en la columna `origen`).

### Detección automática y hora estimada (US-177, US-180)

Se calcula al procesar **cada posición aceptada** de un viaje en ventana (del conductor o del simulador,
en `TelemetryService.process`). Corre después del commit de la posición, en su propia transacción:
si algo falla, la posición ya quedó guardada. Por posición se consulta la base una vez (el viaje) y,
solo al llegar a una parada, otra para no duplicar su `LLEGADA_PARADA`; las paradas salen de
`RouteStopsCache` (Redis `fleet:route:{id}:stops`) y el estado entre lecturas vive en Redis.

**Geometría:** la ruta es la polilínea de sus paradas en orden, **en línea recta entre paradas** (no es
el trazado de la vía). Distancias con haversine; distancia y proyección sobre cada tramo en un plano
local (`common/geo/Geo`).

- **Llegada a parada:** a menos de `stop-radius-m` de la siguiente parada pendiente (`stop-radius-ends-m`
  para la primera y la última) se registra `LLEGADA_PARADA` `{parada, orden}` una sola vez por parada
  (avance en `fleet_itineraries.ultima_parada_real`). Si ya existe para esa parada (p. ej. del
  simulador) no se duplica, y el simulador tampoco duplica la automática. Si el bus pasa una parada sin
  detectarla, cuenta la siguiente pendiente a la que llegue.
- **Retraso:** desde `delay-threshold-min` minutos de retraso se registra `RETRASO` `{minutos}`; se repite
  solo si creció 15 minutos más desde el último aviso (Redis `fleet:itinerary:{id}:retraso-avisado`).
  No se avisa una vez alcanzadas todas las paradas.
- **Desvío:** a más de `deviation-m` de la polilínea en 3 lecturas seguidas se registra `DESVIO`
  `{distanciaM}` y se crea una alerta de gravedad `ALTA` (`trip_alerts` + `TripAlertRaised`). Solo una
  alerta de desvío abierta a la vez por viaje; al volver a la ruta se reinicia la cuenta.
- **ETA (US-180):** hora programada de cada parada = `fechaSalida + minutos_desde_salida`. La posición se
  proyecta sobre el tramo entre la última parada alcanzada y la siguiente (si ya va más adelante, sobre
  el tramo más cercano desde ahí) y la fracción recorrida da la hora programada de ese punto:
  `retraso = ahora − esa hora` (nunca menor a −5 min). Siguiente parada: si la velocidad promedio de las
  últimas 3 lecturas supera 10 km/h, `ahora + distancia restante / velocidad`; si no, `programada +
  retraso`. Demás paradas: `programada + retraso`, nunca antes de ahora. Se guarda en Redis
  (`fleet:itinerary:{id}:eta`) y se publica `EtaUpdated` solo si alguna ETA cambió 1 minuto o más y como
  máximo una vez por `eta-publish-interval` por viaje.

| Propiedad (`tripbus.fleet.*`) | Por defecto | Qué controla |
|---|---|---|
| `stop-radius-m` | `150` | Radio (m) de llegada a una parada intermedia |
| `stop-radius-ends-m` | `300` | Radio (m) de llegada a la primera y la última parada |
| `delay-threshold-min` | `10` | Minutos de retraso desde los que se registra `RETRASO` |
| `deviation-m` | `1000` | Distancia (m) a la ruta a partir de la cual una lectura cuenta como desvío |
| `eta-publish-interval` | `30s` | Intervalo mínimo entre dos `EtaUpdated` del mismo viaje |

### Compartir mi viaje (US-181)

- `POST /share-links` crea un código de **8 caracteres** del alfabeto `ABCDEFGHJKMNPQRSTUVWXYZ23456789` (sin
  `0`/`O` ni `1`/`I`/`L`, que se confunden al dictarlo) con `SecureRandom`. Se guarda en Redis
  (`fleet:share:{codigo}`) **solo** con `{itineraryId, venceEn}`: el enlace nunca lleva datos del pasajero.
- Vence en la **llegada estimada + 2 h**: el fin real si el viaje ya terminó; si no, la ETA de la última
  parada (US-180) o, sin ETA, `fechaLlegada`. La llave expira sola en Redis en ese momento.
- Máximo **5 enlaces por usuario y viaje** (contador `fleet:share-limit:{itineraryId}:{userId}` con el mismo
  vencimiento): el sexto responde 409 `SHARE_LINK_LIMIT`. Viaje cancelado: 409 `TRIP_CANCELLED`; ya vencido:
  409 `TRIP_FINISHED`; inexistente: 404 `TRIP_NOT_FOUND`. Redis caído: 503 `SHARE_UNAVAILABLE`.
- `GET /share/{codigo}` es público. El familiar sigue el viaje con los endpoints públicos de siempre
  (`/itineraries/{id}/bus-position`, `/itineraries/{id}/eta`, `/trips/{id}/timeline`).

### Puntualidad (US-182, US-193)

Tabla `trip_punctuality` (una fila por viaje): `salida_programada`, `salida_real`, `llegada_programada`,
`llegada_real`, `minutos_retraso_salida`, `minutos_retraso_llegada`, `paradas_a_tiempo`, `paradas_total`,
`conductor_id` (el `PRINCIPAL`, o quien inició el viaje), `route_id` y `company_id`. Se llena (o se
actualiza) al **finalizar el viaje** (US-171, conductor u operador) o cuando su **rastreo pasa a
`FINALIZADO`** (job de US-174). Los viajes cancelados no se registran.

**Regla de puntualidad:**

| Dato | Cómo se calcula |
|---|---|
| Salida real | La novedad `INICIO` (la primera). |
| Llegada real | La `LLEGADA_PARADA` de la última parada o, si no hay, el `FIN`. |
| Minutos de retraso | `real − programada` en minutos completos (negativo si fue antes); `null` sin dato real. |
| Parada a tiempo | Llegó con **5 min de retraso o menos** respecto a `fechaSalida + minutos_desde_salida`. La primera parada usa la salida real y la última la llegada real si no tienen `LLEGADA_PARADA`. Sin llegada registrada: no está a tiempo. |
| Viaje a tiempo (US-193) | Llegó con **10 min de retraso o menos**. |

`GET /itineraries/{id}/track` devuelve:

```json
{
  "itineraryId": "…", "origen": "Bogotá", "destino": "Tunja",
  "recorrido": [{"lat": 4.65, "lng": -74.10, "timestamp": "…"}],
  "paradas": [{"orden": 1, "nombre": "Bogotá", "lat": 4.65, "lng": -74.10, "programada": "…", "real": "…", "aTiempo": true}],
  "puntualidad": {"salidaProgramada": "…", "salidaReal": "…", "llegadaProgramada": "…", "llegadaReal": "…",
                  "minutosRetrasoSalida": 3, "minutosRetrasoLlegada": -9, "paradasATiempo": 2, "paradasTotal": 3}
}
```

El recorrido toma la primera posición de cada minuto (`DISTINCT ON (date_trunc('minute', …))`), así que
funciona igual con las posiciones resumidas de HT-08; pasados 90 días queda vacío y la puntualidad sigue
saliendo de `trip_punctuality` (si un viaje terminado por horario no tiene fila, se calcula al vuelo).

`GET /reports/punctuality` hace **una consulta agregada** sobre `trip_punctuality` (`GROUP BY route_id` o
`conductor_id`, índice `(company_id, salida_programada)`):

```json
{"desde": "2026-03-01", "hasta": "2026-03-31", "agrupar": "conductor", "companyId": "…",
 "grupos": [{"routeId": null, "conductorId": "…", "nombre": "Ana", "viajes": 2,
             "retrasoPromedioSalidaMin": 6.0, "retrasoPromedioLlegadaMin": 12.5,
             "porcentajeViajesATiempo": 50.0, "porcentajeParadasATiempo": 62.5}]}
```

Grupos ordenados por número de viajes. Promedios con un decimal (`null` si ningún viaje tiene el dato);
`porcentajeParadasATiempo` es `null` si no hay paradas. Agrupado por conductor, `nombre` sale de identity
(`DriverNameCache`, caché de 10 min; `null` si identity no lo conoce o no responde). Errores: 400
`INVALID_RANGE` (`hasta` antes de `desde`), `RANGE_TOO_LARGE` (más de 366 días), `INVALID_GROUPING`;
403 `NO_COMPANY` (operador sin empresa) y 403 para otros roles.

### Retención de posiciones (HT-08)

`PositionRetentionJob` corre todos los días a las 4:00 (`America/Bogota`, `tripbus.jobs.positions-retention-cron`)
en **un solo nodo** (`LeaderLock`):

1. Borra las posiciones de más de `tripbus.fleet.positions.keep-days` (**90**) días.
2. Las de más de `tripbus.fleet.positions.detail-days` (**7**) días las resume a **una por minuto por viaje**
   (la primera de cada minuto; las que no tienen viaje, por bus), de a una hora por vez. Hasta dónde ya se
   resumió queda en Redis (`fleet:positions:resumidas-hasta`) para no recorrer cada día lo ya resumido.

Todo por lotes de `tripbus.fleet.positions.batch-size` (5000) filas por sentencia, cada una en su propia
transacción, con el índice `bus_positions (registrado_en)`. La puntualidad no depende de las posiciones:
queda en `trip_punctuality`.

## Simulador GPS

Con `SIMULATOR_ENABLED=true` mueve cada 5 s los buses de los viajes en curso siguiendo las
paradas de su ruta y genera las novedades INICIO / LLEGADA_PARADA / FIN. Usa la misma ingesta
asíncrona que el celular del conductor, marca sus viajes como `ACTIVO` en `tracking_sessions` (así
en QA el mapa muestra el estado de rastreo igual que con un conductor real) y corre en un solo nodo
(LeaderLock en Redis). En Kubernetes es un
Deployment aparte (`fleet-gps-simulator`).

| Entorno | Simulador por defecto | Por qué |
|---|---|---|
| IDE (sin perfil) | apagado | `application.yml`: `SIMULATOR_ENABLED:false` |
| QA | **encendido** | `application-qa.yml`: para ver el seguimiento sin conductores reales |
| PROD | apagado | `application-prod.yml`: el GPS viene de los conductores. Para una demo del MVP se puede encender con `SIMULATOR_ENABLED=true` |

Al arrancar con el simulador encendido el log muestra `Simulador GPS habilitado`.

## Flujo de ramas y entornos

```
rama de trabajo --PR--> develop (QA) --PR de release--> main (PROD)
```

- `main` sigue siendo la rama por defecto en GitHub, pero **los PR de trabajo van con base `develop`**.
- `develop` es QA y `main` es PROD: a `main` solo llega código por un PR de release desde `develop`.

| Evento | Pruebas + JaCoCo + Trivy | SonarQube Cloud | Imagen publicada en GHCR |
|---|---|---|---|
| PR con base `develop` | ✔ | ✔ (quality gate) | no |
| push a `develop` (merge de un PR) | ✔ | ✔ (quality gate) | `:qa`, `:develop`, `:sha-<corto>` |
| PR con base `main` (release) | ✔ | — | no |
| push a `main` (merge del release) | ✔ | — | `:prod`, `:latest`, `:sha-<corto>` |
| `workflow_dispatch` | ✔ | solo si se lanza sobre `develop` | no |

**Por qué Sonar solo corre en `develop`:** el plan Free de SonarQube Cloud analiza una única rama,
y la rama principal del proyecto en Sonar es `develop`. Lo que llega a `main` es exactamente el
código que ya pasó el quality gate en `develop`, así que en `main` el CI hace `mvn verify` sin Sonar
y lo indica con un aviso (`::notice::`). El control de cobertura de JaCoCo (`jacoco:check`) sí corre
en todas las ramas.

**Cómo hacer un release:**

1. Asegúrate de que el último CI de `develop` esté en verde (pruebas, cobertura, quality gate y
   Trivy) y de haber probado la imagen `:qa`.
2. Abre un PR con base `main` y rama de origen `develop` (título p. ej. `release: <resumen>`).
3. Cuando el CI del PR pase, haz merge (sin squash, para que `main` y `develop` compartan historial).
   El push a `main` publica la imagen `:prod` / `:latest`.

## Entornos (IDE / QA / PROD)

| Entorno | Cómo corre | Perfil Spring | Secretos |
|---|---|---|---|
| IDE | `mvn spring-boot:run` contra los contenedores de QA | (ninguno) | valores por defecto de QA en `application.yml` |
| QA | Docker Compose en tu computador | `qa` (+ `desplegado`) | `.env` (copia de `.env.example`) |
| PROD | Docker Compose en el servidor | `prod` (+ `desplegado`) | solo en el servidor |

Puertos de este repo (el contenedor escucha en el mismo puerto que publica):

| Servicio | QA (publicado) | Dentro de Docker | PROD |
|---|---|---|---|
| fleet-trip-events-service | 18005 | 18005 | no se publica (entra por api-gateway 18000) |
| PostgreSQL `tripbus_fleet` / usuario `fleet` | 18105 | 5432 | nunca se publica |
| Redis (compartido, con contraseña) | 18100 | 6379 | nunca se publica |

Otros servicios que usa: identity (JWKS, `IDENTITY_JWKS_URI`, por defecto `http://localhost:18001/.well-known/jwks.json`;
y `GET /internal/users/{id}` para validar conductores, `IDENTITY_URL`, por defecto `http://localhost:18001`)
y catalog (paradas de la ruta, `CATALOG_URL`, por defecto `http://localhost:18002`). En `qa`/`prod` no
tienen valor por defecto y son obligatorias.

### Levantar QA

```bash
cp .env.example .env && docker compose up -d --build
docker compose ps                                   # esperar a "healthy"
curl http://localhost:18005/actuator/health
curl http://localhost:18005/actuator/info           # build + app.commit=qa-local
```

Con este compose solo responden las consultas públicas (`/buses/{id}/position`,
`/itineraries/{id}/bus-position`, `/trips/{id}/timeline`), pero sin catalog no llegan viajes
(`ItineraryPublished`): lo esperado es 404 o una lista vacía, y el simulador no tiene nada que mover.
`/driver/**`, `POST /telemetry/position` y `POST /trips/{id}/events` exigen un JWT de identity: pruébalos con el
compose general de **API-gateway**. El servicio llega a identity (18001) y catalog (18002) de tu PC
por `host.docker.internal`.

No lo corras a la vez que el compose general de **API-gateway** (usan los mismos puertos).
`docker compose down` detiene conservando los datos; `down -v` los borra.

### Imágenes Docker (GHCR)

El CI publica `ghcr.io/trip-bus/fleet-trip-events-service` solo en push (nunca desde un PR):

| Etiqueta | Rama | Uso |
|---|---|---|
| `:qa` (y `:develop`) | `develop` | Última versión de QA. |
| `:prod` (y `:latest`) | `main` | Última versión de PROD; es la que se despliega en el servidor. |
| `:sha-<corto>` | ambas | Versión inmutable de un commit concreto (para fijar o hacer rollback). |

`docker compose up --build` en tu computador construye la imagen local
`ghcr.io/trip-bus/fleet-trip-events-service:qa-local`; para usar la de QA ya publicada:
`docker pull ghcr.io/trip-bus/fleet-trip-events-service:qa`.

### Correr desde el IDE contra QA

Con el compose de QA arriba, `docker compose stop fleet-trip-events-service` (libera el 18005) y luego
`mvn spring-boot:run` sin perfil: `application.yml` ya apunta a `localhost:18105` (BD) y
`localhost:18100` (Redis). Para Redis define `REDIS_PASSWORD=tripbus_redis_qa_pw` (el compose de QA
lo exige con contraseña).

### Variables

En `qa` y `prod` se activa el perfil `desplegado`: si falta alguna variable obligatoria el servicio
no arranca y dice cuáles faltan (`RequiredSecretsCheck`).

| Variable | Obligatoria en qa/prod | Por defecto (IDE) |
|---|---|---|
| `DB_PASSWORD` | ✔ | `fleet_qa_pw` |
| `REDIS_PASSWORD` | ✔ | (vacía) |
| `INTERNAL_API_TOKEN` | ✔ | `tripbus_internal_qa_token` |
| `IDENTITY_JWKS_URI` | ✔ | `http://localhost:18001/.well-known/jwks.json` |
| `IDENTITY_URL` | ✔ | `http://localhost:18001` |
| `CATALOG_URL` | ✔ | `http://localhost:18002` |

Opcionales con valor por defecto: `SERVER_PORT` (18005), `DB_HOST` (localhost), `DB_PORT` (18105),
`DB_NAME` (tripbus_fleet), `DB_USERNAME` (fleet), `DB_POOL_SIZE`, `REDIS_HOST` (localhost),
`REDIS_PORT` (18100), `SIMULATOR_ENABLED` (IDE `false`, QA `true`, PROD `false`), `TELEMETRY_STREAM`,
`LOG_FORMAT` (`text`; en PROD `json`), `OTEL_EXPORTER_OTLP_ENDPOINT` (vacía: no se exportan trazas),
`TRACING_SAMPLING` (`0.1`) y `OPENAPI_SERVER_URL` (vacía). Ver [Observabilidad](#observabilidad).
`DB_HOST` reemplaza a la antigua `DB_URL`.

## Swagger

Solo en IDE y QA (en `prod` springdoc está apagado y responde 404):

- UI: http://localhost:18005/swagger-ui.html
- OpenAPI JSON: http://localhost:18005/v3/api-docs
- Con `OPENAPI_SERVER_URL` (p. ej. la URL del API Gateway) el documento anuncia ese servidor y "Try it out"
  pasa por él; vacía (por defecto), springdoc usa la URL del propio servicio.

La posición del bus y la línea de tiempo son públicas. Para `/driver/**` (CONDUCTOR),
`/telemetry/position` (ADMIN) y `POST /trips/{id}/events` haz login en identity (`/auth/login`) con el
usuario del rol que corresponda y pega el `accessToken` en **Authorize**.

## Observabilidad

- `/actuator/health` (con `liveness` y `readiness`), `/actuator/prometheus`.
- `/actuator/info`: versión y fecha del build, y `app.commit` (el commit con el que se construyó la imagen).
- Cada petición lleva un `requestId`: se reutiliza el header `X-Request-Id` (lo manda el API Gateway)
  o se genera uno, y se devuelve en la respuesta. Fleet lo **propaga** en sus llamadas salientes
  (`CatalogClient` e `IdentityClient`), así una petición se sigue entre servicios.

**Trazas (HT-10):** Micrometer Tracing con el puente de OpenTelemetry (`micrometer-tracing-bridge-otel` y
`opentelemetry-exporter-otlp`, versiones de Spring Boot). Se muestrea `TRACING_SAMPLING` (10 % por defecto)
y se exportan por OTLP/HTTP a `${OTEL_EXPORTER_OTLP_ENDPOINT}/v1/traces` solo si la variable tiene valor
(vacía = no se exporta; `ObservabilityEnvironment`). El contexto W3C (`traceparent`) se propaga:

- en las llamadas HTTP a catalog e identity (los `RestClient` salen del `RestClient.Builder` de Boot, con
  observación);
- en el stream de telemetría: cada posición encolada lleva `traceparent` y el consumidor la procesa como
  hijo de esa traza (`TracePropagation`);
- en los eventos del outbox: la columna `outbox.trace_parent` guarda la traza que generó el evento, el relay
  la publica en el campo `traceparent` del stream y `EventConsumer` maneja el evento dentro de esa traza.

**Logs:** texto legible por defecto, con `[traceId,spanId] [requestId] [itineraryId]`. Con `LOG_FORMAT=json`
(por defecto en PROD) se usa el logging estructurado de Spring Boot en formato **ECS**
(`logging.structured.format.console=ecs`): una línea JSON por evento con todo el MDC (`traceId`, `spanId`,
`requestId` e `itineraryId` cuando existe). El `itineraryId` lo ponen las rutas `/itineraries/{id}`,
`/trips/{id}` y `{itineraryId}` (`ItineraryMdcInterceptor`) y los consumidores de telemetría y eventos.
**Nunca** se escriben coordenadas junto al id del conductor en el mismo log (ni el `conductorId` va al MDC).

**Métricas** (en `/actuator/prometheus`):

| Métrica | Tipo | Etiquetas | Qué cuenta |
|---|---|---|---|
| `tripbus_posiciones_total` | Counter | `resultado=aceptada\|descartada`, `motivo` | Posiciones recibidas (del conductor y de `/telemetry/position`). Las aceptadas llevan `motivo="ninguno"`; las descartadas el motivo de US-173 (`PRECISION_BAJA`, `SALTO_IMPOSIBLE`…) o el código de error (`STALE_POSITION`, `TELEMETRY_UNAVAILABLE`…). |
| `tripbus_sesiones_rastreo` | Gauge | `estado` | Sesiones de rastreo en cada estado (una consulta agrupada, reutilizada 15 s). |
| `tripbus_alertas_total` | Counter | `tipo`, `gravedad` | Alertas creadas para el operador (novedades graves, SOS y desvíos). |
| `tripbus_procesar_posicion_segundos` | Timer | — | Tiempo de procesar cada posición del stream de telemetría (los Timer `tripbus.*` salen con sufijo `_segundos`, `MetricsConfig`). |

## Pruebas

```bash
mvn -B verify      # unitarias + integración con PostgreSQL y Redis reales (Testcontainers: Docker abierto)
```

- `*Test` / `*Tests`: unitarias (simulador GPS, `RequiredSecretsCheck`, `RequestIdFilter`,
  `RequestIdPropagationInterceptor`, `IdentityClient` con `MockRestServiceServer`, cálculo de estado).
- `FleetIT`: levanta PostgreSQL 16 y Redis 7 con Testcontainers, corre las migraciones Flyway reales y
  prueba la ingesta asíncrona de posiciones (stream de telemetría, idempotencia), el transactional
  outbox hacia el bus de eventos y las novedades del viaje por empresa. Ya no necesita `TRIPBUS_IT`
  ni servicios levantados a mano.
- `DriverIT`: asignación de conductores y mis viajes de hoy con PostgreSQL y Redis reales e identity
  simulado con WireMock (validaciones, cruces, cancelación, eventos, y 20 hilos asignando al mismo
  conductor en viajes cruzados: solo queda 1).
- `DriverTrackingIT`: ubicación del conductor y estados de rastreo con PostgreSQL y Redis reales
  (conductor no asignado, fuera de ventana, pausa, lote desordenado, precisión mala, salto imposible,
  paso a `SIN_SENAL` y a `FINALIZADO` con el job, y que `PositionUpdated` no lleve `conductorId`).
- `TripOperationIT`: sincronización con catalog (rutas, reprogramación con conflicto de conductor,
  cambio de bus), inicio y fin del viaje (checklist incompleto, doble inicio, fin forzado) y alertas
  (novedades graves, SOS repetido y fuera de la línea de tiempo, alerta de otra empresa: 404).
- `TripAnalysisIT`: detección automática y ETA con una secuencia de posiciones por Bogotá → Chocontá →
  Tunja (llegada a cada parada, retraso que se repite al crecer 15 min, desvío con una sola alerta, ETA y
  límite de `EtaUpdated`), sin duplicados con el simulador. `GeoTest` y `EtaCalculatorTest`: geometría y
  cálculo del ETA con instantes fijos.
- `FleetLiveIT`: mapa de la flota con viajes en varios estados (fuera de la ventana y cancelado no
  aparecen), operador que no ve otra empresa, ADMIN con y sin `companyId`, identity caído (nombre
  `null`) y `companyId` en `PositionUpdated` y `EtaUpdated`. `FleetLiveServiceTest` y
  `DriverNameCacheTest`: resumen, Redis caído y caché de nombres.
- `ObservabilidadIT` (con tracing, métricas y los consumidores de los streams encendidos): `traceparent` en
  la posición encolada, en el evento del outbox y en las llamadas a identity; métricas propias en
  `/actuator/prometheus`; y `UserDeleted` que saca al conductor de sus viajes pendientes (sin tocar los
  terminados). `TracePropagationTest`, `FleetMetricsTest`, `TrackingSessionGaugesTest`,
  `ObservabilityEnvironmentTest`, `ItineraryMdcInterceptorTest` y `OpenApiConfigTest`: unitarias de HT-10.
- `HistorialViajeIT`: enlace creado, vencido (Awaitility), por encima del límite y con ETA; puntualidad
  calculada al finalizar y al pasar a `FINALIZADO`; recorrido de un viaje no terminado o cancelado (404);
  reporte por ruta y por conductor (con nombre de identity), otra empresa y ADMIN; y resumen y borrado de
  posiciones con `/itineraries/{id}/track` funcionando después. `ShareLinkServiceTest`: códigos sin
  ambigüedad, choques y Redis caído.

Toda la plataforma: ver el README de **API-gateway**.

## Calidad

| Herramienta | Qué revisa | Dónde |
|---|---|---|
| JaCoCo | Cobertura de las pruebas y **mínimo obligatorio** (`jacoco:check`) | local: `target/site/jacoco/index.html` (tras `mvn verify`); CI: tabla en el resumen de cada ejecución y artifact `jacoco` |
| SonarQube Cloud | Bugs, code smells y cobertura; quality gate "Sonar way" (código nuevo ≥ 80 %) | proyecto `Trip-Bus_fleet-trip-events-service` de la organización `trip-bus` |
| Gitleaks | Secretos en todo el historial git | job `secretos` del CI |
| Trivy | Vulnerabilidades CRITICAL/HIGH con corrección disponible en la imagen Docker | job `docker` del CI |

### Cobertura mínima (JaCoCo)

`mvn verify` falla si la cobertura total baja de los mínimos del `pom.xml`, en **todas** las ramas
(también en `main`, donde Sonar no corre):

| Métrica | Medido (2026-11-04) | Mínimo |
|---|---|---|
| Líneas | 95,8 % | `jacoco.minimo.lineas` = 0.95 |
| Ramas | 90,1 % | `jacoco.minimo.ramas` = 0.90 |

**La cobertura nunca puede bajar: si agregas pruebas, sube el mínimo.** Corre `mvn verify`, mira
el total en `target/site/jacoco/index.html` (o la tabla del CI), redondéalo hacia abajo al 5 % y
actualiza la propiedad en el pom en el mismo PR. Las mejores candidatas para subir ramas son
`EventConsumer`, `GpsSimulator` (el `tick`/`step` con viajes en curso), `TelemetryConsumer` y `CurrentUser`.

Fuera de la cobertura (mismas en `sonar.coverage.exclusions` y en los `<excludes>` de JaCoCo) solo
está lo que no tiene lógica: `FleetApplication`, los records sin métodos de `web/dto`, las entidades
JPA de solo constructor/getters (`BusPosition`, `OutboxMessage`, `TripEvent`, `ProcessedEvent`) y
`config/OpenApiConfig`. `FleetItinerary`, `RouteStop` y `FleetProperties` tienen lógica y **sí** cuentan.

### Vulnerabilidades aceptadas (`.trivyignore.yaml`)

Lista **temporal** de CVE que Trivy no debe tratar como error. Solo entran las que **no tienen
arreglo en Spring Boot 3.x** (hoy CVE-2026-47884 y CVE-2026-47890 de `spring-webmvc`, que solo se
corrigen en Spring Framework 7; fleet no usa XsltView ni SSE); cada una explica en `statement` por
qué se acepta. Todas **vencen el 2027-01-31** (`expired_at`): desde esa fecha Trivy vuelve a fallar
si no se migró a Spring Boot 4.1. Lo que sí tiene parche nunca se agrega aquí: se corrige subiendo la
versión (ver las propiedades `*.version` de parches de seguridad en `pom.xml`).

Los paquetes de Alpine de la imagen se actualizan con `apk upgrade` en el `Dockerfile`. Como el CI construye
con caché de capas (`type=gha`), esa capa se invalida en cada ejecución con el build-arg `APK_REFRESH`
(`github.run_id`): así cada build trae los parches de Alpine publicados después de la última caché (p. ej.
libexpat 2.10.0) y Trivy no falla por paquetes que ya tienen arreglo.

El análisis de Sonar solo corre en `develop` (push y PR con base `develop`, ver
[Flujo de ramas y entornos](#flujo-de-ramas-y-entornos)) y si el repo tiene el secreto `SONAR_TOKEN`;
en otro caso el CI hace `mvn verify` y deja un aviso.

### Secretos del CI (`SONAR_TOKEN`, `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`)

| Secreto | Valor |
|---|---|
| `SONAR_TOKEN` | Token de SonarQube Cloud con acceso al proyecto `Trip-Bus_fleet-trip-events-service` |
| `DOCKERHUB_USERNAME` | Usuario de Docker Hub |
| `DOCKERHUB_TOKEN` | Personal access token con permiso **Public Repo Read-only** (solo lectura; nunca la contraseña) |

Los runners de GitHub comparten IP y agotan el límite de descargas anónimas de Docker Hub
(`toomanyrequests: You have reached your unauthenticated pull rate limit`), lo que rompe
Testcontainers (`postgres:16-alpine`, Redis) y el build de la imagen (`maven`, `eclipse-temurin`).
Con los secretos de Docker Hub los jobs `secretos`, `build-test` y `docker` inician sesión antes de
descargar nada y usan el límite de una cuenta autenticada. Son **opcionales pero recomendados**: si
faltan, el CI descarga sin autenticar, deja un aviso y puede fallar por el límite. Gitleaks se
descarga desde GHCR (`ghcr.io/gitleaks/gitleaks`), que no tiene ese límite. Todos se crean en
Settings → Secrets and variables → Actions.

Escaneos locales con Docker:

```bash
docker run --rm -v "$PWD:/repo" ghcr.io/gitleaks/gitleaks:v8.30.1 git /repo --redact
docker build -t fleet-trip-events-service:local . && \
  docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$PWD:/src" aquasec/trivy:0.70.0 \
  image --severity CRITICAL,HIGH --ignore-unfixed --ignorefile /src/.trivyignore.yaml fleet-trip-events-service:local
```
