package org.gluu.apps.configapi;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
public class OxTrustWebResourceTest {

    @Test
    public void testHelloEndpoint() {
        given()
          .when().get("/api/config/oxtrust")
          .then()
             .statusCode(200)
             .body(is("hello"));
    }

}