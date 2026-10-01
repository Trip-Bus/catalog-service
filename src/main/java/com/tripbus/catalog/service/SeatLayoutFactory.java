package com.tripbus.catalog.service;

import com.tripbus.catalog.domain.BusType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Genera la distribución real de asientos (US-016) para los tipos de bus del MVP.
 * Celdas: ASIENTO, PASILLO, BANO, CONDUCTOR, ESCALERA, PUERTA, VACIO.
 * Columnas 1-2 y 4-5 son asientos; la columna 3 es el pasillo.
 */
public final class SeatLayoutFactory {

    public static final int COLUMNAS = 5;

    private SeatLayoutFactory() {
    }

    public static int capacidad(BusType tipo) {
        return tipo == BusType.DOS_PISOS ? 60 : 40;
    }

    public static Map<String, Object> layout(BusType tipo) {
        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("tipo", tipo.name());
        layout.put("capacidad", capacidad(tipo));
        List<Map<String, Object>> pisos = new ArrayList<>();
        if (tipo == BusType.UN_PISO) {
            List<Map<String, Object>> celdas = new ArrayList<>();
            cabina(celdas);
            asientos(celdas, 1, 10, 1);
            fila(celdas, 11, "VACIO", "VACIO", "PASILLO", "BANO", "BANO");
            pisos.add(piso(1, 11, celdas));
        } else {
            List<Map<String, Object>> piso1 = new ArrayList<>();
            cabina(piso1);
            int numero = asientos(piso1, 1, 3, 1);
            fila(piso1, 4, "ESCALERA", "VACIO", "PASILLO", "BANO", "BANO");
            pisos.add(piso(1, 4, piso1));

            List<Map<String, Object>> piso2 = new ArrayList<>();
            fila(piso2, 0, "ESCALERA", "VACIO", "PASILLO", "VACIO", "VACIO");
            asientos(piso2, 1, 12, numero);
            pisos.add(piso(2, 12, piso2));
        }
        layout.put("pisos", pisos);
        return layout;
    }

    /** Lista de números de asiento en orden ascendente ("1".."40" o "1".."60"). */
    public static List<String> seatNumbers(BusType tipo) {
        List<String> numeros = new ArrayList<>();
        for (int i = 1; i <= capacidad(tipo); i++) {
            numeros.add(String.valueOf(i));
        }
        return numeros;
    }

    private static void cabina(List<Map<String, Object>> celdas) {
        fila(celdas, 0, "CONDUCTOR", "VACIO", "PASILLO", "VACIO", "PUERTA");
    }

    private static int asientos(List<Map<String, Object>> celdas, int filaInicio, int filaFin, int primerNumero) {
        int numero = primerNumero;
        for (int fila = filaInicio; fila <= filaFin; fila++) {
            for (int columna = 1; columna <= COLUMNAS; columna++) {
                if (columna == 3) {
                    celdas.add(celda(fila, columna, "PASILLO", null));
                } else {
                    celdas.add(celda(fila, columna, "ASIENTO", String.valueOf(numero++)));
                }
            }
        }
        return numero;
    }

    private static void fila(List<Map<String, Object>> celdas, int fila, String... tipos) {
        for (int columna = 1; columna <= tipos.length; columna++) {
            celdas.add(celda(fila, columna, tipos[columna - 1], null));
        }
    }

    private static Map<String, Object> celda(int fila, int columna, String tipo, String asiento) {
        Map<String, Object> celda = new LinkedHashMap<>();
        celda.put("fila", fila);
        celda.put("columna", columna);
        celda.put("tipo", tipo);
        if (asiento != null) {
            celda.put("asiento", asiento);
        }
        return celda;
    }

    private static Map<String, Object> piso(int numero, int filas, List<Map<String, Object>> celdas) {
        Map<String, Object> piso = new LinkedHashMap<>();
        piso.put("piso", numero);
        piso.put("filas", filas + 1);
        piso.put("columnas", COLUMNAS);
        piso.put("celdas", celdas);
        return piso;
    }
}
