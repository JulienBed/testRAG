package org.acme.rag.model;

/**
 * Requête optionnelle pour personnaliser le résumé.
 * Permet de guider le LLM vers un type de résumé spécifique.
 */
public class SummaryRequest {

    /**
     * Question ou instruction supplémentaire pour orienter le résumé.
     * Exemples :
     *   - "Quels sont les points clés sur la sécurité ?"
     *   - "Résume en 3 bullet points pour un manager"
     *   - null → résumé général par défaut
     */
    private String customPrompt;

    /**
     * Langue souhaitée pour le résumé (ex: "français", "english").
     * Par défaut : même langue que le document.
     */
    private String language;

    public SummaryRequest() {}

    public String getCustomPrompt() {
        return customPrompt;
    }

    public void setCustomPrompt(String customPrompt) {
        this.customPrompt = customPrompt;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }
}
