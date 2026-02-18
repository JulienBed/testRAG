package org.acme.rag.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.acme.rag.model.DocumentChunk;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Service d'extraction de texte depuis un fichier PDF.
 *
 * <h2>Pipeline d'extraction</h2>
 * <ol>
 *   <li><b>Chargement</b> : PDFBox ouvre le document PDF</li>
 *   <li><b>Extraction</b> : PDFTextStripper lit le texte page par page</li>
 *   <li><b>Nettoyage</b> : suppression des espaces superflus, caractères
 *       non-imprimables, lignes vides répétées</li>
 *   <li><b>Chunking</b> : découpage du texte en segments de taille fixe
 *       avec chevauchement (overlap) pour préserver le contexte</li>
 * </ol>
 *
 * <h2>Pourquoi le chunking ?</h2>
 * <p>Un LLM a une fenêtre de contexte limitée (ex: 128k tokens pour GPT-4o).
 * En RAG, on ne passe pas tout le document au LLM, mais seulement les N
 * chunks les plus pertinents pour la question posée. Le chunking permet :</p>
 * <ul>
 *   <li>Une recherche vectorielle précise (petits segments = meilleure similarité)</li>
 *   <li>Un contexte LLM maîtrisé (pas de dépassement de limite)</li>
 *   <li>Une attribution des sources fiable</li>
 * </ul>
 */
@ApplicationScoped
public class PdfExtractionService {

    private static final Logger LOG = Logger.getLogger(PdfExtractionService.class);

    /**
     * Taille cible d'un chunk en caractères.
     * ~1000 caractères ≈ ~250 tokens (ratio approximatif pour le français/anglais).
     * Règle pratique : 500-1500 caractères selon le type de document.
     */
    private static final int CHUNK_SIZE = 1000;

    /**
     * Chevauchement entre deux chunks consécutifs.
     * Un overlap de 20% évite de couper le contexte sémantique à la frontière
     * entre deux chunks (ex: une phrase commencée dans le chunk N-1 peut se
     * terminer dans le chunk N).
     */
    private static final int CHUNK_OVERLAP = 200;

    // -------------------------------------------------------------------------
    // API publique
    // -------------------------------------------------------------------------

