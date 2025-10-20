package org.example;

import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.nio.file.*;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import java.util.List;

public class OcrService {

    private final ITesseract tesseract = new Tesseract();

    /**
     * @param tessdataPath  peut être null. S'il est fourni via -Dtessdata=..., il doit pointer
     *                      DIRECTEMENT sur le dossier "tessdata" (celui qui contient eng.traineddata, fra.traineddata).
     *                      Sinon, on cherche "tessdata" dans le classpath (src/main/resources/tessdata) :
     *                        - en IDE: on pointe directement sur target/classes/tessdata
     *                        - en JAR: on extrait vers un dossier temporaire .../tessdata
     */
    public OcrService(String tessdataPath) {
        try {
            if (tessdataPath != null && !tessdataPath.isBlank()) {
                // Cas explicite via -Dtessdata=... → DOIT être le dossier tessdata lui-même
                tesseract.setDatapath(tessdataPath);
            } else {
                // Cherche "tessdata" dans le classpath
                URL url = Thread.currentThread().getContextClassLoader().getResource("tessdata");
                if (url == null) {
                    throw new IllegalStateException(
                            "Ressource 'tessdata' introuvable dans le classpath. Attendu:\n" +
                                    "  src/main/resources/tessdata/eng.traineddata\n" +
                                    "  src/main/resources/tessdata/fra.traineddata"
                    );
                }

                String protocol = url.getProtocol(); // "file" en IDE, "jar" depuis le fat-jar
                if ("file".equalsIgnoreCase(protocol)) {
                    // ✅ IDE: resource = dossier réel (ex: target/classes/tessdata)
                    Path tessdataDir = Paths.get(url.toURI()); // toURI() évite le bug "/C:/..."
                    if (!Files.isDirectory(tessdataDir)) {
                        throw new IllegalStateException("'tessdata' n'est pas un dossier: " + tessdataDir);
                    }
                    // 👉 datapath DOIT être le dossier tessdata lui-même
                    tesseract.setDatapath(tessdataDir.toAbsolutePath().toString());

                } else {
                    // ✅ JAR: extraire eng/fra vers un dossier temp ".../tessdata"
                    Path tempRoot = Files.createTempDirectory("tess4j_");
                    Path tessDir  = tempRoot.resolve("tessdata");
                    Files.createDirectories(tessDir);

                    copyResourceFromClasspath("tessdata/eng.traineddata", tessDir.resolve("eng.traineddata"));
                    copyResourceFromClasspath("tessdata/fra.traineddata", tessDir.resolve("fra.traineddata"));

                    // 👉 datapath = dossier tessdata lui-même
                    tesseract.setDatapath(tessDir.toAbsolutePath().toString());

                    // Nettoyage à la fin du process
                    tempRoot.toFile().deleteOnExit();
                    tessDir.toFile().deleteOnExit();
                    tessDir.resolve("eng.traineddata").toFile().deleteOnExit();
                    tessDir.resolve("fra.traineddata").toFile().deleteOnExit();
                }
            }

            // ===== Réglages OCR pour tickets de caisse (texte noir sur fond blanc) =====
            tesseract.setLanguage("eng+fra");
            tesseract.setOcrEngineMode(ITessAPI.TessOcrEngineMode.OEM_LSTM_ONLY);

            // Bon point de départ : texte un peu dispersé/irrégulier sur tickets
            tesseract.setPageSegMode(ITessAPI.TessPageSegMode.PSM_SPARSE_TEXT);
            // Si tes tickets sont bien droits avec lignes nettes, essaie plutôt :
            // tesseract.setPageSegMode(ITessAPI.TessPageSegMode.PSM_AUTO);

            // Variables utiles
            tesseract.setVariable("user_defined_dpi", "300");          // si l'image n'a pas de DPI
            tesseract.setVariable("preserve_interword_spaces", "1");   // garder les espaces
            tesseract.setVariable("tessedit_char_blacklist", "|~`^'\""); // retire qq parasites fréquents

            // Exemple de whitelist contextuelle (à activer ponctuellement si tu OCR-ises une zone chiffres)
            // tesseract.setVariable("tessedit_char_whitelist", "0123456789.,:/-+%€$CHFABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz");

        } catch (Exception e) {
            throw new IllegalStateException("Impossible de préparer le dossier tessdata: " + e.getMessage(), e);
        }
    }

    private void copyResourceFromClasspath(String resourcePath, Path target) throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new FileNotFoundException("Ressource introuvable dans le classpath: " + resourcePath);
            }
            Files.copy(in, target, REPLACE_EXISTING);
        }
    }

    public ResultModel run(BufferedImage img) throws Exception {
        String text = tesseract.doOCR(img);

        List<Word> words = tesseract.getWords(img, ITessAPI.TessPageIteratorLevel.RIL_WORD);
        double avgConf = 0.0;
        if (words != null && !words.isEmpty()) {
            long sum = 0;
            for (Word w : words) sum += Math.max(0, w.getConfidence()); // 0..100
            avgConf = sum * 1.0 / words.size();
        }
        return new ResultModel(text == null ? "" : text.trim(), avgConf);
    }
}
