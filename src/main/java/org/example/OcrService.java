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

                String protocol = url.getProtocol();
                if ("file".equalsIgnoreCase(protocol)) {
                    Path tessdataDir = Paths.get(url.toURI());
                    if (!Files.isDirectory(tessdataDir)) {
                        throw new IllegalStateException("'tessdata' n'est pas un dossier: " + tessdataDir);
                    }
                    //
                    tesseract.setDatapath(tessdataDir.toAbsolutePath().toString());

                } else {
                    Path tempRoot = Files.createTempDirectory("tess4j_");
                    Path tessDir  = tempRoot.resolve("tessdata");
                    Files.createDirectories(tessDir);

                    copyResourceFromClasspath("tessdata/eng.traineddata", tessDir.resolve("eng.traineddata"));
                    copyResourceFromClasspath("tessdata/fra.traineddata", tessDir.resolve("fra.traineddata"));

                    tesseract.setDatapath(tessDir.toAbsolutePath().toString());

                    // Nettoyage
                    tempRoot.toFile().deleteOnExit();
                    tessDir.toFile().deleteOnExit();
                    tessDir.resolve("eng.traineddata").toFile().deleteOnExit();
                    tessDir.resolve("fra.traineddata").toFile().deleteOnExit();
                }
            }

            // texte noir sur fond blanc
            tesseract.setLanguage("eng+fra");
            tesseract.setOcrEngineMode(ITessAPI.TessOcrEngineMode.OEM_LSTM_ONLY);
            tesseract.setPageSegMode(ITessAPI.TessPageSegMode.PSM_SPARSE_TEXT);

            tesseract.setVariable("user_defined_dpi", "300");
            tesseract.setVariable("preserve_interword_spaces", "1");
            tesseract.setVariable("tessedit_char_blacklist", "|~`^'\"");

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
            for (Word w : words) sum += Math.max(0, w.getConfidence());
            avgConf = sum * 1.0 / words.size();
        }
        return new ResultModel(text == null ? "" : text.trim(), avgConf);
    }
}
