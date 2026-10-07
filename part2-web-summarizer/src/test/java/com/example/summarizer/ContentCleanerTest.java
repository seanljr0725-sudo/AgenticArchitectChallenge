package com.example.summarizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContentCleanerTest {
    static final String ARTICLE = "Research explains how renewable energy reduces emissions and improves air quality. ".repeat(8).strip();
    private final ContentCleaner cleaner = new ContentCleaner();

    @Test
    void preservesArticleAndMainHeadersButRemovesSiteHeader() {
        for (String container : new String[]{"article", "main"}) {
            String html = "<header>site menu</header><" + container + ">"
                    + "<header><h1>Energy findings</h1><time>2026-10-05</time><p>By Alex</p></header>"
                    + "<p>" + ARTICLE + "</p></" + container + ">";
            assertEquals("Energy findings 2026-10-05 By Alex " + ARTICLE, cleaner.clean(html));
        }
    }

    @Test
    void removesObviousInlineHiddenContentWithoutRemovingVisibleContent() {
        String html = "<article><p>" + ARTICLE + "</p>"
                + "<div style='display:none'>hidden one</div>"
                + "<div style='color:red; DISPLAY : NONE !important;'><p>hidden child</p></div>"
                + "<p style='visibility: hidden'>hidden two</p>"
                + "<p style='display:block; visibility:visible'>Visible detail</p></article>";
        assertEquals(ARTICLE + " Visible detail", cleaner.clean(html));
    }

    @Test
    void removesBoilerplateAndPrefersArticle() {
        String html = "<header>header junk</header><nav>navigation junk</nav>"
                + "<main><article><h1>Energy findings</h1><p>" + ARTICLE + "</p>"
                + "<script>script junk</script><style>style junk</style>"
                + "<div class='cookie-banner'>cookie junk</div><aside>sidebar junk</aside>"
                + "<p hidden>hidden junk</p><p aria-hidden='true'>hidden junk</p></article>"
                + "<p>unrelated main junk</p></main><footer>footer junk</footer>";
        assertEquals("Energy findings " + ARTICLE, cleaner.clean(html));
    }

    @Test
    void usesMainWhenArticleIsOnlyATeaserAndBodyWhenNoSemanticContainerExists() {
        assertEquals(ARTICLE, cleaner.clean("<article>Teaser</article><main>" + ARTICLE + "</main>"));
        assertEquals(ARTICLE, cleaner.clean("<div>" + ARTICLE + "</div><div class='sidebar'>junk</div>"));
    }

    @Test
    void keepsArticleTextAboutCookies() {
        String text = "Browser cookies help store user preferences. ".repeat(15).strip();
        assertEquals(text, cleaner.clean("<article class='cookies-guide'>" + text + "</article>"));
    }

    @Test
    void detectsSparseOrNonProseContent() {
        assertFalse(cleaner.hasMeaningfulContent(cleaner.clean("<div id='app'></div><script>load()</script>")));
        assertFalse(cleaner.hasMeaningfulContent("123 ".repeat(100)));
        assertFalse(cleaner.hasMeaningfulContent(null));
        assertTrue(cleaner.hasMeaningfulContent(ARTICLE));
    }
}
