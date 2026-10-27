package com.alejandro.bank.accounts.web;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;

import java.util.List;
import java.util.Map;

// El OpenAPI de Quarkus igual al de Spring (BANK-111, OpenApiConfig de accounts-service): el esquema ProblemDetail y
// 401 y 403 en todas las operaciones, sin escribirlos a mano en cada método. Solo se agregan si el método no los
// declaró. La comparación de los dos documentos está en OpenApiParityTest (tests/parity/accounts-openapi.txt)
public class ProblemsOpenApiFilter implements OASFilter {

    @Override
    public void filterOpenAPI(OpenAPI openAPI) {
        if (openAPI.getComponents() == null) {
            openAPI.setComponents(OASFactory.createComponents());
        }
        openAPI.getComponents().addSchema("ProblemDetail", OASFactory.createSchema()
                .type(List.of(Schema.SchemaType.OBJECT))
                .properties(Map.of(
                        "detail", OASFactory.createSchema().type(List.of(Schema.SchemaType.STRING)),
                        "instance", OASFactory.createSchema().type(List.of(Schema.SchemaType.STRING)),
                        "status", OASFactory.createSchema().type(List.of(Schema.SchemaType.INTEGER)),
                        "title", OASFactory.createSchema().type(List.of(Schema.SchemaType.STRING)))));
        if (openAPI.getPaths() == null || openAPI.getPaths().getPathItems() == null) {
            return;
        }
        openAPI.getPaths().getPathItems().values().forEach(path -> path.getOperations().values().forEach(operation -> {
            if (operation.getResponses().getAPIResponse("401") == null) {
                operation.getResponses().addAPIResponse("401", problem("Falta el token o no es válido"));
            }
            if (operation.getResponses().getAPIResponse("403") == null) {
                operation.getResponses().addAPIResponse("403", problem("No tiene permiso para acceder a este recurso"));
            }
        }));
    }

    private static APIResponse problem(String description) {
        return OASFactory.createAPIResponse().description(description).content(OASFactory.createContent()
                .addMediaType("application/problem+json", OASFactory.createMediaType()
                        .schema(OASFactory.createSchema().ref("ProblemDetail"))));
    }
}
