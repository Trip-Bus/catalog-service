# Catalog Service

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=Trip-Bus_catalog-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=Trip-Bus_catalog-service)

Catálogo de viajes de TripBus: rutas, paradas, itinerarios, buses, mapas de asientos y precios.
Responde las búsquedas del pasajero y es dueño de la carga inicial de datos (seed).

**Stack:** Java 21 · Spring Boot 3.5.16 · PostgreSQL 16 · Redis 7

**HU:** HT-01, US-007, US-008, US-014, US-039, US-047, US-100, US-110, US-111, US-114, US-116, US-117, US-118, US-119 · **Puerto:** 18002 · **BD:** `tripbus_catalog` (puerto QA 18102)

## API

| Método | Endpoint | Descripción |
|---|---|---|
| GET | `/trips/search?origin=&destination=&date=YYYY-MM-DD&companyId=&franja=&precioMin=&precioMax=&tipoBus=&sort=&order=&page=&size=` | Búsqueda pública. `franja`: `MANANA` (05:00–11:59), `TARDE` (12:00–17:59) o `NOCHE` (18:00–04:59), hora de Bogotá. Precio filtra el precio del pasajero (oferta activa si existe). `sort`: `salida` (default), `precio` o `duracion`; `order`: `asc` (default) o `desc`; `page`=0, `size`=50 (máximo 100). Sigue respondiendo lista; `X-Total-Count` da el total filtrado y está expuesto por CORS. Sin parámetros nuevos conserva el comportamiento actual. |
| GET | `/trips/search/companies?origin=&destination=&date=YYYY-MM-DD` | Empresas con viajes disponibles para esa búsqueda (filtro de empresa). |
| GET | `/trips/cities` | Ciudades disponibles. |
| GET | `/trips/in-progress?origin=&destination=` | Viajes en ruta (público, US-039/US-047): para abrir el seguimiento del bus sin tiquete. Al menos uno de `origin`/`destination` (si no, 400 `INVALID_SEARCH`), sin distinguir tildes. Itinerarios no cancelados con `fechaSalida - 30 min <= ahora <= fechaLlegadaEstimada + 30 min` (`tripbus.catalog.in-progress-before`/`-after`, mismo criterio que la ventana de fleet), ordenados por salida, máximo 50. Devuelve `itineraryId`, `busId`, `empresa`, `origen`, `destino`, `fechaSalida`, `fechaLlegadaEstimada`; sin precio, asientos ni oferta, y sin cache. |
| GET | `/itineraries/{id}` | Detalle con paradas y hora programada. |
| GET | `/itineraries/{id}/seatmap` | Distribución real del bus (pisos, pasillo, baño, numeración). |
| GET | `/itineraries/{id}/price` | Precio base + oferta activa. |
| GET | `/routes/{id}/stops` | Paradas de una ruta (público, lo usa fleet). |
| POST | `/admin/seed` | Seed idempotente (ADMIN). También corre al arrancar y cada madrugada. |
| POST | `/admin/itineraries/{id}/cancel` | Cancela un viaje (ADMIN/OPERADOR). Body `{motivo}`. El OPERADOR solo cancela viajes de su empresa (otra empresa → 404 `ITINERARY_NOT_FOUND`). 409 `ITINERARY_ALREADY_CANCELLED` o `ITINERARY_FINISHED` (ya llegó). Guarda el historial (`CANCELACION`) → `ItineraryCancelled`. |
| POST | `/companies` | Crea una empresa (ADMIN, US-100). Body `{nombre, nit}`: nombre obligatorio, NIT de 9-10 dígitos con dígito de verificación opcional (`900123456-7`). NIT único sin importar el dígito de verificación (409 `NIT_ALREADY_EXISTS`). Queda `ACTIVA` → `CompanyCreated`. |
| GET | `/companies?estado=&page=&size=` | Empresas paginadas (ADMIN), filtro opcional `ACTIVA`/`INACTIVA`. `page` desde 0, `size` 1-100 (20 por defecto). Respuesta `{content, page, size, totalElements, totalPages}`. |
| GET | `/companies/{id}` | Detalle (ADMIN): `id`, `nombre`, `nit`, `estado`, `creadoEn`, `actualizadoEn`. 404 `COMPANY_NOT_FOUND`. |
| PUT | `/companies/{id}` | Edita nombre y NIT (ADMIN) → `CompanyUpdated`. |
| PATCH | `/companies/{id}/estado` | Body `{estado: ACTIVA\|INACTIVA}` (ADMIN). `INACTIVA` → `CompanyDeactivated`: sus viajes dejan de salir en `/trips/search` y `/trips/in-progress` y no puede crear buses (409 `COMPANY_INACTIVE`). Reactivar → `CompanyUpdated`. Invalida la cache de búsquedas. |
| POST | `/companies/{id}/terms` | ADMIN agrega una vigencia inmutable: `{cuotaMensual, comisionPorcentaje, vigenteDesde}`. La fecha no puede estar en el pasado y la comisión debe estar entre 0 y 30 %. Publica `CompanyTermsChanged`. |
| GET | `/companies/{id}/terms` | Historial para ADMIN o el OPERADOR de esa empresa, ordenado por vigencia descendente. |
| GET | `/internal/companies/{id}?fecha=YYYY-MM-DD` | Servicio a servicio (`X-Internal-Token`): `{id, nombre, estado, comisionPorcentaje, cuotaMensual}`. `fecha` opcional (hoy por defecto); Booking consulta las condiciones vigentes para registrar la comisión de la venta. |
| GET | `/operator/trips?desde=&hasta=&companyId=&page=&size=` | OPERADOR (empresa del token) o ADMIN (requiere `companyId`): viajes ordenados/paginados con ruta, salida, bus, estado, precio, oferta y ocupación de Seat Inventory. Fechas inclusivas en hora de Bogotá; rango predeterminado hoy a 7 días, máximo 31. Si Inventory falla, `ocupacion` es null y `ocupacionConocida` false. `resumen` agrega viajes, ocupación promedio conocida y candidatos a oferta (<40 % a 24 h). |
| POST | `/buses` | Crea un bus (ADMIN/OPERADOR, US-110). Body `{placa, tipo, companyId?, distribucion?}`: placa `ABC123` única (409 `PLACA_ALREADY_EXISTS`), `tipo` `UN_PISO`/`DOS_PISOS`. El OPERADOR crea en la empresa de su token; el ADMIN debe enviar `companyId` (400 `COMPANY_REQUIRED`). Sin `distribucion` usa el mapa por defecto del tipo. Queda `ACTIVO`. |
| GET | `/buses?estado=&tipo=&companyId=&page=&size=` | Buses paginados (ADMIN/OPERADOR). El OPERADOR solo ve los de su empresa; `companyId` solo aplica al ADMIN. |
| GET | `/buses/{id}` | Detalle: `id`, `companyId`, `placa`, `tipo`, `capacidad`, `estado`, `creadoEn`, `actualizadoEn`. Un bus de otra empresa es 404 `BUS_NOT_FOUND` para el OPERADOR. |
| PUT | `/buses/{id}` | Edita placa y tipo. Cambiar el tipo reinicia el mapa al de por defecto del nuevo tipo (409 `BUS_IN_USE` si tiene viajes futuros no cancelados). |
| PATCH | `/buses/{id}/estado` | Body `{estado: ACTIVO\|INACTIVO\|MANTENIMIENTO}`. |
| GET | `/buses/{id}/seatmap` | Mapa de asientos del bus (US-111), mismo JSON que `distribucion` en `/itineraries/{id}/seatmap`: `{tipo, capacidad, pisos: [{piso, filas, columnas, celdas: [{fila, columna, tipo, asiento?}]}]}`. Tipos de celda: `ASIENTO`, `PASILLO`, `BANO`, `CONDUCTOR`, `ESCALERA`, `PUERTA`, `VACIO`. |
| PUT | `/buses/{id}/seatmap` | Reemplaza el mapa (mismo JSON). Al menos 1 asiento, números únicos, solo las celdas `ASIENTO` llevan número, 1 piso para `UN_PISO` y 2 para `DOS_PISOS` (máximo 2), celdas dentro de `filas`/`columnas` y sin repetir posición; si no, 400 `INVALID_SEATMAP`. La capacidad del bus se recalcula como el número de asientos. 409 `BUS_IN_USE` si el bus tiene viajes futuros no cancelados (el cambio de bus en un viaje llega en el Sprint 3). |

