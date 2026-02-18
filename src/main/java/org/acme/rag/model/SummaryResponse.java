package org.acme.rag.model;

import java.util.List;

/**
 * Réponse retournée par l'API après traitement du PDF.
 *
 * Contient le résumé généré par le LLM et des métadonnées
 * sur le document traité.
 */
public class SummaryResponse {

    /** Nom du fichier PDF traité */
    private String fileName;

    /** Nombre de pages du PDF */
    private int pageCount;

    /** Nombre de chunks créés pour le vector store */
    private int chunkCount;

    /** Résumé généré par le LLM via RAG */
    private String summary;

    /** Points clés extraits du document */
    private List<String> keyPoints;

    /** Informations de statut */
    private String status;

    public SummaryResponse() {}

    // ----- Builder-style factory -----

    public static SummaryResponse success(String fileName, int pageCount,
                                          int chunkCount, String summary,
                                          List<String> keyPoints) {
        SummaryResponse r = new SummaryResponse();
        r.fileName   = fileName;
        r.pageCount  = pageCount;
        r.chunkCount = chunkCount;
        r.summary    = summary;
        r.keyPoints  = keyPoints;
        r.status     = "SUCCESS";
        return r;
    }

    public static SummaryResponse error(String fileName, String message) {
        SummaryResponse r = new SummaryResponse();
        r.fileName = fileName;
        r.summary  = message;
        r.status   = "ERROR";
        return r;
    }

    // ----- Getters / Setters -----

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public int getPageCount() { return pageCount; }
    public void setPageCount(int pageCount) { this.pageCount = pageCount; }

    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public List<String> getKeyPoints() { return keyPoints; }
    public void setKeyPoints(List<String> keyPoints) { this.keyPoints = keyPoints; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
