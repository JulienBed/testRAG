package org.acme.rag.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * Configuration CDI pour le pipeline RAG.
 *
 * <h2>Architecture : Ollama (LLM local) + MiniLM (embeddings locaux)</h2>
 *
 * <p>Ce projet utilise deux composants 100% locaux, sans clé API :</p>
 * <ul>
 *   <li><b>Ollama (mistral, llama3, etc.)</b> → génération du résumé (serveur local sur :11434)</li>
 *   <li><b>all-MiniLM-L6-v2</b> → vectorisation des chunks (local, gratuit, embarqué dans le JAR)</li>
 * </ul>
 *
 * <h2>Rôle des beans produits</h2>
 *
 * <h3>EmbeddingModel (local)</h3>
 * <p>{@link AllMiniLmL6V2QuantizedEmbeddingModel} est un modèle ONNX quantisé (~23 Mo)
 * embarqué directement dans le JAR. Il génère des vecteurs de 384 dimensions.
 * Aucun appel réseau, aucune clé API requise.</p>
 *
 * <h3>EmbeddingStore</h3>
 * <p>Stocke les paires (vecteur, TextSegment) pour la recherche sémantique.
 * {@link InMemoryEmbeddingStore} : simple, sans dépendance externe, réinitialisé au redémarrage.</p>
 * <p>Alternatives production : PgVector, Chroma, Pinecone, Qdrant.</p>
 *
 * <h3>ContentRetriever</h3>
 * <p>Effectue la recherche sémantique : vectorise la query via MiniLM,
 * puis cherche les segments les plus proches (similarité cosinus) dans le store.</p>
 *
 * <h3>RetrievalAugmentor</h3>
 * <p>Orchestre le pipeline RAG complet :
 * Query → ContentRetriever → Top-N segments → Injection dans le prompt Claude.</p>
 * <p>Découvert automatiquement par les {@code @RegisterAiService} via CDI.</p>
 */
@ApplicationScoped
public class RagConfiguration {

    /**
     * Produit le modèle d'embedding local all-MiniLM-L6-v2 (quantisé).
     *
     * <p>Ce modèle tourne entièrement en local sur CPU via ONNX Runtime.
     * Il est chargé une seule fois au démarrage de l'application et réutilisé
     * pour toutes les vectorisations (ingestion + recherche).</p>
     *
     * <p>Caractéristiques :</p>
     * <ul>
     *   <li>Dimensions : 384</li>
     *   <li>Taille modèle : ~23 Mo (embarqué dans le JAR)</li>
     *   <li>Vitesse : ~1-5ms par chunk sur CPU standard</li>
     *   <li>Langues : multilingue (excellente qualité en français)</li>
     * </ul>
     */
    @Produces
    @ApplicationScoped
    public EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2QuantizedEmbeddingModel();
    }

    /**
     * Produit l'EmbeddingStore en singleton d'application.
     *
     * <p><b>Singleton important</b> : il doit être unique pour que le même store
     * soit partagé entre {@link org.acme.rag.service.RagIngestionService} (écriture)
     * et le {@link RetrievalAugmentor} (lecture).</p>
     */
    @Produces
    @ApplicationScoped
    public EmbeddingStore<TextSegment> embeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    /**
     * Produit le ContentRetriever qui effectue la recherche sémantique.
     *
     * <p>Paramètres de la recherche :</p>
     * <ul>
     *   <li>{@code maxResults=5} : retourne les 5 segments les plus pertinents</li>
     *   <li>{@code minScore=0.6} : ignore les segments avec similarité < 60%</li>
     * </ul>
     *
     * <p>La similarité cosinus est calculée entre le vecteur de la question
     * et les vecteurs des segments stockés dans l'EmbeddingStore.</p>
     */
    @Produces
    @ApplicationScoped
    public ContentRetriever contentRetriever(EmbeddingStore<TextSegment> store,
                                             EmbeddingModel embeddingModel) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .maxResults(5)
                // MiniLM-L6-v2 produit des scores légèrement différents d'OpenAI.
                // 0.5 est un bon seuil de départ pour ce modèle.
                .minScore(0.5)
                .build();
    }

    /**
     * Produit le RetrievalAugmentor utilisé automatiquement par les AI Services.
     *
     * <p>Quarkus LangChain4j détecte ce bean CDI et l'injecte dans tous les
     * {@code @RegisterAiService} qui ne spécifient pas explicitement un augmenteur.</p>
     *
     * <p>Le {@link DefaultRetrievalAugmentor} prend la query utilisateur,
     * la passe au ContentRetriever, récupère les segments pertinents,
     * et les injecte dans le contexte du LLM avant l'appel API.</p>
     */
    @Produces
    @ApplicationScoped
    public RetrievalAugmentor retrievalAugmentor(ContentRetriever contentRetriever) {
        return DefaultRetrievalAugmentor.builder()
                .contentRetriever(contentRetriever)
                .build();
    }
}
