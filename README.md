# Catalog Service

Catálogo de viajes de TripBus: rutas, paradas, itinerarios, buses, mapas de asientos y precios.
Responde las búsquedas del pasajero y es dueño de la carga inicial de datos (seed).

**HU:** HT-01, US-007, US-008, US-014 · **Puerto:** 16002 · **BD:** `tripbus_catalog`

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

## Variables

`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `IDENTITY_JWKS_URI`, `SEAT_INVENTORY_URL`, `INTERNAL_API_TOKEN`, `SEED_ON_STARTUP`.

## Ejecutar

Toda la plataforma: `cd ../API-gateway && docker compose up -d --build` (ver README de API-gateway).

Solo este servicio (con PostgreSQL y Redis del compose levantados):

```bash
mvn spring-boot:run          # puerto 16002
```

## Pruebas

```bash
mvn test                     # unitarias
TRIPBUS_IT=true mvn test     # + integración contra PostgreSQL/Redis reales (base tripbus_catalog_test)
```

El pipeline `.github/workflows/ci-cd.yml` corre todas las pruebas y publica la imagen en GHCR.
