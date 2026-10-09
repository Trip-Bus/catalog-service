# Catalog Service

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=Trip-Bus_catalog-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=Trip-Bus_catalog-service)

Catálogo de viajes de TripBus: rutas, paradas, itinerarios, buses, mapas de asientos y precios.
Responde las búsquedas del pasajero y es dueño de la carga inicial de datos (seed).

**Stack:** Java 21 · Spring Boot 3.5.16 · PostgreSQL 16 · Redis 7

**HU:** HT-01, US-007, US-008, US-014 · **Puerto:** 18002 · **BD:** `tripbus_catalog` (puerto QA 18102)

## API

| Método | Endpoint | Descripción |
|---|---|---|
| GET | `/trips/search?origin=&destination=&date=YYYY-MM-DD` | Búsqueda (sin distinguir tildes), ordenada por salida, con asientos disponibles (consultados a Seat Inventory) y oferta activa. |
| GET | `/trips/cities` | Ciudades disponibles. |
| GET | `/itineraries/{id}` | Detalle con paradas y hora programada. |
| GET | `/itineraries/{id}/seatmap` | Distribución real del bus (pisos, pasillo, baño, numeración). |
| GET | `/itineraries/{id}/price` | Precio base + oferta activa. |
| GET | `/routes/{id}/stops` | Paradas de una ruta. |
| POST | `/admin/seed` | Seed idempotente (ADMIN). También corre al arrancar y cada madrugada. |
| POST | `/admin/itineraries/{id}/cancel` | Cancela un viaje (ADMIN/OPERADOR) → `ItineraryCancelled`. |

**Publica:** `ItineraryPublished`, `ItineraryCancelled`. **Consume:** `OfferActivated`, `OfferClosed`, `OfferSoldOut`, `UserLoggedIn`.

Cache de búsquedas en Redis (TTL 45 s). Si Seat Inventory no responde, la búsqueda sigue funcionando sin el dato de disponibilidad.

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
| catalog-service | 18002 | 18002 | no se publica (entra por api-gateway 18000) |
| PostgreSQL `tripbus_catalog` / usuario `catalog` | 18102 | 5432 | nunca se publica |
| Redis (compartido, con contraseña) | 18100 | 6379 | nunca se publica |

Otros servicios que usa: identity (JWKS, `IDENTITY_JWKS_URI`, por defecto `http://localhost:18001/.well-known/jwks.json`)
y seat-inventory (`SEAT_INVENTORY_URL`, por defecto `http://localhost:18003`). En `qa`/`prod` no tienen
valor por defecto y son obligatorias.

### Levantar QA

```bash
cp .env.example .env && docker compose up -d --build
docker compose ps                                   # esperar a "healthy"
curl http://localhost:18002/actuator/health
curl "http://localhost:18002/trips/search?origin=Bogotá&destination=Medellín&date=$(date -d tomorrow +%F)"
```

Con este compose solo funcionan `/trips/search` y `/trips/cities` (públicos; sin seat-inventory la
búsqueda responde sin el dato de asientos). `/admin/**` exige un JWT de identity: pruébalo con el
compose general de **API-gateway**. El servicio llega a identity (18001) y seat-inventory (18003) de
tu PC por `host.docker.internal`.

No lo corras a la vez que el compose general de **API-gateway** (usan los mismos puertos).
`docker compose down` detiene conservando los datos; `down -v` los borra.

### Imágenes Docker (GHCR)

El CI publica `ghcr.io/trip-bus/catalog-service` solo en push (nunca desde un PR):

| Etiqueta | Rama | Uso |
|---|---|---|
| `:qa` (y `:develop`) | `develop` | Última versión de QA. |
| `:prod` (y `:latest`) | `main` | Última versión de PROD; es la que se despliega en el servidor. |
| `:sha-<corto>` | ambas | Versión inmutable de un commit concreto (para fijar o hacer rollback). |

`docker compose up --build` en tu computador construye la imagen local `ghcr.io/trip-bus/catalog-service:qa-local`;
para usar la de QA ya publicada: `docker pull ghcr.io/trip-bus/catalog-service:qa`.

### Correr desde el IDE contra QA

Con el compose de QA arriba, `docker compose stop catalog-service` (libera el 18002) y luego
`mvn spring-boot:run` sin perfil: `application.yml` ya apunta a `localhost:18102` (BD) y
`localhost:18100` (Redis). Para Redis define `REDIS_PASSWORD=tripbus_redis_qa_pw` (el compose de QA
lo exige con contraseña).

### Variables

En `qa` y `prod` se activa el perfil `desplegado`: si falta alguna variable obligatoria el servicio
no arranca y dice cuáles faltan (`RequiredSecretsCheck`).

| Variable | Obligatoria en qa/prod | Por defecto (IDE) |
|---|---|---|
| `DB_PASSWORD` | ✔ | `catalog_qa_pw` |
| `REDIS_PASSWORD` | ✔ | (vacía) |
| `INTERNAL_API_TOKEN` | ✔ | `tripbus_internal_qa_token` |
| `IDENTITY_JWKS_URI` | ✔ | `http://localhost:18001/.well-known/jwks.json` |
| `SEAT_INVENTORY_URL` | ✔ | `http://localhost:18003` |

