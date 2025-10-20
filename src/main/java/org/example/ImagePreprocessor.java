package org.example;// imports utiles en haut du fichier :
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.awt.image.RescaleOp;

public class ImagePreprocessor {

    // === EXISTANT ===
    public static BufferedImage preprocess(BufferedImage src) {
        // Peut rester pour d’autres usages, mais pour les tickets on utilisera les méthodes ci-dessous.
        BufferedImage gray = toGrayscale(src);
        BufferedImage contrast = boostContrast(gray, 1.15f, -8f);
        return binarizeOtsu(contrast);
    }

    // === NOUVEAU : pipeline tickets ===
    /** Pass A : upscale -> grayscale -> unsharp (pas de binarisation) */
    public static BufferedImage receiptPassA(BufferedImage src) {
        BufferedImage g = toGrayscale(src);
        BufferedImage up = upscale2x(g);
        return unsharp(up);
    }

    /** Pass B : pass A + contraste + seuillage adaptatif Sauvola */
    public static BufferedImage receiptPassB(BufferedImage src) {
        BufferedImage a = receiptPassA(src);
        BufferedImage boosted = boostContrast(a, 1.2f, -10f);
        return sauvola(boosted, 31, 0.34); // fenêtre ~31, k ~ 0.34
    }

    // === Outils ===
    public static BufferedImage toGrayscale(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return gray;
    }

    public static BufferedImage boostContrast(BufferedImage srcGray, float scale, float offset) {
        RescaleOp op = new RescaleOp(scale, offset, null);
        BufferedImage dst = new BufferedImage(srcGray.getWidth(), srcGray.getHeight(), srcGray.getType());
        op.filter(srcGray, dst);
        return dst;
    }

    public static BufferedImage upscale2x(BufferedImage srcGray) {
        int w = srcGray.getWidth() * 2, h = srcGray.getHeight() * 2;
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(srcGray, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    public static BufferedImage unsharp(BufferedImage srcGray) {
        float[] k = {
                0, -1,  0,
                -1,  5, -1,
                0, -1,  0
        };
        ConvolveOp op = new ConvolveOp(new Kernel(3,3,k), ConvolveOp.EDGE_NO_OP, null);
        BufferedImage dst = new BufferedImage(srcGray.getWidth(), srcGray.getHeight(), srcGray.getType());
        op.filter(srcGray, dst);
        return dst;
    }

    // Otsu (déjà présent chez toi)
    public static BufferedImage binarizeOtsu(BufferedImage gray) {
        int w = gray.getWidth(), h = gray.getHeight();
        int[] hist = new int[256];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) hist[gray.getRaster().getSample(x,y,0)]++;
        int total = w*h; double sum=0; for (int t=0;t<256;t++) sum += t*hist[t];
        double sumB=0, varMax=0; int wB=0, threshold=128;
        for (int t=0;t<256;t++) {
            wB += hist[t]; if (wB==0) continue;
            int wF = total - wB; if (wF==0) break;
            sumB += (double)(t*hist[t]);
            double mB=sumB/wB, mF=(sum-sumB)/wF;
            double between = (double)wB*wF*(mB-mF)*(mB-mF);
            if (between>varMax){varMax=between; threshold=t;}
        }
        BufferedImage out = new BufferedImage(w,h,BufferedImage.TYPE_BYTE_BINARY);
        for (int y=0;y<h;y++) for (int x=0;x<w;x++){
            int v = gray.getRaster().getSample(x,y,0);
            out.setRGB(x,y, 0xFF000000 | ((v>threshold)?0xFFFFFF:0x000000));
        }
        return out;
    }

    // Sauvola simple (adaptatif) – très utile quand le fond n’est pas blanc pur
    public static BufferedImage sauvola(BufferedImage gray, int window, double k) {
        int w = gray.getWidth(), h = gray.getHeight();
        BufferedImage out = new BufferedImage(w,h,BufferedImage.TYPE_BYTE_BINARY);

        long[][] S = new long[h+1][w+1];
        long[][] Q = new long[h+1][w+1];
        for (int y=1;y<=h;y++){
            long rs=0, rq=0;
            for (int x=1;x<=w;x++){
                int v = gray.getRaster().getSample(x-1,y-1,0);
                rs += v; rq += (long)v*v;
                S[y][x] = S[y-1][x] + rs;
                Q[y][x] = Q[y-1][x] + rq;
            }
        }
        int r = Math.max(1, window/2);
        for (int y=0;y<h;y++){
            int y0 = Math.max(0,y-r), y1 = Math.min(h-1,y+r);
            for (int x=0;x<w;x++){
                int x0 = Math.max(0,x-r), x1 = Math.min(w-1,x+r);
                int N = (x1-x0+1)*(y1-y0+1);
                long s = area(S,x0,y0,x1,y1);
                long q = area(Q,x0,y0,x1,y1);
                double mean = s*1.0/N;
                double var = Math.max(0, q*1.0/N - mean*mean);
                double std = Math.sqrt(var);
                double R = 128.0;
                double T = mean * (1 + k*((std/R)-1));
                int v = gray.getRaster().getSample(x,y,0);
                out.setRGB(x,y, 0xFF000000 | ((v>T)?0xFFFFFF:0x000000));
            }
        }
        return out;
    }
    private static long area(long[][] I, int x0,int y0,int x1,int y1){
        return I[y1+1][x1+1]-I[y0][x1+1]-I[y1+1][x0]+I[y0][x0];
    }
}
