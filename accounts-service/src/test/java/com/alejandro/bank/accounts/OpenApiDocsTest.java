package com.alejandro.bank.accounts;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

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

    @Test
    void swaggerUiIsPublic() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
