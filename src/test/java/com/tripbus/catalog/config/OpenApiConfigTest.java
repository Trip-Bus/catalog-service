package com.tripbus.catalog.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.info.BuildProperties;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** HT-10: OPENAPI_SERVER_URL y versión del documento. */
class OpenApiConfigTest {

    private OpenAPI openApi(DefaultListableBeanFactory beans, String serverUrl) {
        return new OpenApiConfig().catalogOpenApi(beans.getBeanProvider(BuildProperties.class), serverUrl);
    }

    @Test
    void conUrlAnunciaEseServidor() {
        assertThat(openApi(new DefaultListableBeanFactory(), " https://api.tripbus.co ").getServers())
                .extracting(Server::getUrl).containsExactly("https://api.tripbus.co");
    }

    @Test
    void vaciaDejaQueSpringdocUseLaDelServicio() {
        OpenAPI openApi = openApi(new DefaultListableBeanFactory(), "");
        assertThat(openApi.getServers()).isNull();
        assertThat(openApi.getInfo().getVersion()).isEqualTo("dev");
    }

    @Test
    void conBuildInfoUsaLaVersionDelBuild() {
        Properties props = new Properties();
        props.setProperty("version", "1.2.3");
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("buildProperties", new BuildProperties(props));
        assertThat(openApi(beans, null).getInfo().getVersion()).isEqualTo("1.2.3");
    }
}