| POST | `/routes` | Crea una ruta con sus paradas (ADMIN/OPERADOR, US-114). Body `{companyId?, origen, destino, paradas: [{orden, nombre, lat, lng, minutosDesdeSalida}]}`. Reglas (400 `INVALID_ROUTE` con `details` por campo, p. ej. `paradas[2].lat`): origen ≠ destino; mínimo 2 paradas; la primera es el origen y la última el destino; `orden` consecutivo desde 1; `minutosDesdeSalida` empieza en 0 y crece estrictamente; lat −4.3 a 13.5 y lng −79.1 a −66.8 (Colombia). `duracionMinutos` = minutos de la última parada. El OPERADOR crea en su empresa; el ADMIN envía `companyId`. Empresa `INACTIVA` → 409 `COMPANY_INACTIVE`. → `RouteCreated`. |
| GET | `/routes?estado=&origen=&destino=&companyId=&page=&size=` | Rutas paginadas (ADMIN/OPERADOR), sin paradas. `origen`/`destino` sin distinguir tildes. El OPERADOR solo ve las de su empresa. |
| GET | `/routes/{id}` | Detalle con paradas. Una ruta de otra empresa es 404 `ROUTE_NOT_FOUND`. |
| PUT | `/routes/{id}` | Reemplaza origen, destino y paradas (mismas reglas). → `RouteUpdated`. |
| PUT | `/routes/{id}/stops` | Reemplaza la lista completa de paradas (array, mismas reglas con el origen y destino de la ruta). → `RouteUpdated`. Si la ruta tiene viajes futuros no cancelados y algo cambia: 409 `ROUTE_IN_USE`. |
| PATCH | `/routes/{id}/estado` | Body `{estado: ACTIVA\|INACTIVA}`. → `RouteUpdated`. Una ruta `INACTIVA` no recibe viajes nuevos. |
| POST | `/itineraries` | Programa un viaje (ADMIN/OPERADOR, US-116). Body `{routeId, busId, fechaSalida, precioBase}`; `fechaLlegadaEstimada` = salida + duración de la ruta. Errores: 400 `INVALID_PRICE` (precio ≤ 0), `DEPARTURE_IN_PAST`, `COMPANY_MISMATCH` (ruta y bus de empresas distintas), `VALIDATION_ERROR`; 404 `ROUTE_NOT_FOUND`/`BUS_NOT_FOUND` (también si son de otra empresa); 409 `COMPANY_INACTIVE`, `ROUTE_INACTIVE`, `BUS_NOT_ACTIVE`, `BUS_SCHEDULE_CONFLICT` (US-117, `details.itineraryId` = viaje con el que choca). → `ItineraryPublished`; invalida la cache de búsquedas. |
| POST | `/itineraries/schedule` | Programación recurrente. Body `{routeId, busId, horaSalida: "06:00", diasSemana: [LUNES..DOMINGO], desde, hasta, precioBase}`: un viaje por cada fecha de `[desde, hasta]` cuyo día esté en `diasSemana`, en hora de Colombia. Máximo 90 días y 200 viajes (400 `INVALID_SCHEDULE`). Responde `{creados: [...], omitidos: [{fecha, fechaSalida, codigo, motivo, conflictoCon?}]}`: una fecha pasada (`DEPARTURE_IN_PAST`) o con cruce (`BUS_SCHEDULE_CONFLICT`) se omite sin hacer fallar las demás. |
| GET | `/itineraries?desde=&hasta=&routeId=&busId=&estado=&companyId=&page=&size=` | Viajes de la empresa (ADMIN/OPERADOR), paginados y ordenados por salida. `desde`/`hasta` son fechas (hora de Colombia, inclusivas). `GET /itineraries/{id}` sigue público. |
| PATCH | `/itineraries/{id}/departure` | Reprograma o retrasa un viaje (ADMIN/OPERADOR de su empresa, US-118). Body `{nuevaFechaSalida, motivo}`; la llegada se recalcula con la duración de la ruta. Solo antes de la salida original (409 `ITINERARY_ALREADY_DEPARTED`) y si no está cancelado (409 `ITINERARY_CANCELLED`); la nueva salida en el futuro (400 `DEPARTURE_IN_PAST`) y distinta de la actual (400 `SAME_DEPARTURE`). El tipo lo calcula el servicio: `RETRASO` si se corre hacia adelante hasta `tripbus.catalog.max-delay` (6 h por defecto), `REPROGRAMACION` si es más o si se adelanta. Revalida US-117 en el mismo bus sin contar el propio viaje (409 `BUS_SCHEDULE_CONFLICT`). Otra empresa → 404. → `ItineraryRescheduled`; invalida la cache de búsquedas. |
| PATCH | `/itineraries/{id}/bus` | Cambia el bus de un viaje no cancelado ni terminado (ADMIN/OPERADOR de su empresa, US-119). Body `{busId, motivo}`: bus de la misma empresa (si no, 404 `BUS_NOT_FOUND` para el OPERADOR o 400 `COMPANY_MISMATCH` para el ADMIN), `ACTIVO` (409 `BUS_NOT_ACTIVE`), distinto del actual (400 `SAME_BUS`) y sin cruces (409 `BUS_SCHEDULE_CONFLICT`). Bloquea las filas de los dos buses en orden de id. Antes de guardar llama a Seat Inventory `POST /internal/itineraries/{id}/remap {seatNumbers, capacidad}` (asientos del mapa real del bus nuevo, con `X-Internal-Token`): su 409 → 409 `SEATS_CONFLICT` con `details.asientos` (asientos vendidos o retenidos que no existen en el bus nuevo); sin respuesta o cualquier otra respuesta que no sea 2xx → 503 `SEAT_INVENTORY_UNAVAILABLE`. En ambos casos no cambia nada. Si el remap salió bien pero la transacción de Catalog falla, vuelve a llamar al remap con los asientos del bus anterior y deja un `ERROR` en el log. → `ItineraryBusChanged`. `/itineraries/{id}/seatmap` pasa a mostrar el mapa del bus nuevo. |
| GET | `/itineraries/{id}/changes` | Historial del viaje (ADMIN/OPERADOR de su empresa): `[{id, tipo: RETRASO\|REPROGRAMACION\|CAMBIO_BUS\|CANCELACION, salidaAnterior, salidaNueva, busAnterior, busNuevo, motivo, hechoPor, creadoEn}]`, del más viejo al más nuevo. |

