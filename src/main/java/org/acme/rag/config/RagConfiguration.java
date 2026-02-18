package org.acme.rag.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * Configuration CDI pour le pipeline RAG.
 *
 * <h2>Rôle des beans produits</h2>
 *
 * <h3>EmbeddingStore</h3>
 * <p>Stocke les paires (vecteur, TextSegment) pour la recherche sémantique.
 * Ici, {@link InMemoryEmbeddingStore} est utilisé : simple, sans dépendance externe,
 * réinitialisé à chaque redémarrage.</p>
 *
 * <p>Alternatives production :</p>
 * <ul>
 *   <li>{@code quarkus-langchain4j-pgvector} → PostgreSQL avec extension vector</li>
 *   <li>{@code quarkus-langchain4j-chroma} → Chroma DB (spécialisé vecteurs)</li>
 *   <li>{@code quarkus-langchain4j-pinecone} → Pinecone (SaaS)</li>
 *   <li>{@code quarkus-langchain4j-qdrant} → Qdrant (open source)</li>
 * </ul>
 *
 * <h3>ContentRetriever</h3>
 * <p>Effectue la recherche sémantique : prend une query, la vectorise via
 * l'EmbeddingModel, puis cherche les segments les plus proches dans le store.</p>
 *
 * <h3>RetrievalAugmentor</h3>
 * <p>Orchestre le pipeline RAG complet :
 * Query → ContentRetriever → Segments → Injection dans le prompt LLM.</p>
 * <p>Découvert automatiquement par les {@code @RegisterAiService} via CDI.</p>
 */
@ApplicationScoped
public class RagConfiguration {

    @Inject
    EmbeddingModel embeddingModel;

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
    public ContentRetriever contentRetriever(EmbeddingStore<TextSegment> store) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .maxResults(5)
                .minScore(0.6)
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
