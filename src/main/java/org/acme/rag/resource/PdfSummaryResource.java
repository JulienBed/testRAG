package org.acme.rag.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.acme.rag.model.SummaryRequest;
import org.acme.rag.model.SummaryResponse;
import org.acme.rag.service.PdfRagService;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * Endpoint REST pour le traitement RAG de fichiers PDF.
 *
 * <h2>Endpoints disponibles</h2>
 * <ul>
 *   <li>{@code POST /api/pdf/summarize} - Upload un PDF et génère un résumé</li>
 *   <li>{@code POST /api/pdf/ask} - Pose une question sur le dernier document indexé</li>
 *   <li>{@code GET  /api/pdf/health} - Vérifie l'état du service</li>
 * </ul>
 *
 * <h2>Exemple d'utilisation avec curl</h2>
 * <pre>
 * # Résumé d'un PDF
 * curl -X POST http://localhost:8080/api/pdf/summarize \
 *   -F "file=@/chemin/vers/document.pdf" \
 *   -F "language=français"
 *
 * # Question sur le document (après upload)
 * curl -X POST http://localhost:8080/api/pdf/ask \
 *   -H "Content-Type: application/json" \
 *   -d '{"customPrompt": "Quels sont les risques mentionnés ?", "language": "français"}'
 * </pre>
 */
@Path("/api/pdf")
@Produces(MediaType.APPLICATION_JSON)
public class PdfSummaryResource {

    private static final Logger LOG = Logger.getLogger(PdfSummaryResource.class);

    @Inject
    PdfRagService pdfRagService;

    // -------------------------------------------------------------------------
    // POST /api/pdf/summarize
    // -------------------------------------------------------------------------

    /**
     * Upload un PDF et génère un résumé via le pipeline RAG.
     *
     * <p>Ce endpoint accepte un {@code multipart/form-data} avec :</p>
     * <ul>
     *   <li>{@code file} : le fichier PDF (obligatoire)</li>
     *   <li>{@code language} : langue du résumé (optionnel, défaut: français)</li>
     *   <li>{@code customPrompt} : instruction personnalisée (optionnel)</li>
     * </ul>
     *
     * @param file         fichier PDF uploadé
     * @param language     langue souhaitée pour le résumé
     * @param customPrompt instruction personnalisée optionnelle
     * @return résumé structuré avec points clés
     */
    @POST
    @Path("/summarize")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response summarize(
            @RestForm("file") FileUpload file,
            @RestForm("language") String language,
            @RestForm("customPrompt") String customPrompt) {

        if (file == null || file.filePath() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(SummaryResponse.error("unknown", "Aucun fichier PDF fourni."))
                    .build();
        }

        String fileName = file.fileName() != null ? file.fileName() : "document.pdf";
        LOG.infof("Réception de '%s' (%.1f KB)",
                fileName, file.size() / 1024.0);

        // Validation : vérifier que c'est bien un PDF
        if (!isPdfFile(fileName)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(SummaryResponse.error(fileName,
                            "Le fichier doit être un PDF (.pdf)."))
                    .build();
        }

        // Construction de la requête de résumé
        SummaryRequest request = new SummaryRequest();
        request.setLanguage(language);
        request.setCustomPrompt(customPrompt);

        try (InputStream pdfStream = Files.newInputStream(file.filePath())) {
            SummaryResponse response = pdfRagService.processPdf(pdfStream, fileName, request);

            if ("ERROR".equals(response.getStatus())) {
                // 422 Unprocessable Entity (Response.Status.UNPROCESSABLE_ENTITY
                // n'est disponible que dans JAX-RS 3.1+, on utilise le code direct)
                return Response.status(422)
                        .entity(response)
                        .build();
            }

            return Response.ok(response).build();

        } catch (IOException e) {
            LOG.errorf(e, "Erreur de lecture du fichier '%s'", fileName);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(SummaryResponse.error(fileName, "Erreur serveur : " + e.getMessage()))
                    .build();
        }
    }

    // -------------------------------------------------------------------------
    // POST /api/pdf/ask
    // -------------------------------------------------------------------------

    /**
     * Pose une question sur le dernier document indexé dans le vector store.
     *
     * <p>Ce endpoint utilise le RAG pour retrouver les passages pertinents
     * du document précédemment uploadé et générer une réponse contextualisée.</p>
     *
     * <p><b>Important :</b> Le vector store est en mémoire, donc le document
     * doit avoir été uploadé dans la même session (même instance JVM).</p>
     *
     * @param request requête contenant la question (dans {@code customPrompt})
     *                et la langue souhaitée
     * @return réponse JSON avec la réponse du LLM
     */
    @POST
    @Path("/ask")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response askQuestion(SummaryRequest request) {
        if (request == null || request.getCustomPrompt() == null
                || request.getCustomPrompt().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"Le champ 'customPrompt' (votre question) est obligatoire.\"}")
                    .build();
        }

        LOG.infof("Question reçue : '%s'", request.getCustomPrompt());

        String answer = pdfRagService.answerQuestion(
                request.getCustomPrompt(),
                request.getLanguage()
        );

        return Response.ok()
                .entity("{\"question\": \"" + escapeJson(request.getCustomPrompt()) +
                        "\", \"answer\": \"" + escapeJson(answer) + "\"}")
                .build();
    }

    // -------------------------------------------------------------------------
    // GET /api/pdf/health
    // -------------------------------------------------------------------------

    /**
     * Vérifie l'état du service.
     *
     * @return statut du service
     */
    @GET
    @Path("/health")
    public Response health() {
        return Response.ok()
                .entity("{\"status\": \"UP\", \"service\": \"LLM RAG PDF Summary\"}")
                .build();
    }

    // -------------------------------------------------------------------------
    // Utilitaires privés
    // -------------------------------------------------------------------------

    private boolean isPdfFile(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".pdf");
    }

    /** Échappe les caractères spéciaux JSON dans une chaîne. */
    private String escapeJson(String value) {
        if (value == null) return "";
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
