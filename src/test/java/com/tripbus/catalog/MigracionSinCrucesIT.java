package com.tripbus.catalog;

import com.tripbus.catalog.seed.SeedService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-117: la migración V5 sobre una base con datos del seed anterior, donde las rutas de más de 24 h usaban
 * el mismo bus todos los días (viajes cruzados). Debe mover los días impares al bus alterno que también usa
 * el seed nuevo y solo entonces crear la restricción EXCLUDE.
 */
@Testcontainers
class MigracionSinCrucesIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final LocalDate PRIMER_DIA = LocalDate.of(2026, 11, 1);

    @Test
    void v5MueveLosDiasImparesAlBusAlternoYCreaLaRestriccion() throws Exception {
        flyway("4").migrate();

        UUID empresa = UUID.randomUUID();
        UUID rutaLarga = UUID.randomUUID();
        UUID rutaCorta = UUID.randomUUID();
        UUID busLargo = SeedService.id("bus:car-vil:0");
        UUID busCorto = UUID.randomUUID();
        try (Connection c = conexion()) {
            ejecutar(c, "insert into companies (id, nombre, nit) values (?, 'Expreso', '900100001-1')", empresa);
            ejecutar(c, "insert into routes (id, company_id, origen, destino, origen_norm, destino_norm, duracion_minutos)"
                    + " values (?, ?, 'Cartagena', 'Villavicencio', 'cartagena', 'villavicencio', 1530)", rutaLarga, empresa);
            ejecutar(c, "insert into routes (id, company_id, origen, destino, origen_norm, destino_norm, duracion_minutos)"
                    + " values (?, ?, 'Bogotá', 'Tunja', 'bogota', 'tunja', 150)", rutaCorta, empresa);
            ejecutar(c, "insert into buses (id, company_id, placa, tipo, capacidad) values (?, ?, 'TCA350', 'DOS_PISOS', 60)",
                    busLargo, empresa);
            ejecutar(c, "insert into buses (id, company_id, placa, tipo, capacidad) values (?, ?, 'TBO070', 'UN_PISO', 40)",
                    busCorto, empresa);
            ejecutar(c, "insert into seat_maps (id, bus_id, distribucion) values (?, ?, '{\"pisos\":[]}')",
                    UUID.randomUUID(), busLargo);
            for (int d = 0; d < 4; d++) {
                OffsetDateTime salida = PRIMER_DIA.plusDays(d).atTime(10, 30).atOffset(ZoneOffset.UTC); // 05:30 Bogotá
                viaje(c, rutaLarga, busLargo, salida, 1530);
                viaje(c, rutaCorta, busCorto, salida, 150);
            }
        }

        flyway(null).migrate();

        UUID alterno = SeedService.busAlterno(busLargo);
        try (Connection c = conexion()) {
            Map<LocalDate, UUID> busPorDia = new HashMap<>();
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select (fecha_salida at time zone 'America/Bogota')::date, bus_id from itineraries where route_id = '"
                            + rutaLarga + "'")) {
                while (rs.next()) {
                    busPorDia.put(rs.getObject(1, LocalDate.class), rs.getObject(2, UUID.class));
                }
            }
            assertThat(busPorDia).hasSize(4);
            busPorDia.forEach((dia, bus) -> assertThat(bus).as(dia.toString())
                    .isEqualTo(ChronoUnit.DAYS.between(LocalDate.of(2000, 1, 1), dia) % 2 == 1 ? alterno : busLargo));

            try (ResultSet rs = c.createStatement().executeQuery(
                    "select placa, tipo, capacidad, company_id from buses where id = '" + alterno + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("UCA350");
                assertThat(rs.getString(2)).isEqualTo("DOS_PISOS");
                assertThat(rs.getInt(3)).isEqualTo(60);
                assertThat(rs.getObject(4, UUID.class)).isEqualTo(empresa);
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select id, distribucion from seat_maps where bus_id = '" + alterno + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject(1, UUID.class)).isEqualTo(SeedService.id("seatmap:" + alterno));
                assertThat(rs.getString(2)).isEqualTo("{\"pisos\":[]}");
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select count(distinct bus_id) from itineraries where route_id = '" + rutaCorta + "'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }

            // La restricción ya existe: un viaje cruzado real se rechaza.
            OffsetDateTime cruzada = PRIMER_DIA.atTime(12, 0).atOffset(ZoneOffset.UTC);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> viaje(c, rutaCorta, busCorto, cruzada, 60))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ex_itineraries_bus_sin_cruce");
        }
    }

    private static Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static Connection conexion() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void viaje(Connection c, UUID ruta, UUID bus, OffsetDateTime salida, int minutos) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("insert into itineraries (id, route_id, bus_id, fecha_salida,"
                + " fecha_llegada_estimada, precio_base) values (?, ?, ?, ?, ?, 100000)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, ruta);
            ps.setObject(3, bus);
            ps.setObject(4, salida);
            ps.setObject(5, salida.plusMinutes(minutos));
            ps.executeUpdate();
        }
    }

    private static void ejecutar(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }
}
