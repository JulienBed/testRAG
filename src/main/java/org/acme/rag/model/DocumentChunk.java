package org.acme.rag.model;

/**
 * Représente un fragment (chunk) de texte extrait du PDF.
 *
 * <p>Le texte d'un PDF est découpé en chunks avant d'être vectorisé.
 * Ce découpage (chunking) est essentiel pour le RAG :</p>
 * <ul>
 *   <li>Trop grand → contexte LLM dépassé, pertinence réduite</li>
 *   <li>Trop petit → perte de contexte sémantique</li>
 *   <li>Recommandé : 500-1000 tokens avec 10-20% de chevauchement</li>
 * </ul>
 */
public class DocumentChunk {

    /** Index du chunk dans le document (0-based) */
    private final int index;

    /** Contenu textuel du chunk */
    private final String content;

    /** Numéro de page d'origine (approximatif) */
    private final int sourcePage;

    public DocumentChunk(int index, String content, int sourcePage) {
        this.index      = index;
        this.content    = content;
        this.sourcePage = sourcePage;
    }

    public int getIndex() { return index; }
    public String getContent() { return content; }
    public int getSourcePage() { return sourcePage; }

    @Override
    public String toString() {
        return "Chunk[" + index + "](page=" + sourcePage +
               ", chars=" + content.length() + ")";
    }
}
