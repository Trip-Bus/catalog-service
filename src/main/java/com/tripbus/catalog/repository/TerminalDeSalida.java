package com.tripbus.catalog.repository;

import java.util.UUID;

/** Terminal de una parada de las rutas de una empresa en una ciudad (de donde sale el bus). */
public record TerminalDeSalida(UUID companyId, String terminal) {
}