Opcionales con valor por defecto: `SERVER_PORT` (18002), `DB_HOST` (localhost), `DB_PORT` (18102),
`DB_NAME` (tripbus_catalog), `DB_USERNAME` (catalog), `DB_POOL_SIZE`, `REDIS_HOST` (localhost),
`REDIS_PORT` (18100), `SEED_ON_STARTUP` (true). `DB_HOST` reemplaza a la antigua `DB_URL`.

El seed queda encendido también en PROD: en el MVP no hay pantalla para crear rutas y el seed es
idempotente (ver `application-prod.yml`).

## Swagger

Solo en IDE y QA (en `prod` springdoc está apagado y responde 404):

- UI: http://localhost:18002/swagger-ui.html
- OpenAPI JSON: http://localhost:18002/v3/api-docs

La búsqueda y la consulta son públicas. Para `/admin/**` haz login en identity (`/auth/login`) y pega
el `accessToken` en **Authorize**.

## Observabilidad

- `/actuator/health` (con `liveness` y `readiness`), `/actuator/prometheus`.
- `/actuator/info`: versión y fecha del build, y `app.commit` (el commit con el que se construyó la imagen).
- Cada petición lleva un `requestId`: se reutiliza el header `X-Request-Id` (lo manda el API Gateway)
  o se genera uno, y se devuelve en la respuesta. Catalog lo **propaga** en sus llamadas salientes
  (p. ej. a seat-inventory), así una petición se sigue entre servicios. Logs legibles con
  `[requestId]` en IDE/QA y JSON (con `requestId` y `"service":"catalog-service"`) en PROD.

## Pruebas

```bash
mvn -B verify      # unitarias + integración con PostgreSQL y Redis reales (Testcontainers: Docker abierto)
```

- `*Test` / `*Tests`: unitarias.
- `CatalogIT`: levanta PostgreSQL 16 y Redis 7 con Testcontainers, corre las migraciones Flyway
  reales, ejecuta el seed y prueba búsqueda, mapa de asientos, ofertas y `/admin/seed`.

Toda la plataforma: ver el README de **API-gateway**.

## Calidad

| Herramienta | Qué revisa | Dónde |
|---|---|---|
| JaCoCo | Cobertura de las pruebas y **mínimo obligatorio** (`jacoco:check`) | local: `target/site/jacoco/index.html` (tras `mvn verify`); CI: tabla en el resumen de cada ejecución y artifact `jacoco` |
| SonarQube Cloud | Bugs, code smells y cobertura; quality gate "Sonar way" (código nuevo ≥ 80 %) | proyecto `Trip-Bus_catalog-service` de la organización `trip-bus` |
| Gitleaks | Secretos en todo el historial git | job `secretos` del CI |
| Trivy | Vulnerabilidades CRITICAL/HIGH con corrección disponible en la imagen Docker | job `docker` del CI |

### Cobertura mínima (JaCoCo)

`mvn verify` falla si la cobertura total baja de los mínimos del `pom.xml`, en **todas** las ramas
(también en `main`, donde Sonar no corre):

| Métrica | Medido (2026-10-09) | Mínimo |
|---|---|---|
| Líneas | 71 % | `jacoco.minimo.lineas` = 0.70 |
| Ramas | 55 % | `jacoco.minimo.ramas` = 0.55 |

**La cobertura nunca puede bajar: si agregas pruebas, sube el mínimo.** Corre `mvn verify`, mira
el total en `target/site/jacoco/index.html` (o la tabla del CI), redondéalo hacia abajo al 5 % y
actualiza la propiedad en el pom en el mismo PR. Ramas está por debajo del 60 % de referencia:
las mejores candidatas para subirla son `EventConsumer`, `CurrentUser` y `TripSearchResult`.

Fuera de la cobertura (mismas en `sonar.coverage.exclusions` y en los `<excludes>` de JaCoCo) solo
está lo que no tiene lógica: `CatalogApplication`, los DTO sin métodos de `web/dto`, las entidades
JPA de solo getters (`Bus`, `Company`, `Route`, `SeatMap`, `Stop`, `ProcessedEvent`) y
`config/OpenApiConfig`. `TripSearchResult` e `Itinerary` tienen lógica y **sí** cuentan.

### Vulnerabilidades aceptadas (`.trivyignore.yaml`)

Lista **temporal** de CVE que Trivy no debe tratar como error. Solo entran las que **no tienen
arreglo en Spring Boot 3.x** (hoy CVE-2026-47884 y CVE-2026-47890 de `spring-webmvc`, que solo se
corrigen en Spring Framework 7); cada una explica en `statement` por qué se acepta. Todas
**vencen el 2027-01-31** (`expired_at`): desde esa fecha Trivy vuelve a fallar si no se migró a
Spring Boot 4.1. Lo que sí tiene parche nunca se agrega aquí: se corrige subiendo la versión
(ver las propiedades `*.version` de parches de seguridad en `pom.xml`).

El análisis de Sonar solo corre en `develop` (push y PR con base `develop`, ver
[Flujo de ramas y entornos](#flujo-de-ramas-y-entornos)) y si el repo tiene el secreto `SONAR_TOKEN`;
en otro caso el CI hace `mvn verify` y deja un aviso. Escaneos locales con Docker:

```bash
docker run --rm -v "$PWD:/repo" zricethezav/gitleaks:v8.30.1 git /repo --redact
docker build -t catalog-service:local . && \
  docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$PWD:/src" aquasec/trivy:0.70.0 \
  image --severity CRITICAL,HIGH --ignore-unfixed --ignorefile /src/.trivyignore.yaml catalog-service:local
```
