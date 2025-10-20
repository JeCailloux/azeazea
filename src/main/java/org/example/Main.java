package org.example;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

public class Main {

    /**
     * Usage:
     *   java -jar target/ocr-simple-1.0-jar-with-dependencies.jar C:\Users\basti\Desktop\ticket-de-caisse.png
     */
    public static void main(String[] args) {
        // ----- Console UTF-8 (accents) -----
        try {
            System.setOut(new PrintStream(new java.io.FileOutputStream(FileDescriptor.out), true, "UTF-8"));
            System.setErr(new PrintStream(new java.io.FileOutputStream(FileDescriptor.err), true, "UTF-8"));
        } catch (Exception ignore) {}

        // ----- Args -----
        if (args.length != 1) {
            System.err.println("Usage: java -jar target/ocr-simple-1.0-jar-with-dependencies.jar C:\\Users\\basti\\Desktop\\ticket-de-caisse.png");
            System.exit(1);
        }

        String imagePath = args[0];
        File input = new File(imagePath);
        if (!input.exists() || !input.isFile()) {
            System.err.println("Erreur: fichier introuvable: " + input.getAbsolutePath());
            System.exit(2);
        }

        String lower = imagePath.toLowerCase();
        if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".bmp") || lower.endsWith(".tif") || lower.endsWith(".tiff"))) {
            System.err.println("Erreur: format d'image non supporté. Formats acceptés: PNG, JPG, JPEG, BMP, TIF, TIFF.");
            System.exit(2);
        }

        try {
            // ----- Lecture -----
            BufferedImage img = ImageIO.read(input);
            if (img == null) {
                System.err.println("Erreur: l'image n'a pas pu être lue (format invalide ou fichier corrompu). Chemin: " + input.getAbsolutePath());
                System.exit(2);
            }

            // ----- Prétraitements (tickets de caisse) -----
            // Pass A : grayscale + upscale x2 + unsharp (souvent meilleur pour LSTM)
            BufferedImage passA = ImagePreprocessor.receiptPassA(img);
            // Pass B : A + contraste + Sauvola (filet de sécurité si fond gris/bruité)
            BufferedImage passB = ImagePreprocessor.receiptPassB(img);

            // ----- OCR -----
            // Ne PAS dépendre de -Dtessdata : OcrService gère automatiquement le tessdata embarqué
            OcrService ocr = new OcrService(System.getProperty("tessdata")); // ok si null

            ResultModel rA = ocr.run(passA);
            ResultModel rB = ocr.run(passB);

            // Choisir la meilleure passe selon la confiance
            ResultModel result = (rB.getConfidence() > rA.getConfidence()) ? rB : rA;

            // ----- Sorties -----
            System.out.println("===== TEXTE OCR =====");
            System.out.println(result.getText());
            System.out.printf("%nConfiance PassA (gray): %.1f%% | PassB (bin): %.1f%%%n", rA.getConfidence(), rB.getConfidence());
            System.out.printf("Confiance retenue      : %.1f%%%n", result.getConfidence());

            Path base = input.toPath();
            Path txtOut = base.resolveSibling(stripExt(base.getFileName().toString()) + "_ocr.txt");
            Path jsonOut = base.resolveSibling(stripExt(base.getFileName().toString()) + "_ocr.json");

            Files.writeString(txtOut, result.getText(), StandardCharsets.UTF_8);

            ObjectMapper om = new ObjectMapper();
            om.writerWithDefaultPrettyPrinter().writeValue(jsonOut.toFile(), result);

            System.out.println("\nFichiers écrits:");
            System.out.println(" - " + txtOut.toAbsolutePath());
            System.out.println(" - " + jsonOut.toAbsolutePath());

        } catch (IOException e) {
            System.err.println("Erreur IO: " + e.getMessage());
            System.exit(3);
        } catch (Exception e) {
            System.err.println("Erreur: " + e.getMessage());
            System.exit(4);
        }
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return (i >= 0) ? name.substring(0, i) : name;
    }
}
