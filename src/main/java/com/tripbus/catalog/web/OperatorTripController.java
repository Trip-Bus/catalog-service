package com.tripbus.catalog.web;
import com.tripbus.catalog.common.security.CurrentUser;
import com.tripbus.catalog.service.OperatorTripService;
import com.tripbus.catalog.web.dto.OperatorTripPage;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.UUID;
@RestController
public class OperatorTripController {
 private final OperatorTripService service;
 public OperatorTripController(OperatorTripService service){this.service=service;}
 @GetMapping("/operator/trips") @PreAuthorize("hasAnyRole('OPERADOR','ADMIN')")
 public OperatorTripPage trips(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate desde,
  @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate hasta,
  @RequestParam(required=false) UUID companyId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size,Authentication auth){
  return service.list(desde,hasta,companyId,CurrentUser.hasRole(auth,"ADMIN")?null:CurrentUser.companyScope(auth),page,size);
 }
}
