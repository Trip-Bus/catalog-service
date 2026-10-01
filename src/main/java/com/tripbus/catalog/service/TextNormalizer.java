package com.tripbus.catalog.service;

import java.text.Normalizer;
import java.util.Locale;

/** Normaliza nombres de ciudades para búsqueda: minúsculas, sin tildes, espacios simples. */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String noAccents = Normalizer.normalize(value.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
