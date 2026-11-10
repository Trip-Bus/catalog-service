package com.tripbus.catalog.web.dto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record OperatorTripPage(Summary resumen, List<Trip> content, int page, int size, long totalElements, int totalPages) {
 public record Summary(long viajes, BigDecimal ocupacionPromedio, long candidatosOferta) { }
 public record Occupancy(int vendidos,int disponibles,int bloqueados,BigDecimal porcentaje,BigDecimal tasaAbandono) { }
 public record Trip(UUID itineraryId,String origen,String destino,Instant fechaSalida,Instant fechaLlegadaEstimada,
                    String placa,String tipoBus,String estado,BigDecimal precioBase,OfertaResumen ofertaActiva,
                    Occupancy ocupacion,boolean ocupacionConocida) { }
}
