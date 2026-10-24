package com.alejandro.bank.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// El config-server real (puerto aleatorio) sirviendo config-repo/ como lo pide customers-service al arrancar
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfigServerApplicationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void servesCustomersServiceFileBeforeCommonFile() throws Exception {
        JsonNode sources = get("/customers-service/default").at("/propertySources");

        assertThat(sources.size()).isEqualTo(2);
        // El archivo del servicio va primero: sus valores mandan sobre los comunes
        assertThat(sources.get(0).at("/name").asString()).endsWith("customers-service.yml");
        assertThat(sources.get(1).at("/name").asString()).endsWith("application.yml");
    }

    @Test
    void servesExpectedProperties() throws Exception {
        JsonNode sources = get("/customers-service/default").at("/propertySources");

        assertThat(sources.get(0).at("/source/server.port").asInt()).isEqualTo(8081);
        assertThat(sources.get(0).at("/source/spring.data.redis.port").asString()).isEqualTo("${REDIS_PORT:6380}");
        assertThat(sources.get(1).at("/source/spring.security.oauth2.resourceserver.jwt.authorities-claim-name").asString())
                .isEqualTo("roles");
    }

    // Repositorio público: el config-server nunca debe servir credenciales (decisión S6)
    @Test
    void doesNotServeCosmosCredentials() throws Exception {
        List<String> keys = new ArrayList<>();
        get("/customers-service/default").at("/propertySources")
                .forEach(source -> source.at("/source").propertyNames().forEach(keys::add));

        assertThat(keys).isNotEmpty().noneMatch(key -> key.toLowerCase().contains("cosmos"));
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return jsonMapper.readTree(response.body());
    }
}
