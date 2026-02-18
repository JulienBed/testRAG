package org.acme.rag.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * Interface AI Service pour la génération de résumés via RAG.
 *
 * <h2>Comment fonctionne un AI Service Quarkus LangChain4j ?</h2>
 *
 * <p>L'annotation {@code @RegisterAiService} demande à Quarkus de générer
 * automatiquement une implémentation de cette interface au moment du build.
 * Cette implémentation :</p>
 * <ol>
 *   <li>Intercepte l'appel à la méthode Java</li>
 *   <li>Construit le prompt système + prompt utilisateur</li>
 *   <li>Cherche dans le CDI context un {@code RetrievalAugmentor} bean.
 *       S'il en trouve un (produit par {@code RagConfiguration}), il exécute
 *       la recherche vectorielle et injecte les chunks pertinents (= RAG)</li>
 *   <li>Appelle l'API OpenAI (ou autre LLM configuré)</li>
 *   <li>Retourne la réponse textuelle</li>
 * </ol>
 *
 * <h2>Le pattern RAG</h2>
 * <pre>
 *   Question ──► EmbeddingModel ──► Query Vector
 *                                        │
 *                                        ▼
 *   Vector Store ◄── similarité cosinus ──┘
 *        │
 *        ▼
 *   Top-N chunks pertinents
 *        │
 *        ▼
 *   [System Prompt] + [Chunks] + [Question] ──► LLM ──► Réponse
 * </pre>
 *
 * <h2>Découverte automatique du RetrievalAugmentor</h2>
 * <p>Le bean {@code RetrievalAugmentor} produit par {@link org.acme.rag.config.RagConfiguration}
 * est découvert automatiquement par Quarkus LangChain4j et appliqué à tous les appels.</p>
 */
@RegisterAiService
public interface PdfSummaryAssistant {

    /**
     * Génère un résumé complet du document PDF.
     *
     * <p>Le {@code @SystemMessage} définit le rôle et le comportement du LLM.
     * Le {@code @UserMessage} est le prompt utilisateur avec les variables
     * injectées dynamiquement (entre {accolades}).</p>
     *
     * <p>LangChain4j injecte automatiquement les chunks récupérés du vector store
     * dans le contexte disponible au LLM (via {@code {{information}}} ou en
     * enrichissant le prompt avant l'appel API).</p>
     *
     * @param context  texte du document (extrait du PDF, utilisé pour guider la recherche)
     * @param language langue souhaitée pour la réponse
     * @return résumé structuré généré par le LLM
     */
    @SystemMessage("""
            Tu es un assistant expert en analyse documentaire.
            Ton rôle est d'analyser des documents PDF et de produire des résumés
            clairs, structurés et fidèles au contenu original.

            Règles importantes :
            - Reste strictement fidèle au contenu du document fourni
            - N'invente pas d'informations qui ne sont pas dans le document
            - Si une information n'est pas présente, dis-le explicitement
            - Structure ta réponse avec des sections claires
            - Utilise un langage professionnel et précis
            """)
    @UserMessage("""
            Sur la base du contenu du document suivant, génère un résumé complet.

            Langue de réponse souhaitée : {language}

            Contexte du document disponible via la recherche sémantique :
            {context}

            Génère un résumé structuré comprenant :
            1. **Vue d'ensemble** : de quoi parle ce document ?
            2. **Points principaux** : quelles sont les informations essentielles ?
            3. **Conclusion** : quelle est la conclusion ou le message clé ?

            Résumé :
            """)
    String summarize(String context, String language);

    /**
     * Extrait les points clés du document sous forme de liste.
     *
     * @param context  extrait du document (utilisé comme base de recherche RAG)
     * @param language langue de la réponse
     * @return liste de points clés, un par ligne, préfixé par "•"
     */
    @SystemMessage("""
            Tu es un assistant expert en extraction d'informations clés.
            Tu dois identifier et lister les points les plus importants d'un document.
            Format de réponse : une liste de points, chaque point sur une nouvelle ligne,
            commençant par "•".
            Maximum 8 points. Sois concis et précis.
            """)
    @UserMessage("""
            Extrait les points clés les plus importants du document.

            Langue : {language}
            Contexte : {context}

            Points clés (format bullet "•") :
            """)
    String extractKeyPoints(String context, String language);

    /**
     * Répond à une question spécifique sur le document (mode Q&R).
     *
     * <p>C'est le cas d'usage classique du RAG : l'utilisateur pose une question,
     * le système trouve les passages pertinents du document et le LLM formule
     * une réponse basée sur ces passages.</p>
     *
     * @param question question de l'utilisateur
     * @param language langue de la réponse
     * @return réponse basée sur le contenu du document
     */
    @SystemMessage("""
            Tu es un assistant spécialisé dans l'analyse de documents.
            Réponds aux questions en te basant UNIQUEMENT sur le contenu du document fourni.
            Si la réponse n'est pas dans le document, dis clairement :
            "Cette information ne figure pas dans le document."
            """)
    @UserMessage("""
            Question : {question}

            Langue de réponse : {language}

            Réponds en te basant sur le contenu du document.
            """)
    String answerQuestion(String question, String language);
}
