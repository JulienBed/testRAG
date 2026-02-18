package org.acme.rag;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Utilitaire pour créer des PDFs de test programmatiquement.
 *
 * <p>Permet de créer des PDFs avec du contenu textuel connu pour
 * valider l'extraction et le chunking sans dépendre de fichiers externes.</p>
 */
public class TestPdfBuilder {

    private static final int MAX_CHARS_PER_LINE = 85;
    private static final float FONT_SIZE = 11f;
    private static final float LINE_HEIGHT = 14f;
    private static final float MARGIN = 50f;

    /**
     * Crée un PDF simple avec le texte fourni.
     *
     * @param text contenu textuel à intégrer dans le PDF
     * @return bytes du PDF généré
     */
    public static byte[] createSimplePdf(String text) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            String[] lines = wrapText(text, MAX_CHARS_PER_LINE);

            PDPage currentPage = null;
            PDPageContentStream contentStream = null;
            float yPosition = 0;

            for (String line : lines) {
                // Nouvelle page si nécessaire
                if (currentPage == null || yPosition < MARGIN + LINE_HEIGHT) {
                    if (contentStream != null) {
                        contentStream.endText();
                        contentStream.close();
                    }
                    currentPage = new PDPage(PDRectangle.A4);
                    document.addPage(currentPage);
                    contentStream = new PDPageContentStream(document, currentPage);
                    contentStream.beginText();
                    contentStream.setFont(font, FONT_SIZE);
                    yPosition = currentPage.getMediaBox().getHeight() - MARGIN;
                    contentStream.newLineAtOffset(MARGIN, yPosition);
                }

                contentStream.showText(sanitizeForPdf(line));
                contentStream.newLineAtOffset(0, -LINE_HEIGHT);
                yPosition -= LINE_HEIGHT;
            }

            if (contentStream != null) {
                contentStream.endText();
                contentStream.close();
            }

            document.save(baos);
            return baos.toByteArray();
        }
    }

    /** Découpe le texte en lignes de longueur maximale. */
    private static String[] wrapText(String text, int maxChars) {
        String[] rawLines = text.split("\n");
        java.util.List<String> result = new java.util.ArrayList<>();

        for (String line : rawLines) {
            if (line.length() <= maxChars) {
                result.add(line);
            } else {
                // Découpage des lignes trop longues
                int start = 0;
                while (start < line.length()) {
                    int end = Math.min(start + maxChars, line.length());
                    if (end < line.length()) {
                        int spaceIdx = line.lastIndexOf(' ', end);
                        if (spaceIdx > start) end = spaceIdx;
                    }
                    result.add(line.substring(start, end));
                    start = end + 1;
                }
            }
        }
        return result.toArray(new String[0]);
    }

    /** Supprime les caractères non supportés par PDFBox Type1. */
    private static String sanitizeForPdf(String text) {
        return text.replaceAll("[^\\x20-\\x7E]", " ");
    }
}
