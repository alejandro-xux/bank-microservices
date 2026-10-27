package com.alejandro.bank.accounts.web;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// El filtro solo, sin Quarkus: Quarkus lo aplica al compilar (arma el documento una vez), así que las pruebas
// @QuarkusTest ven su resultado pero no lo cuentan como cubierto
class ProblemsOpenApiFilterTest {

    private final ProblemsOpenApiFilter filter = new ProblemsOpenApiFilter();

    @Test
    void addsTheProblemSchemaAnd401And403WhereMissing() {
        Operation get = OASFactory.createOperation().responses(OASFactory.createAPIResponses()
                .addAPIResponse("200", OASFactory.createAPIResponse().description("ok")));
        Operation post = OASFactory.createOperation().responses(OASFactory.createAPIResponses()
                .addAPIResponse("401", OASFactory.createAPIResponse().description("propia")));
        OpenAPI openAPI = OASFactory.createOpenAPI().paths(OASFactory.createPaths()
                .addPathItem("/accounts", OASFactory.createPathItem().GET(get).POST(post)));

        filter.filterOpenAPI(openAPI);

        assertThat(openAPI.getComponents().getSchemas()).containsKey("ProblemDetail");
        assertThat(openAPI.getComponents().getSchemas().get("ProblemDetail").getProperties())
                .containsOnlyKeys("detail", "instance", "status", "title");
        assertThat(get.getResponses().getAPIResponses()).containsOnlyKeys("200", "401", "403");
        assertThat(get.getResponses().getAPIResponse("401").getContent().getMediaType("application/problem+json")
                .getSchema().getRef()).endsWith("ProblemDetail");
        // La descripción propia del método se respeta
        assertThat(post.getResponses().getAPIResponse("401").getDescription()).isEqualTo("propia");
        assertThat(post.getResponses().getAPIResponses()).containsOnlyKeys("401", "403");
    }

    @Test
    void withoutPathsOnlyTheSchemaIsAdded() {
        OpenAPI openAPI = OASFactory.createOpenAPI().components(OASFactory.createComponents());

        filter.filterOpenAPI(openAPI);

        assertThat(openAPI.getComponents().getSchemas()).containsOnlyKeys("ProblemDetail");
        assertThat(openAPI.getPaths()).isNull();
    }

    @Test
    void anOperationWithBothAlreadyDeclaredIsLeftAlone() {
        Operation op = OASFactory.createOperation().responses(OASFactory.createAPIResponses()
                .addAPIResponse("401", OASFactory.createAPIResponse().description("a"))
                .addAPIResponse("403", OASFactory.createAPIResponse().description("b")));
        OpenAPI openAPI = OASFactory.createOpenAPI().paths(OASFactory.createPaths()
                .addPathItem("/x", OASFactory.createPathItem().GET(op)));

        filter.filterOpenAPI(openAPI);

        assertThat(op.getResponses().getAPIResponse("403").getDescription()).isEqualTo("b");
    }
}
