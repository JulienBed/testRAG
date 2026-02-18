package org.acme.rag.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.rag.model.DocumentChunk;
import org.acme.rag.model.SummaryRequest;
import org.acme.rag.model.SummaryResponse;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service orchestrateur principal du pipeline RAG.
 *
 * <h2>Orchestration du pipeline complet</h2>
 * <p>Ce service coordonne les trois étapes fondamentales du RAG :</p>
 *
 * <h3>Phase 1 : Indexation (Ingestion)</h3>
 * <pre>
 *   PDF ──► PdfExtractionService ──► chunks ──► RagIngestionService ──► VectorStore
 * </pre>
 *
 * <h3>Phase 2 : Récupération (Retrieval)</h3>
 * <pre>
 *   Question ──► EmbeddingModel ──► query vector
 *   query vector ──► VectorStore.findRelevant() ──► Top-N chunks
 * </pre>
 *
 * <h3>Phase 3 : Génération (Generation)</h3>
 * <pre>
 *   [System Prompt] + [Top-N chunks] + [Question] ──► LLM ──► Réponse
 * </pre>
 *
 * <p>Ces trois phases forment le pattern <b>RAG (Retrieval-Augmented Generation)</b>,
 * qui permet à un LLM de répondre avec précision sur des documents privés/spécifiques
 * sans avoir besoin de les inclure dans son entraînement.</p>
 */
@ApplicationScoped
public class PdfRagService {

    private static final Logger LOG = Logger.getLogger(PdfRagService.class);

    /** Langue par défaut si non spécifiée */
    private static final String DEFAULT_LANGUAGE = "français";

    @Inject
    PdfExtractionService pdfExtractionService;

    @Inject
    RagIngestionService ragIngestionService;

    @Inject
    PdfSummaryAssistant summaryAssistant;

    // -------------------------------------------------------------------------
    // API publique principale
    // -------------------------------------------------------------------------

    /**
     * Traitement complet d'un PDF : extraction → indexation → résumé.
     *
     * @param pdfStream  flux du fichier PDF
     * @param fileName   nom du fichier
     * @param request    paramètres optionnels (langue, prompt personnalisé)
     * @return réponse avec résumé et points clés
     */
    public SummaryResponse processPdf(InputStream pdfStream,
                                      String fileName,
                                      SummaryRequest request) {
        LOG.infof("=== Démarrage du pipeline RAG pour '%s' ===", fileName);

        String language = resolveLanguage(request);

        try {
            // ── PHASE 1 : Extraction du PDF ───────────────────────────────────
            LOG.info("[Phase 1] Extraction du texte PDF...");
            PdfExtractionService.ExtractionResult extraction =
                    pdfExtractionService.extractAndChunk(pdfStream, fileName);

            List<DocumentChunk> chunks = extraction.getChunks();
            LOG.infof("[Phase 1] OK → %d pages, %d chunks extraits",
                    extraction.getPageCount(), chunks.size());

            if (extraction.getFullText().isBlank()) {
                return SummaryResponse.error(fileName,
                        "Le PDF ne contient pas de texte extractible " +
                        "(PDF scanné ou protégé). Essayez un PDF avec du texte natif.");
            }

            // ── PHASE 2 : Ingestion dans le Vector Store ──────────────────────
            LOG.info("[Phase 2] Ingestion dans le vector store (embedding)...");
            ragIngestionService.ingestChunks(chunks, fileName);
            LOG.infof("[Phase 2] OK → %d vecteurs indexés", chunks.size());

            // ── PHASE 3a : Génération du résumé via RAG ───────────────────────
            LOG.info("[Phase 3a] Génération du résumé via LLM + RAG...");

            // On utilise un extrait du texte comme contexte de recherche
            // (les N premiers caractères pour guider la recherche vectorielle)
            String searchContext = buildSearchContext(extraction.getFullText(), request);
            String summary = summaryAssistant.summarize(searchContext, language);
            LOG.infof("[Phase 3a] OK → résumé généré (%d chars)", summary.length());

            // ── PHASE 3b : Extraction des points clés ────────────────────────
            LOG.info("[Phase 3b] Extraction des points clés...");
            String rawKeyPoints = summaryAssistant.extractKeyPoints(searchContext, language);
            List<String> keyPoints = parseKeyPoints(rawKeyPoints);
            LOG.infof("[Phase 3b] OK → %d points clés extraits", keyPoints.size());

            LOG.infof("=== Pipeline RAG terminé pour '%s' ===", fileName);

            return SummaryResponse.success(fileName, extraction.getPageCount(),
                    chunks.size(), summary, keyPoints);

        } catch (IOException e) {
            LOG.errorf(e, "Erreur lors de l'extraction du PDF '%s'", fileName);
            return SummaryResponse.error(fileName,
                    "Erreur lors de la lecture du PDF : " + e.getMessage());
        } catch (Exception e) {
            LOG.errorf(e, "Erreur lors du traitement RAG pour '%s'", fileName);
            return SummaryResponse.error(fileName,
                    "Erreur lors de la génération du résumé : " + e.getMessage());
        }
    }

    /**
     * Mode Question-Réponse : répond à une question spécifique sur le document.
     *
     * <p>Nécessite que le document ait été préalablement indexé via
     * {@link #processPdf}. Le RAG retrouve automatiquement les passages
     * pertinents dans le vector store.</p>
     *
     * @param question question de l'utilisateur
     * @param language langue souhaitée
     * @return réponse basée sur le contenu du document
     */
    public String answerQuestion(String question, String language) {
        LOG.infof("Question RAG : '%s'", question);
        String effectiveLanguage = (language != null && !language.isBlank())
                ? language : DEFAULT_LANGUAGE;
        return summaryAssistant.answerQuestion(question, effectiveLanguage);
    }

    // -------------------------------------------------------------------------
    // Méthodes privées utilitaires
    // -------------------------------------------------------------------------

    private String resolveLanguage(SummaryRequest request) {
        if (request != null && request.getLanguage() != null
                && !request.getLanguage().isBlank()) {
            return request.getLanguage();
        }
        return DEFAULT_LANGUAGE;
    }

    /**
     * Construit le contexte de recherche pour le LLM.
     * Si un prompt personnalisé est fourni, on l'utilise comme requête de
     * recherche. Sinon, on prend le début du texte extrait.
     */
    private String buildSearchContext(String fullText, SummaryRequest request) {
        if (request != null && request.getCustomPrompt() != null
                && !request.getCustomPrompt().isBlank()) {
            return request.getCustomPrompt();
        }
        // Extrait les 2000 premiers caractères comme contexte général
        int limit = Math.min(2000, fullText.length());
        return fullText.substring(0, limit);
    }

    /**
     * Parse la liste de points clés retournée par le LLM.
     * Le LLM retourne une chaîne avec des "•" ou "-" en début de ligne.
     */
    private List<String> parseKeyPoints(String rawKeyPoints) {
        if (rawKeyPoints == null || rawKeyPoints.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rawKeyPoints.split("\n"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                // Garde uniquement les lignes commençant par • ou -
                .filter(line -> line.startsWith("•") || line.startsWith("-")
                        || line.startsWith("*") || Character.isDigit(line.charAt(0)))
                // Nettoie le préfixe
                .map(line -> line.replaceFirst("^[•\\-*\\d\\.]+\\s*", "").trim())
                .filter(line -> !line.isBlank())
                .collect(Collectors.toList());
    }
}
