package org.gluu.apps.configapi;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.info.Contact;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.servers.Server;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@ApplicationPath("/")
@OpenAPIDefinition(
    info = @Info(title = "Config API",
        description = "This API allows to change Gluu server configuration",
        version = "1.0",
        contact = @Contact(name = "Gluu", url = "https://github.com/gluuFederation/oxtrust-api")),
    servers = {
        @Server(url = "http://localhost:8083")
    },
    externalDocs = @ExternalDocumentation(url = "https://github.com/gluuFederation/oxtrust-api", description = "All the Quarkus workshops"),
    tags = {
        @Tag(name = "api", description = "Public that can be used by anybody"),
        @Tag(name = "configuration", description = "Anybody interested in heroes")
    }
)
public class ApiApplication extends Application {
}