Los endpoints de buses, rutas y programación responden 403 a un OPERADOR sin `companyId` en el token. Las
empresas y buses del seed quedan `ACTIVA`/`ACTIVO`.

**Un bus no puede estar en dos viajes que se cruzan (US-117).** Dos viajes no cancelados del mismo bus no
pueden solaparse en `[salida, llegada + alistamiento]` (`tripbus.catalog.bus-turnaround`, 30 min por defecto).
Al programar se bloquea la fila del bus (`SELECT ... FOR UPDATE`) antes de revisar el cruce, así que dos
solicitudes simultáneas nunca crean viajes cruzados. Además la base tiene la restricción
`ex_itineraries_bus_sin_cruce` (`EXCLUDE USING gist`, extensión `btree_gist`) que impide cualquier cruce real.
En el seed, las rutas que con el alistamiento no caben en 24 h (p. ej. Cartagena–Villavicencio, 25 h 30 min)
usan un bus alterno los días impares (placa `U…`); la migración V5 movió a ese mismo bus los viajes viejos que
se cruzaban.

**Publica:** `ItineraryPublished` (payload `{itineraryId, routeId, busId, companyId, origen, destino, fechaSalida, fechaLlegada, precioBase, tipoBus, capacidad, seatNumbers}`; `seatNumbers` y `capacidad` salen del mapa de asientos real del bus), `ItineraryCancelled` (payload `{itineraryId, companyId, routeId, fechaSalida, motivo}`; lo consume booking), `ItineraryRescheduled` (payload `{itineraryId, companyId, routeId, busId, fechaSalidaAnterior, fechaSalida, fechaLlegada, minutosDiferencia, tipo, motivo}`; `minutosDiferencia` es negativo si se adelantó), `ItineraryBusChanged` (payload `{itineraryId, companyId, busAnterior, busNuevo, capacidad, seatNumbers}`), `CompanyCreated`, `CompanyUpdated`, `CompanyDeactivated` (payload `{companyId, nombre, nit, estado}`), `RouteCreated`, `RouteUpdated` (payload `{routeId, companyId, origen, destino, duracionMinutos, estado, paradas: [{orden, nombre, lat, lng, minutosDesdeSalida}]}`; nadie los consume todavía, fleet los usará en el Sprint 3). **Consume:** `OfferActivated`, `OfferClosed`, `OfferSoldOut`, `UserLoggedIn`.

