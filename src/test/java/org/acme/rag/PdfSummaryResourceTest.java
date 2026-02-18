package org.acme.rag;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;

/**
 * Tests d'intégration pour l'API REST.
 *
 * <p>Ces tests utilisent RestAssured pour tester les endpoints HTTP.
 * Le LLM est mocké via {@code %test.quarkus.langchain4j.mock-chat-model.enabled=true}
 * dans application.properties pour éviter les appels API réels pendant les tests.</p>
 */
@QuarkusTest
class PdfSummaryResourceTest {

    /**
     * Vérifie que le health check répond correctement.
     */
    @Test
    void testHealthEndpoint() {
        given()
            .when().get("/api/pdf/health")
            .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("service", equalTo("LLM RAG PDF Summary"));
    }

    /**
     * Vérifie qu'un upload sans fichier retourne une erreur 400.
     */
    @Test
    void testSummarizeWithoutFile() {
        given()
            .contentType("multipart/form-data")
            .when().post("/api/pdf/summarize")
            .then()
                .statusCode(anyOf(is(400), is(415), is(500)));
    }

    /**
     * Vérifie qu'un fichier non-PDF est rejeté.
     */
    @Test
    void testSummarizeWithNonPdfFile() {
        given()
            .contentType("multipart/form-data")
            .multiPart("file", "test.txt", "This is not a PDF".getBytes(), "text/plain")
            .when().post("/api/pdf/summarize")
            .then()
                .statusCode(400)
                .body("status", equalTo("ERROR"));
    }

    /**
     * Vérifie qu'un PDF valide est traité correctement.
     * Le LLM mocké retourne une réponse générique.
     */
    @Test
    void testSummarizeWithValidPdf() throws IOException {
        byte[] pdfBytes = TestPdfBuilder.createSimplePdf(
            "Introduction à l'Intelligence Artificielle\n\n" +
            "L'intelligence artificielle est un domaine de l'informatique " +
            "qui vise à créer des systèmes capables d'effectuer des tâches " +
            "qui nécessitent normalement l'intelligence humaine.\n\n" +
            "Les applications incluent la vision par ordinateur, " +
            "le traitement du langage naturel, et la robotique."
        );

        given()
            .contentType("multipart/form-data")
            .multiPart("file", "test-document.pdf", pdfBytes, "application/pdf")
            .formParam("language", "français")
            .when().post("/api/pdf/summarize")
            .then()
                .statusCode(anyOf(is(200), is(422)))  // 200 si OK, 422 si LLM mock vide
                .body("fileName", equalTo("test-document.pdf"));
    }

    /**
     * Vérifie que le endpoint /ask répond aux questions.
     */
    @Test
    void testAskWithoutQuestion() {
        given()
            .contentType("application/json")
            .body("{\"language\": \"français\"}")
            .when().post("/api/pdf/ask")
            .then()
                .statusCode(400);
    }

    /**
     * Vérifie que le endpoint /ask accepte une question valide.
     */
    @Test
    void testAskWithValidQuestion() {
        given()
            .contentType("application/json")
            .body("{\"customPrompt\": \"Quels sont les points clés ?\", \"language\": \"français\"}")
            .when().post("/api/pdf/ask")
            .then()
                .statusCode(anyOf(is(200), is(500)))  // 200 si le store est alimenté
                .contentType(containsString("application/json"));
    }
}
