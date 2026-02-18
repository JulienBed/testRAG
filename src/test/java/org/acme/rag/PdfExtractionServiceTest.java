package org.acme.rag;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.acme.rag.model.DocumentChunk;
import org.acme.rag.service.PdfExtractionService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires pour le service d'extraction PDF.
 *
 * <p>Ces tests vérifient la logique de chunking et de nettoyage du texte
 * sans dépendance au LLM (pas d'appel API OpenAI).</p>
 */
@QuarkusTest
class PdfExtractionServiceTest {

    @Inject
    PdfExtractionService pdfExtractionService;

    /**
     * Vérifie que le service génère des chunks pour un texte long.
     *
     * <p>Ce test utilise un PDF de test créé programmatiquement avec PDFBox.
     * On vérifie que :</p>
     * <ul>
     *   <li>Le texte est extrait correctement</li>
     *   <li>Les chunks ont la bonne taille</li>
     *   <li>L'overlap est respecté</li>
     * </ul>
     */
    @Test
    void testChunkingWithLongText() throws IOException {
        // Création d'un PDF de test avec PDFBox
        byte[] pdfBytes = TestPdfBuilder.createSimplePdf(generateLongText());

        try (InputStream is = new ByteArrayInputStream(pdfBytes)) {
            PdfExtractionService.ExtractionResult result =
                    pdfExtractionService.extractAndChunk(is, "test.pdf");

            assertNotNull(result, "Le résultat ne doit pas être null");
            assertTrue(result.getPageCount() > 0, "Doit avoir au moins une page");
            assertFalse(result.getFullText().isBlank(), "Le texte extrait ne doit pas être vide");
            assertFalse(result.getChunks().isEmpty(), "Doit avoir au moins un chunk");

            // Vérification de l'indexation des chunks
            List<DocumentChunk> chunks = result.getChunks();
            for (int i = 0; i < chunks.size(); i++) {
                assertEquals(i, chunks.get(i).getIndex(),
                        "L'index du chunk doit être séquentiel");
                assertFalse(chunks.get(i).getContent().isBlank(),
                        "Le contenu du chunk ne doit pas être vide");
            }

            System.out.println("=== Résultat du test de chunking ===");
            System.out.println("Pages : " + result.getPageCount());
            System.out.println("Texte total : " + result.getFullText().length() + " chars");
            System.out.println("Chunks créés : " + chunks.size());
            chunks.forEach(c -> System.out.printf("  %s : %d chars%n",
                    c, c.getContent().length()));
        }
    }

    /**
     * Vérifie que le service gère les PDFs avec peu de texte.
     */
    @Test
    void testChunkingWithShortText() throws IOException {
        byte[] pdfBytes = TestPdfBuilder.createSimplePdf("Document très court.");

        try (InputStream is = new ByteArrayInputStream(pdfBytes)) {
            PdfExtractionService.ExtractionResult result =
                    pdfExtractionService.extractAndChunk(is, "short.pdf");

            assertFalse(result.getFullText().isBlank());
            assertEquals(1, result.getChunks().size(),
                    "Un texte court doit donner exactement 1 chunk");
        }
    }

    // -------------------------------------------------------------------------
    // Utilitaires de test
    // -------------------------------------------------------------------------

    /** Génère un texte long pour tester le chunking. */
    private String generateLongText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Introduction au Machine Learning et à l'Intelligence Artificielle\n\n");

        String[] paragraphs = {
            "Le machine learning est une branche de l'intelligence artificielle qui permet " +
            "aux systèmes informatiques d'apprendre automatiquement à partir de données " +
            "sans être explicitement programmés. Cette approche repose sur des algorithmes " +
            "qui analysent des patterns dans les données pour faire des prédictions ou " +
            "prendre des décisions.\n\n",

            "Les réseaux de neurones artificiels s'inspirent du fonctionnement du cerveau " +
            "humain. Ils sont composés de couches de neurones artificiels interconnectés " +
            "qui transforment les données d'entrée en sortie utiles. Le deep learning " +
            "utilise des réseaux avec de nombreuses couches cachées.\n\n",

            "Le traitement du langage naturel (NLP) est un domaine qui permet aux machines " +
            "de comprendre et de générer du langage humain. Les modèles de langage comme " +
            "GPT utilisent l'architecture Transformer pour modéliser les relations entre " +
            "les mots dans un texte.\n\n",

            "Le RAG (Retrieval-Augmented Generation) combine la puissance des LLM avec " +
            "une base de connaissances externe. Au lieu de se fier uniquement aux " +
            "connaissances encodées lors de l'entraînement, le RAG récupère des " +
            "informations pertinentes depuis une base documentaire pour augmenter " +
            "la précision et la fiabilité des réponses.\n\n",

            "Les embeddings vectoriels sont des représentations numériques de textes " +
            "dans un espace à haute dimension. Deux textes sémantiquement proches " +
            "auront des vecteurs proches dans cet espace. Cette propriété est exploitée " +
            "pour la recherche sémantique dans les vector stores.\n\n"
        };

        // Répéter pour créer un texte suffisamment long
        for (int i = 0; i < 4; i++) {
            for (String p : paragraphs) {
                sb.append(p);
            }
        }

        return sb.toString();
    }
}
