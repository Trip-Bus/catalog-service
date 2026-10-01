package com.tripbus.catalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tripbus.services")
public record ServiceUrls(String seatInventoryUrl) {
}
