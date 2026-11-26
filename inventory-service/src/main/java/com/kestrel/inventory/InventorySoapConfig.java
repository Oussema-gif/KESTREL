package com.kestrel.inventory;

import jakarta.xml.ws.Endpoint;
import java.nio.file.Path;
import java.time.Clock;
import org.apache.cxf.Bus;
import org.apache.cxf.jaxws.EndpointImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Configuration
class InventorySoapConfig {
    @Bean
    JwtDecoder inventoryJwtDecoder(@Value("${kestrel.jwt-public-key}") Path publicKey, Clock clock) throws Exception {
        return SoapSecurity.decoder(publicKey, clock);
    }

    @Bean(destroyMethod = "stop")
    Endpoint inventorySoap(Bus bus, InventoryEndpoint service, JwtDecoder decoder) {
        EndpointImpl endpoint = new EndpointImpl(bus, service);
        endpoint.setWsdlLocation("classpath:contracts/soap/inventory.wsdl");
        endpoint.getInInterceptors().add(new SoapSecurity.Authenticate(decoder));
        endpoint.getInInterceptors().add(new SoapSecurity.Authorize());
        endpoint.getOutFaultInterceptors().add(new ClientFaultInterceptor());
        endpoint.publish("/inventory");
        return endpoint;
    }
}
