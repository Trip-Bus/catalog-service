package com.tripbus.catalog.web;
import com.tripbus.catalog.service.OperatorTripService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class OperatorTripControllerTest {
 @Test void limitaOperadorALaEmpresaDelTokenYPermiteAdminIndicarEmpresa(){
  OperatorTripService service=mock(OperatorTripService.class);OperatorTripController controller=new OperatorTripController(service);
  UUID operator=UUID.randomUUID(),company=UUID.randomUUID();
  Jwt operatorJwt=Jwt.withTokenValue("token").header("alg","none").subject(operator.toString()).claim("companyId",company.toString()).build();
  controller.trips(null,null,null,0,50,new JwtAuthenticationToken(operatorJwt,List.of(new SimpleGrantedAuthority("ROLE_OPERADOR"))));
  verify(service).list(null,null,null,company,0,50);
  Jwt adminJwt=Jwt.withTokenValue("token").header("alg","none").subject(UUID.randomUUID().toString()).build();
  controller.trips(null,null,company,1,10,new JwtAuthenticationToken(adminJwt,List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
  verify(service).list(null,null,company,null,1,10);
 }
}
