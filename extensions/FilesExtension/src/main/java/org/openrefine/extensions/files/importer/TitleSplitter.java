package org.openrefine.extensions.files.importer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

public class TitleSplitter {

    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.9;

    public static class Piece {
        public int startPage;
        public int endPage;
        public String title = "";

        public int pageCount() {
            return endPage - startPage + 1;
        }
    }

    public static String normalize(String title) {
        if (title == null) return "";
        String s = Normalizer.normalize(title, Normalizer.Form.NFKC);
        s = s.replaceAll("\\s+", "");
        s = s.replaceAll("[\\p{Punct}，。、；：？！“”‘’（）《》〈〉【】〔〕…—·～￥]", "");
        return s.toLowerCase();
    }

    public static double similarity(String a, String b) {
        String x = normalize(a);
        String y = normalize(b);
        if (x.isEmpty() && y.isEmpty()) return 1.0;
        if (x.isEmpty() || y.isEmpty()) return 0.0;
        int dist = levenshtein(x, y);
        return 1.0 - dist / (double) Math.max(x.length(), y.length());
    }

    public static List<Piece> split(List<String> pageTitles) {
        return split(pageTitles, DEFAULT_SIMILARITY_THRESHOLD);
    }

    public static List<Piece> split(List<String> pageTitles, double threshold) {
        List<Piece> pieces = new ArrayList<>();
        if (pageTitles == null || pageTitles.isEmpty()) return pieces;
        Piece current = null;
        for (int i = 0; i < pageTitles.size(); i++) {
            String raw = pageTitles.get(i);
            String rawTitle = raw == null ? "" : raw.trim();
            String norm = normalize(rawTitle);
            boolean newPiece;
            if (current == null) {
                newPiece = true;
            } else {
                String currentNorm = normalize(current.title);
                if (norm.isEmpty() || currentNorm.isEmpty()) {
                    newPiece = false;
                } else {
                    newPiece = similarity(norm, currentNorm) < threshold;
                }
            }
            if (newPiece) {
                current = new Piece();
                current.startPage = i + 1;
                current.endPage = i + 1;
                current.title = rawTitle;
                pieces.add(current);
            } else {
                current.endPage = i + 1;
                if (current.title.isEmpty()) {
                    current.title = rawTitle;
                }
            }
        }
        return pieces;
    }

    public static int levenshtein(String a, String b) {
        int la = a.length();
        int lb = b.length();
        if (la == 0) return lb;
        if (lb == 0) return la;
        int[] prev = new int[lb + 1];
        int[] curr = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= lb; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[lb];
    }
}
