package com.alejandro.bank.discovery;

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

import static org.assertj.core.api.Assertions.assertThat;

// Eureka real en un puerto aleatorio: la API del registro y el panel responden
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DiscoveryServerApplicationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void registryApiRespondsAndStartsEmpty() throws Exception {
        HttpResponse<String> response = get("/eureka/apps", "application/json");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode applications = jsonMapper.readTree(response.body()).at("/applications/application");
        // Nadie se registró: ni el propio servidor (register-with-eureka: false)
        assertThat(applications.isMissingNode() || applications.isEmpty()).isTrue();
    }

    @Test
    void dashboardIsServed() throws Exception {
        HttpResponse<String> response = get("/", "text/html");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Eureka");
    }

    private HttpResponse<String> get(String path, String accept) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Accept", accept).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
