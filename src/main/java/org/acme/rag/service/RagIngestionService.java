package org.acme.rag.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.rag.model.DocumentChunk;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Service d'ingestion RAG : transforme les chunks de texte en vecteurs
 * et les stocke dans le vector store.
 *
 * <h2>Pipeline d'ingestion RAG complet</h2>
 * <pre>
 *   PDF Bytes
 *      │
 *      ▼
 *   PDFTextStripper (PDFBox)
 *      │  texte brut
 *      ▼
 *   cleanText() → suppression des artefacts
 *      │  texte propre
 *      ▼
 *   DocumentSplitter → chunks (ex: 1000 chars + 200 overlap)
 *      │  List<TextSegment>
 *      ▼
 *   EmbeddingModel (text-embedding-ada-002)
 *      │  List<float[]> (vecteurs 1536D)
 *      ▼
 *   EmbeddingStore (in-memory)
 *      │  Indexé pour recherche cosinus
 *      ▼
 *   Prêt pour les requêtes RAG !
 * </pre>
 *
 * <h2>Qu'est-ce qu'un embedding ?</h2>
 * <p>Un embedding est une représentation numérique (vecteur flottant) d'un
 * texte dans un espace sémantique à haute dimension (ex: 1536 dimensions
 * pour text-embedding-ada-002). Deux textes ayant un sens proche auront
 * des vecteurs proches (similarité cosinus élevée).</p>
 *
 * <p>Exemple : "Le chat dort" et "Le félin sommeille" auront des vecteurs
 * très proches, même si les mots sont différents.</p>
 */
@ApplicationScoped
public class RagIngestionService {

    private static final Logger LOG = Logger.getLogger(RagIngestionService.class);

    /**
     * EmbeddingModel injecté par Quarkus LangChain4j.
     * Configuré dans application.properties :
     * quarkus.langchain4j.openai.embedding-model.model-name=text-embedding-ada-002
     */
    @Inject
    EmbeddingModel embeddingModel;

    /**
     * EmbeddingStore injecté par Quarkus LangChain4j.
     * Ici : InMemoryEmbeddingStore (quarkus-langchain4j-in-process-embedding).
     * En production : PgVectorEmbeddingStore, ChromaEmbeddingStore, etc.
     */
    @Inject
    EmbeddingStore<TextSegment> embeddingStore;

    // -------------------------------------------------------------------------
    // API publique
    // -------------------------------------------------------------------------

    /**
     * Ingère une liste de chunks dans le vector store.
     *
     * <p>Pour chaque chunk :</p>
     * <ol>
     *   <li>Création d'un {@link TextSegment} avec métadonnées (fileName, chunkIndex)</li>
     *   <li>Appel à l'API d'embedding (OpenAI) → vecteur float[]</li>
     *   <li>Stockage {vecteur → segment} dans l'EmbeddingStore</li>
     * </ol>
     *
     * @param chunks   chunks extraits du PDF
     * @param fileName nom du fichier source (stocké en métadonnée)
     */
    public void ingestChunks(List<DocumentChunk> chunks, String fileName) {
        LOG.infof("Début de l'ingestion RAG : %d chunks pour '%s'", chunks.size(), fileName);

        if (chunks.isEmpty()) {
            LOG.warn("Aucun chunk à ingérer !");
            return;
        }

        // Construction des TextSegments avec métadonnées
        // API LangChain4j 1.0.0-alpha1 : Metadata.from() n'accepte qu'une paire clé/valeur
        // Pour plusieurs entrées, on utilise put() en chaîne
        List<TextSegment> segments = chunks.stream()
                .map(chunk -> {
                    Metadata metadata = new Metadata()
                            .put("fileName", fileName)
                            .put("chunkIndex", chunk.getIndex())
                            .put("sourcePage", chunk.getSourcePage());
                    return TextSegment.from(chunk.getContent(), metadata);
                })
                .toList();

        // Génération des embeddings via l'API OpenAI
        LOG.infof("Appel EmbeddingModel pour %d segments...", segments.size());
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

        // Stockage dans le vector store
        embeddingStore.addAll(embeddings, segments);

        LOG.infof("Ingestion terminée : %d vecteurs stockés dans le vector store", embeddings.size());
    }

    /**
     * Version alternative utilisant l'EmbeddingStoreIngestor de LangChain4j.
     *
     * <p>L'ingestor combine automatiquement le splitting, l'embedding et le
     * stockage en une seule opération. Utile si vous avez des {@link Document}
     * LangChain4j plutôt que des chunks manuels.</p>
     *
     * @param rawText  texte brut du document
     * @param fileName nom du fichier source
     * @return nombre de segments ingérés
     */
    public int ingestRawText(String rawText, String fileName) {
        LOG.infof("Ingestion via EmbeddingStoreIngestor pour '%s'", fileName);

        // Création d'un Document LangChain4j avec métadonnées
        Document document = Document.from(
                rawText,
                Metadata.from("fileName", fileName)
        );

        // Splitter : découpe en segments de 1000 chars avec 200 de chevauchement
        DocumentSplitter splitter = DocumentSplitters.recursive(1000, 200);

        // Ingestor : splitting + embedding + storage en une passe
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(splitter)
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();

        ingestor.ingest(document);

        List<TextSegment> segments = splitter.split(document);
        LOG.infof("Ingestion via ingestor terminée : ~%d segments", segments.size());
        return segments.size();
    }

    /**
     * Vide le vector store (utile en test ou pour réindexer un document).
     *
     * <p><b>Note :</b> L'InMemoryEmbeddingStore ne supporte pas nativement
     * la suppression par métadonnée. En production avec PgVector, on pourrait
     * filtrer par fileName pour ne supprimer que les embeddings d'un document
     * spécifique.</p>
     */
    public void clearStore() {
        // L'InMemoryEmbeddingStore ne dispose pas d'une méthode clear() publique,
        // mais on peut noter qu'un redémarrage de l'application le réinitialise.
        LOG.info("Réinitialisation du vector store (disponible uniquement via redémarrage pour InMemory)");
    }
}