Cache de búsquedas en Redis (TTL 45 s). La disponibilidad no se cachea: se consulta en vivo a Seat Inventory.

Disponibilidad en cada resultado de `/trips/search` (US-008):

| Caso | `asientosDisponibles` | `agotado` | `disponibilidadConocida` |
|---|---|---|---|
| Seat Inventory responde con el itinerario | el número que informa | `asientosDisponibles <= 0` | `true` |
| Seat Inventory responde sin el itinerario (nadie ha ocupado asientos aún) | `capacidad` | `false` | `true` |
| Seat Inventory no responde (timeouts 300/500 ms) | `null` | `null` | `false` |

Seat Inventory inicializa un itinerario la primera vez que alguien retiene, vende o reserva para una
oferta uno de sus asientos: si respondió sin ese itinerario, todos sus asientos están libres.

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

Con este compose solo funcionan `/trips/search`, `/trips/cities` y `/trips/in-progress` (públicos; sin seat-inventory la
búsqueda responde sin el dato de asientos, con `disponibilidadConocida: false`). `/admin/**` exige un JWT de identity: pruébalo con el
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
| `TRIPBUS_CATALOG_DEFAULT_COMMISSION` | — | `5.00` (%) |
| `TRIPBUS_CATALOG_DEFAULT_MONTHLY_FEE` | — | `0.00` (COP) |
| `CORS_ALLOWED_ORIGINS` | — | `http://localhost:18000,http://localhost:3000,http://localhost:5173`; en QA/PROD define los orígenes exactos del frontend |

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
  reales, ejecuta el seed y prueba búsqueda, viajes en ruta, mapa de asientos, ofertas y `/admin/seed`.
