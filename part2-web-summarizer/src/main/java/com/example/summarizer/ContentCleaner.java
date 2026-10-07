package com.example.summarizer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.Comparator;
import java.util.regex.Pattern;

public final class ContentCleaner {
    private static final Pattern HIDDEN_STYLE = Pattern.compile(
            "(?i)(^|;)\\s*(display\\s*:\\s*none|visibility\\s*:\\s*hidden)\\s*(!important\\s*)?(;|$)");
    private static final Pattern BOILERPLATE = Pattern.compile(
            "(?i)(^|[\\s_-])(sidebar|cookie[-_]?(banner|notice|dialog|popup|consent)|"
            + "consent[-_]?(banner|notice|dialog|popup)|onetrust-banner-sdk|"
            + "onetrust-consent-sdk|cc-window)([\\s_-]|$)");

    public String clean(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document document = Jsoup.parse(html);
        document.select("nav, footer, aside, script, style, noscript, template, "
                + "iframe, svg, form, [hidden], [aria-hidden=true], [role=navigation], "
                + "[role=banner], [role=contentinfo], [role=complementary]").remove();
        // Site headers are boilerplate, but article/main headers often contain the title and date.
        for (Element header : document.select("header")) {
            if (header.parents().stream().noneMatch(parent -> parent.is("article, main, [role=main]"))) {
                header.remove();
            }
        }
        for (Element element : document.select("[style]")) {
            if (HIDDEN_STYLE.matcher(element.attr("style")).find()) {
                element.remove();
            }
        }
        for (Element element : document.select("[id], [class]")) {
            String labels = element.id() + " " + element.className();
            if (BOILERPLATE.matcher(labels).find()) {
                element.remove();
            }
        }

        // Ignore tiny teaser articles when a full main/body is available.
        for (String selector : new String[]{"article", "main, [role=main]"}) {
            String preferred = document.select(selector).stream()
                    .map(Element::text)
                    .max(Comparator.comparingInt(String::length))
                    .orElse("");
            if (hasMeaningfulContent(preferred)) {
                return preferred;
            }
        }
        return document.body().text();
    }

    /** A deliberately simple heuristic, not a reliable JavaScript detector. */
    public boolean hasMeaningfulContent(String text) {
        return SummaryGuardrail.countWords(text) >= 50
                && text.codePoints().filter(Character::isLetter).limit(200).count() >= 200;
    }
}
