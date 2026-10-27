package com.alejandro.bank.accounts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

// El OpenAPI de Quarkus contra el contrato de accounts (BANK-111): las mismas operaciones con los mismos códigos de
// respuesta, los mismos esquemas y el mismo esquema de seguridad que Spring. El contrato está en
// tests/parity/accounts-openapi.txt y Spring lo revisa igual (OpenApiDocsTest de accounts-service). Quarkus puede
// publicar esquemas de más (los enums van aparte), nunca de menos
@QuarkusTest
class OpenApiParityTest extends IntegrationTest {

    private static final Path CONTRACT = Path.of("../tests/parity/accounts-openapi.txt");

    @Test
    void theOpenApiMatchesTheContractSharedWithSpring() throws IOException {
        JsonNode doc = new ObjectMapper().readTree(
                given().accept("application/json").when().get("/v3/api-docs").then().statusCode(200).extract().asString());
        List<String> contract = Files.readAllLines(CONTRACT).stream().filter(l -> !l.isBlank() && !l.startsWith("#")).toList();

        assertThat(operations(doc)).as("operaciones y códigos (MÉTODO path códigos)")
                .containsExactlyElementsOf(contract.stream().filter(l -> !l.startsWith("schemas ") && !l.startsWith("security ")).toList());
        assertThat(names(doc.path("components").path("schemas"))).as("esquemas")
                .containsAll(Set.of(line(contract, "schemas ").split(",")));
        assertThat(String.join(",", names(doc.path("components").path("securitySchemes")))).as("esquema de seguridad")
                .isEqualTo(line(contract, "security "));
    }

    static List<String> operations(JsonNode doc) {
        List<String> lines = new ArrayList<>();
        doc.path("paths").properties().forEach(path -> path.getValue().properties().forEach(op -> {
            if (Set.of("get", "post", "put", "patch", "delete").contains(op.getKey())) {
                lines.add(op.getKey().toUpperCase() + " " + path.getKey() + " "
                        + String.join(",", names(op.getValue().path("responses"))));
            }
        }));
        lines.sort(null);
        return lines;
    }

    private static TreeSet<String> names(JsonNode node) {
        TreeSet<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String line(List<String> contract, String prefix) {
        return contract.stream().filter(l -> l.startsWith(prefix)).findFirst().orElseThrow().substring(prefix.length());
    }
}