- `EmpresasBusesIT`: igual (Testcontainers), prueba empresas, buses, mapa de asientos por bus, el efecto
  de inactivar una empresa en la búsqueda (con cache) y en viajes en ruta, y `/internal/companies/{id}`.
- `RutasViajesIT`: igual (Testcontainers), prueba rutas y paradas, programación de viajes (uno y recurrente),
  el payload de `ItineraryPublished`, cruces con alistamiento, 20 solicitudes simultáneas para el mismo bus
  (solo 1 se crea), la restricción `EXCLUDE` de la base y que el seed no genere cruces.
- `EventConsumerIT`: Redis 7 propio (Testcontainers); consumo con consumer group y ack, idempotencia
  por `eventId`, 3 reintentos, evento mal formado y que el grupo se vuelva a crear si desaparece.
- `CambiosDeViajeIT`: igual (Testcontainers) con Seat Inventory simulado con WireMock; prueba retraso contra
  reprogramación según el umbral, reprogramación con y sin cruce, cambio de bus (remap, conflicto de
  asientos, Seat Inventory caído y compensación), la cancelación corregida y el historial.
- `MigracionSinCrucesIT`: PostgreSQL propio; aplica hasta V4, carga viajes cruzados como los del seed viejo
  y verifica que V5 los reparte en el bus alterno antes de crear la restricción.

