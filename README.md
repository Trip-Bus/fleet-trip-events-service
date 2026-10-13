# Fleet + Trip Events Service

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=Trip-Bus_fleet-trip-events-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=Trip-Bus_fleet-trip-events-service)

Todo lo que ocurre durante el viaje: posiciones GPS continuas y novedades (inicio, llegada a
parada, retraso, desvío, fin). Incluye el **simulador GPS** del MVP.

**Stack:** Java 21 · Spring Boot 3.5.16 · PostgreSQL 16 · Redis 7

**HU:** US-038, US-039, US-046, US-047 · **Puerto:** 18005 · **BD:** `tripbus_fleet` (puerto QA 18105)

## API

| Método | Endpoint | Descripción |
|---|---|---|
| POST | `/telemetry/position` | Ingesta GPS (CONDUCTOR/OPERADOR/ADMIN). Responde 202: se encola en `tripbus:telemetry` y un consumidor independiente la procesa. |
| GET | `/buses/{id}/position` | Última posición conocida (Redis). Pública, solo si el bus tiene un viaje en ventana activa; si no, 404 `NO_ACTIVE_TRIP`. |
| GET | `/itineraries/{id}/bus-position` | Dónde va mi bus: estado + paradas para el mapa y la posición (en `null` fuera de la ventana activa o si está cancelado). Pública. |
| POST | `/trips/{id}/events` `{tipo, descripcion, detalle}` | Novedad del viaje (conductor/operador **de la misma empresa**, o ADMIN). |
| GET | `/trips/{id}/timeline` | Línea de tiempo de novedades. Pública. |

**Posición solo durante el viaje (US-039, US-047):** la ventana activa de un itinerario va de
`fechaSalida - 30 min` a `fechaLlegada + 30 min` (`tripbus.fleet.tracking-window-before` / `-after`)
y nunca aplica si está cancelado. Fuera de ella la posición se guarda en `bus_positions` (auditoría)
pero no se expone ni se publica `PositionUpdated`. Estado: `CANCELADO` > `FINALIZADO` (después de
`fechaLlegada`) > `PROGRAMADO` > `SIN_SENAL` > `EN_RUTA`.

**Transactional outbox**: `PositionUpdated` y `TripEventReported` se guardan en la tabla `outbox`
en la misma transacción y un *relay* los publica (`FOR UPDATE SKIP LOCKED`, seguro con varias réplicas).

**Consume:** `ItineraryPublished` (bus ↔ itinerario), `ItineraryCancelled`.

## Simulador GPS

Con `SIMULATOR_ENABLED=true` mueve cada 5 s los buses de los viajes en curso siguiendo las
paradas de su ruta y genera las novedades INICIO / LLEGADA_PARADA / FIN. Usa la misma ruta de
ingesta que un GPS real y corre en un solo nodo (LeaderLock en Redis). En Kubernetes es un
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

Otros servicios que usa: identity (JWKS, `IDENTITY_JWKS_URI`, por defecto `http://localhost:18001/.well-known/jwks.json`)
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
`POST /telemetry/position` y `POST /trips/{id}/events` exigen un JWT de identity: pruébalos con el
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
| `CATALOG_URL` | ✔ | `http://localhost:18002` |

Opcionales con valor por defecto: `SERVER_PORT` (18005), `DB_HOST` (localhost), `DB_PORT` (18105),
`DB_NAME` (tripbus_fleet), `DB_USERNAME` (fleet), `DB_POOL_SIZE`, `REDIS_HOST` (localhost),
`REDIS_PORT` (18100), `SIMULATOR_ENABLED` (IDE `false`, QA `true`, PROD `false`), `TELEMETRY_STREAM`.
`DB_HOST` reemplaza a la antigua `DB_URL`.

## Swagger

Solo en IDE y QA (en `prod` springdoc está apagado y responde 404):

- UI: http://localhost:18005/swagger-ui.html
- OpenAPI JSON: http://localhost:18005/v3/api-docs

La posición del bus y la línea de tiempo son públicas. Para `/telemetry/position` y
`POST /trips/{id}/events` haz login en identity (`/auth/login`) con un usuario CONDUCTOR, OPERADOR o
ADMIN y pega el `accessToken` en **Authorize**.

## Observabilidad

- `/actuator/health` (con `liveness` y `readiness`), `/actuator/prometheus`.
- `/actuator/info`: versión y fecha del build, y `app.commit` (el commit con el que se construyó la imagen).
- Cada petición lleva un `requestId`: se reutiliza el header `X-Request-Id` (lo manda el API Gateway)
  o se genera uno, y se devuelve en la respuesta. Fleet lo **propaga** en sus llamadas salientes
  (`CatalogClient`, paradas de la ruta), así una petición se sigue entre servicios. Logs legibles con
  `[requestId]` en IDE/QA y JSON (con `requestId` y `"service":"fleet-trip-events-service"`) en PROD.

## Pruebas

```bash
mvn -B verify      # unitarias + integración con PostgreSQL y Redis reales (Testcontainers: Docker abierto)
```

- `*Test` / `*Tests`: unitarias (simulador GPS, `RequiredSecretsCheck`, `RequestIdFilter`,
  `RequestIdPropagationInterceptor`).
- `FleetIT`: levanta PostgreSQL 16 y Redis 7 con Testcontainers, corre las migraciones Flyway reales y
  prueba la ingesta asíncrona de posiciones (stream de telemetría, idempotencia), el transactional
  outbox hacia el bus de eventos y las novedades del viaje por empresa. Ya no necesita `TRIPBUS_IT`
  ni servicios levantados a mano.

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

| Métrica | Medido (2026-10-09) | Mínimo |
|---|---|---|
| Líneas | 60 % | `jacoco.minimo.lineas` = 0.60 |
| Ramas | 44 % | `jacoco.minimo.ramas` = 0.40 |

**La cobertura nunca puede bajar: si agregas pruebas, sube el mínimo.** Corre `mvn verify`, mira
el total en `target/site/jacoco/index.html` (o la tabla del CI), redondéalo hacia abajo al 5 % y
actualiza la propiedad en el pom en el mismo PR. Ramas está por debajo del 60 % de referencia:
las mejores candidatas para subirla son `EventConsumer`, `GpsSimulator` (el `tick`/`step` con viajes
en curso), `TelemetryConsumer` y `CurrentUser`.

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
