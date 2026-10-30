package io.github.koslowskyj.example;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;

@QuarkusTest
@TestProfile(ExampleProfile.class)
class WidgetResourceTest {

    @Test
    void lists_the_seeded_widget() {
        given().when().get("/widgets").then().statusCode(200).body("name", hasItem("first widget"));
    }

    @Test
    void adds_then_lists_a_widget() {
        given().queryParam("name", "added").when().post("/widgets").then().statusCode(200).body("name", is("added"));
        given().when().get("/widgets").then().statusCode(200).body("name", hasItems("first widget", "added"));
    }
}