    /**
     * Extrait le texte d'un PDF et le découpe en chunks prêts pour le RAG.
     *
     * @param pdfInputStream flux du fichier PDF uploadé
     * @param fileName       nom du fichier (pour les logs)
     * @return liste de chunks ordonnés
     * @throws IOException si le PDF est invalide ou illisible
     */
    public ExtractionResult extractAndChunk(InputStream pdfInputStream,
                                            String fileName) throws IOException {
        LOG.infof("Début de l'extraction du PDF : %s", fileName);

        // RandomAccessReadBuffer lit le flux directement sans créer de byte[] intermédiaire.
        // Avec readAllBytes() + Loader.loadPDF(byte[]), le tableau brut et le buffer PDFBox
        // coexistaient en mémoire ; ici, un seul buffer interne est alloué.
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(pdfInputStream))) {
            int pageCount = document.getNumberOfPages();
            LOG.infof("PDF chargé : %d page(s)", pageCount);

            // Étape 1 : extraction du texte brut
            String rawText = extractRawText(document);
            LOG.debugf("Texte brut extrait : %d caractères", rawText.length());

            // Étape 2 : nettoyage du texte (libère rawText dès que possible)
            String cleanText = cleanText(rawText);
            LOG.debugf("Texte nettoyé : %d caractères", cleanText.length());

            // Étape 3 : découpage en chunks
            List<DocumentChunk> chunks = splitIntoChunks(cleanText);
            LOG.infof("Chunking terminé : %d chunk(s) créés", chunks.size());

            return new ExtractionResult(cleanText, chunks, pageCount);
        }
    }

    // -------------------------------------------------------------------------
    // Méthodes privées
    // -------------------------------------------------------------------------

    /**
     * Utilise PDFTextStripper pour extraire le texte de toutes les pages.
     * PDFTextStripper respecte l'ordre de lecture (colonne, paragraphe).
     */
    private String extractRawText(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        // Trier par position pour un texte cohérent (important pour les PDF multicolonnes)
        stripper.setSortByPosition(true);
        return stripper.getText(document);
    }

    /**
     * Nettoie le texte extrait en un seul passage sur les caractères.
     *
     * <p>La version précédente chaînait quatre {@code replaceAll()} : chaque appel
     * crée une copie complète de la chaîne, soit ~4× la taille du texte brut
     * en mémoire transitoire. Pour un PDF de 5 Mo de texte, cela représente
     * ~20 Mo d'objets {@code String} temporaires pouvant déclencher un OOM.</p>
     *
     * <p>Cette implémentation lit {@code rawText} une seule fois et écrit dans
     * un {@code StringBuilder} pré-alloué, produisant une unique copie finale.</p>
     */
    private String cleanText(String rawText) {
        int len = rawText.length();
        StringBuilder sb = new StringBuilder(len);

        int consecutiveNewlines = 0;
        int consecutiveSpaces   = 0;

        for (int i = 0; i < len; i++) {
            char c = rawText.charAt(i);

            // Caractères de contrôle non-imprimables (sauf \t \n \r)
            if ((c <= 0x08) || (c == 0x0B) || (c == 0x0C) || (c >= 0x0E && c <= 0x1F) || c == 0x7F) {
                continue;
            }

            // Tabulation → espace
            if (c == '\t') c = ' ';

            // Retour chariot (\r) : ignore seul, laisse le \n suivant gérer CRLF
            if (c == '\r') {
                continue;
            }

            if (c == '\n') {
                consecutiveSpaces = 0;
                consecutiveNewlines++;
                // Réduit les sauts de ligne consécutifs à deux au maximum
                if (consecutiveNewlines <= 2) {
                    sb.append('\n');
                }
                continue;
            }

            if (c == ' ') {
                consecutiveNewlines = 0;
                consecutiveSpaces++;
                // Réduit les espaces consécutifs à un seul
                if (consecutiveSpaces == 1) {
                    sb.append(' ');
                }
                continue;
            }

            // Caractère normal
            consecutiveNewlines = 0;
            consecutiveSpaces   = 0;
            sb.append(c);
        }

        // Trim : supprimer les espaces/newlines en début et fin
        int start = 0;
        while (start < sb.length() && sb.charAt(start) <= ' ') start++;
        int end = sb.length();
        while (end > start && sb.charAt(end - 1) <= ' ') end--;

        return sb.substring(start, end);
    }

    /**
     * Découpe le texte en chunks avec overlap.
     *
     * <h3>Algorithme de sliding window :</h3>
     * <pre>
     *   Position :  0    1000  1800  2600  ...
     *   Chunk 0  : [===========]
     *   Chunk 1  :        [===========]    (overlap de 200 chars avec chunk 0)
     *   Chunk 2  :               [===========]
     * </pre>
     *
     * <p>On essaie de couper aux frontières naturelles (fins de phrase)
     * pour améliorer la cohérence sémantique de chaque chunk.</p>
     */
    private List<DocumentChunk> splitIntoChunks(String text) {
        List<DocumentChunk> chunks = new ArrayList<>();

        if (text.isEmpty()) {
            return chunks;
        }

        int start = 0;
        int chunkIndex = 0;

        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());

            // Optimisation : couper à la fin d'une phrase (. ! ?) ou d'un paragraphe (\n)
            // plutôt qu'au milieu d'un mot
            if (end < text.length()) {
                int boundary = findNaturalBoundary(text, end);
                if (boundary > start) {
                    end = boundary;
                }
            }

            String chunkText = text.substring(start, end).trim();

            if (!chunkText.isEmpty()) {
                // Estimation du numéro de page (linéaire, approximatif)
                int estimatedPage = 1; // sera affiné si on extrait page par page
                chunks.add(new DocumentChunk(chunkIndex++, chunkText, estimatedPage));
            }

            // Avancer en tenant compte de l'overlap
            start = end - CHUNK_OVERLAP;
            if (start <= 0) start = end; // sécurité anti-boucle infinie
        }

        return chunks;
    }

    /**
     * Cherche la fin naturelle d'une phrase ou d'un paragraphe
     * dans un rayon de 200 caractères autour de la position souhaitée.
     */
    private int findNaturalBoundary(String text, int preferredEnd) {
        int searchStart = Math.max(0, preferredEnd - 200);
        int bestBoundary = preferredEnd;

        // Priorité : fin de paragraphe (\n\n)
        int paraEnd = text.lastIndexOf("\n\n", preferredEnd);
        if (paraEnd > searchStart) return paraEnd + 2;

        // Sinon : fin de phrase (. ! ?)
        for (int i = preferredEnd; i >= searchStart; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                // Vérifier que c'est vraiment une fin de phrase (suivi d'espace/newline)
                if (i + 1 < text.length()) {
                    char next = text.charAt(i + 1);
                    if (next == ' ' || next == '\n') {
                        return i + 1;
                    }
                }
            }
        }

        // Sinon : fin de mot (espace)
        int lastSpace = text.lastIndexOf(' ', preferredEnd);
        if (lastSpace > searchStart) {
            bestBoundary = lastSpace;
        }

        return bestBoundary;
    }

    // -------------------------------------------------------------------------
    // Classe résultat
    // -------------------------------------------------------------------------

    /**
     * Résultat de l'extraction : texte complet + chunks + métadonnées.
     */
    public static class ExtractionResult {

        private final String fullText;
        private final List<DocumentChunk> chunks;
        private final int pageCount;

        public ExtractionResult(String fullText, List<DocumentChunk> chunks, int pageCount) {
            this.fullText  = fullText;
            this.chunks    = chunks;
            this.pageCount = pageCount;
        }

        public String getFullText() { return fullText; }
        public List<DocumentChunk> getChunks() { return chunks; }
        public int getPageCount() { return pageCount; }
    }
}
