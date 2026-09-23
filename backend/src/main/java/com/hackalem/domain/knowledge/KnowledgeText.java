package com.hackalem.domain.knowledge;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Bounded plain-text parsing. A source is untrusted evidence, never executable instructions. */
public final class KnowledgeText {
    private KnowledgeText() {}
    private static final int CHUNK_CHARS = 2400;
    private static final int OVERLAP = 240;
    private static final Pattern INJECTION = Pattern.compile(
            "(?is)(ignore.{0,60}(instruction|previous|system)|system\\s*prompt|developer\\s*message|"
            + "игнориру[йя].{0,60}(инструкц|правил)|раскрой.{0,40}(секрет|ключ)|"
            + "(add|confirm|подтверд|добавь).{0,50}(cart|корзин)|<\\|(?:system|assistant))");
    private static final Set<String> STOP = Set.of("нужна", "нужно", "нужен", "нужны", "требуется", "требуются", "количество", "количества", "товар", "товары", "сколько", "как", "какие", "какой", "какая", "условия", "условий", "ли", "есть", "для", "при", "про", "расскажи", "мне", "можно", "что", "это", "the", "is", "are", "a", "of", "and", "how", "what", "terms", "purchase", "покупки", "товара", "товаров", "заказа", "заказ", "или", "по", "на", "в", "с", "и", "о", "об", "у", "до", "вы", "вас");
    public record ParsedChunk(int ordinal, int page, String heading, String text, boolean suspicious) {}
    public static List<ParsedChunk> chunks(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 200_000)
            throw new IllegalArgumentException("Document must contain 1–200000 characters");
        List<ParsedChunk> result = new ArrayList<>();
        String[] pages = raw.replace("\r\n", "\n").split("\f", -1);
        for (int page = 0; page < pages.length; page++) {
            String heading = "";
            for (String paragraph : pages[page].split("\n\\s*\n")) {
                String block = paragraph.strip();
                if (block.isBlank()) continue;
                if (block.startsWith("#")) {
                    int newline = block.indexOf('\n');
                    heading = block.substring(0, newline < 0 ? block.length() : newline).replaceFirst("^#+\\s*", "");
                    if (heading.length() > 500) heading = heading.substring(0, 500);
                    if (newline < 0) continue;
                    block = block.substring(newline + 1).strip();
                    if (block.isBlank()) continue;
                }
                for (int start = 0; start < block.length();) {
                    int end = Math.min(start + CHUNK_CHARS, block.length());
                    if (end < block.length()) {
                        int space = block.lastIndexOf(' ', end);
                        if (space > start + CHUNK_CHARS / 2) end = space;
                    }
                    String text = block.substring(start, end).strip();
                    result.add(new ParsedChunk(result.size(), page + 1, heading, text, INJECTION.matcher(text).find()));
                    if (end == block.length()) break;
                    start = end - OVERLAP;
                }
            }
        }
        if (result.isEmpty() || result.size() > 500) throw new IllegalArgumentException("Document has too many chunks");
        return result;
    }
    public static Set<String> tokens(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{L}\\p{N}]+"))
                .filter(word -> !word.isBlank() && !STOP.contains(word)).map(KnowledgeText::stem)
                .filter(word -> word.length() > 1 || word.matches("[0-9]+" )).collect(Collectors.toCollection(LinkedHashSet::new));
    }
    private static String stem(String word) {
        if (word.matches("стоимост.*|стоит|цен[аыуе]|cost")) return "cost";
        if (word.matches("день|дня|дней|дни|day|days")) return "day";
        if (word.matches("достав.*|delivery|shipping|доставить")) return "delivery";
        if (word.matches("оплат.*|платеж.*|платить|payment|pay")) return "payment";
        if (word.matches("миним.*|парт.*|minimum|batch")) return "minimum";
        if (word.matches("возврат.*|вернуть|return.*")) return "returns";
        if (word.matches("самовывоз.*|pickup")) return "pickup";
        if (word.matches("кабел.*|cable.*")) return "cable";
        if (word.matches("сертификат.*|certificate.*")) return "certificate";
        if (word.matches("[а-я]+") && word.length() > 5)
            return word.replaceFirst("(иями|ами|ого|ему|ому|ами|ями|ах|ях|ов|ев|ий|ая|ые|ой|ом|ам|ям|ы|и|а|я|у|ю|е)$", "");
        return word;
    }
    public static boolean asksCatalog(String query) {
        // Delivery words must never bypass authoritative stock/availability lookup.
        if (Pattern.compile("(?iu)(остат[окки]+|наличи|stock|availability)").matcher(query).find()) return true;
        boolean price = Pattern.compile("(?iu)(цен[аыуе]|стоимост|сколько\\s+стоит|price)").matcher(query).find();
        if (!price) return false;
        boolean product = Pattern.compile("(?iu)(товар|артикул|sku|\\b[0-9]{4,}\\b)").matcher(query).find();
        return product || !tokens(query).contains("delivery");
    }
    public static double relevance(Set<String> query, Set<String> content) {
        if (query.isEmpty()) return 0;
        long matched = query.stream().filter(content::contains).count();
        return (double) matched / query.size();
    }
}
