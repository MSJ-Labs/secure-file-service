package com.msj.securefile.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String KEYCLOAK_AUTH = "keycloak";

    @Bean
    public OpenAPI openAPI(@Value("${app.security.oauth2.issuer-uri}") String issuerUri) {
        String oidcBase = issuerUri + "/protocol/openid-connect";
        return new OpenAPI()
                .info(new Info()
                        .title("Secure File Service API")
                        .version("1.0")
                        .description("Streamed uploads into quarantine, ClamAV scan, download only when CLEAN"))
                .addSecurityItem(new SecurityRequirement().addList(KEYCLOAK_AUTH))
                .components(new Components()
                        .addSecuritySchemes(KEYCLOAK_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.OAUTH2)
                                .flows(new OAuthFlows()
                                        .authorizationCode(new OAuthFlow()
                                                .authorizationUrl(oidcBase + "/auth")
                                                .tokenUrl(oidcBase + "/token")
                                                .scopes(new Scopes().addString("openid", "OpenID Connect"))))));
    }
}
