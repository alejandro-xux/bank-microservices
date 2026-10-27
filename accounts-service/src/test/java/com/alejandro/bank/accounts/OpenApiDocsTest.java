package com.alejandro.bank.accounts;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OpenApiDocsTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void docsArePublicAndDescribeTheLoginAndErrors() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.oidc.type").value("openIdConnect"))
                .andExpect(jsonPath("$.paths['/accounts'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/accounts'].post.responses['422']").exists())
                .andExpect(jsonPath("$.paths['/accounts'].post.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/accounts'].post.responses['403']").exists());
    }

    // El contrato de accounts compartido con la versión Quarkus (BANK-111, tests/parity/accounts-openapi.txt): las
    // mismas operaciones, códigos, esquemas y seguridad. accounts-service-quarkus lo revisa en OpenApiParityTest
    @Test
    void theOpenApiMatchesTheContractSharedWithQuarkus() throws Exception {
        JsonNode doc = new ObjectMapper().readTree(
                mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> lines = new ArrayList<>();
        doc.path("paths").properties().forEach(path -> path.getValue().properties().forEach(op -> lines.add(
                op.getKey().toUpperCase() + " " + path.getKey() + " " + String.join(",", names(op.getValue().path("responses"))))));
        lines.sort(null);
        lines.add("schemas " + String.join(",", names(doc.path("components").path("schemas"))));
        lines.add("security " + String.join(",", names(doc.path("components").path("securitySchemes"))));

        assertThat(lines).isEqualTo(Files.readAllLines(Path.of("../tests/parity/accounts-openapi.txt")).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#")).toList());
    }

    private static TreeSet<String> names(JsonNode node) {
        TreeSet<String> names = new TreeSet<>();
        names.addAll(node.propertyNames());
        return names;
    }

    @Test
    void swaggerUiIsPublic() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
