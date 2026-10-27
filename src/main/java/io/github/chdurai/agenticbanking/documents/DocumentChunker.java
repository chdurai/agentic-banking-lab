package io.github.chdurai.agenticbanking.documents;

import java.util.ArrayList;
import java.util.List;

/** Splits long text on paragraph boundaries into bounded chunks for map-reduce summarization. */
final class DocumentChunker {

    private DocumentChunker() {}

    static List<String> split(String text, int maxChunkChars) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            String p = paragraph;
            while (p.length() > maxChunkChars) {
                flush(chunks, current);
                chunks.add(p.substring(0, maxChunkChars));
                p = p.substring(maxChunkChars);
            }
            if (current.length() + p.length() + 2 > maxChunkChars) {
                flush(chunks, current);
            }
            current.append(p).append("\n\n");
        }
        flush(chunks, current);
        return chunks;
    }

    private static void flush(List<String> chunks, StringBuilder current) {
        if (!current.isEmpty()) {
            chunks.add(current.toString().strip());
            current.setLength(0);
        }
    }
}