Toda la plataforma: ver el README de **API-gateway**.

## Calidad

| Herramienta | Qué revisa | Dónde |
|---|---|---|
| JaCoCo | Cobertura de las pruebas y **mínimo obligatorio** (`jacoco:check`) | local: `target/site/jacoco/index.html` (tras `mvn verify`); CI: tabla en el resumen de cada ejecución y artifact `jacoco` |
| SonarQube Cloud | Bugs, code smells y cobertura; quality gate "Sonar way" (código nuevo ≥ 80 %) | proyecto `Trip-Bus_catalog-service` de la organización `trip-bus` |
| Gitleaks | Secretos en todo el historial git | job `secretos` del CI |
| Trivy | Vulnerabilidades CRITICAL/HIGH con corrección disponible en la imagen Docker | job `docker` del CI |

### Cobertura mínima (JaCoCo)

`mvn verify` falla, en **todas** las ramas (también en `main`, donde Sonar no corre), si:

- la cobertura **total** baja de los mínimos del `pom.xml`, o
- **cualquier clase** (fuera de las exclusiones de abajo) queda por debajo del **80 %** de líneas o de
  ramas (`jacoco.minimo.clase`; una clase sin ramas solo se evalúa en líneas). El mensaje de
  `jacoco:check` dice qué clase y qué contador incumple.

| Métrica | Medido (2026-10-24) | Mínimo |
|---|---|---|
| Líneas (total) | 99,0 % | `jacoco.minimo.lineas` = 0.95 |
| Ramas (total) | 95,1 % | `jacoco.minimo.ramas` = 0.95 |
| Líneas y ramas de cada clase | la más baja: 93,3 % líneas / 83,3 % ramas | `jacoco.minimo.clase` = 0.80 |

**La cobertura nunca puede bajar: si agregas pruebas, sube el mínimo.** Corre `mvn verify`, mira
el total en `target/site/jacoco/index.html` (o la tabla del CI), redondéalo hacia abajo al 5 % y
actualiza la propiedad en el pom en el mismo PR. Una clase nueva debe llegar con sus pruebas: si
queda bajo el 80 %, el build falla.

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
en otro caso el CI hace `mvn verify` y deja un aviso.

### Secretos de Docker Hub en el CI (`DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`)

Los runners de GitHub comparten IP y agotan el límite de descargas anónimas de Docker Hub
(`toomanyrequests: You have reached your unauthenticated pull rate limit`), lo que rompe
Testcontainers (`postgres:16-alpine`, Redis) y el build de la imagen (`maven`, `eclipse-temurin`).
Con estos secretos los jobs `secretos`, `build-test` y `docker` inician sesión en Docker Hub antes de
descargar nada y usan el límite de una cuenta autenticada. Gitleaks se descarga desde GHCR
(`ghcr.io/gitleaks/gitleaks`), que no tiene ese límite.

| Secreto | Valor |
|---|---|
| `DOCKERHUB_USERNAME` | Usuario de Docker Hub |
| `DOCKERHUB_TOKEN` | Personal access token con permiso **Public Repo Read-only** (solo lectura; nunca la contraseña) |

Son **opcionales pero recomendados**: si faltan, el CI descarga sin autenticar (como antes), deja un
aviso y puede fallar por el límite. Se crean en Settings → Secrets and variables → Actions.

Escaneos locales con Docker:

```bash
docker run --rm -v "$PWD:/repo" zricethezav/gitleaks:v8.30.1 git /repo --redact
docker build -t catalog-service:local . && \
  docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$PWD:/src" aquasec/trivy:0.70.0 \
  image --severity CRITICAL,HIGH --ignore-unfixed --ignorefile /src/.trivyignore.yaml catalog-service:local
```
