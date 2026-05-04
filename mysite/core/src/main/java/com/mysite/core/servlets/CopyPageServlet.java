package com.mysite.core.servlets;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;
import javax.servlet.ServletException;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.*;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;

@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/shriram/create-page",
                "sling.servlet.methods=GET"
        }
)
public class CopyPageServlet extends SlingAllMethodsServlet {

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {

        ResourceResolver resolver = request.getResourceResolver();

        try {
            String sourcePath = "/content/shriramfinance/developer-pages/test/invest-template";
            String destParent  = "/content/shriramfinance/fixed-deposit";

            String pageName = getValidatedPageName(request, response);
            if (pageName == null) return;

            String fileId = request.getParameter("fileId");
            if (fileId == null || fileId.trim().isEmpty()) {
                response.getWriter().write("❌ Provide fileId: ?fileId=YOUR_GOOGLE_DOC_ID&name=abc");
                return;
            }

            String destPath = destParent + "/" + pageName;
            Session session  = resolver.adaptTo(Session.class);

            if (!validatePaths(session, sourcePath, destPath, response)) return;

            // ✅ Fetch Google Doc HTML
            String docHtml = fetchGoogleDocHtml(fileId);

            // ✅ Parse into blocks using pure regex (no XML / Jsoup)
            List<Map<String, Object>> blocks = parseDocToBlocks(docHtml);

            copyPage(session, sourcePath, destPath);

            Page newPage = getPage(resolver, destPath, response);
            if (newPage == null) return;

            Resource contentResource = newPage.getContentResource();
            if (contentResource == null) {
                response.getWriter().write("❌ jcr:content not found");
                return;
            }

            updatePageProperties(contentResource);
            updateSections(contentResource, blocks);

            resolver.commit();
            response.getWriter().write("✅ Page created & updated: " + destPath);

        } catch (Exception e) {
            e.printStackTrace();
            response.getWriter().write("❌ Error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════
    // ✅ FETCH GOOGLE DOC HTML
    // ═══════════════════════════════════════
    private String fetchGoogleDocHtml(String fileId) throws IOException {
        String urlStr = "https://docs.google.com/document/d/" + fileId + "/export?format=html";
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(true);
        try (InputStream is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ═══════════════════════════════════════
    // ✅ PARSE HTML → BLOCKS  (pure regex, mirrors your JS logic)
    //    H1  → new block
    //    H2  → new section inside block
    //    rest → appended to current section html
    // ═══════════════════════════════════════
    private List<Map<String, Object>> parseDocToBlocks(String raw) {

        // 1️⃣ Strip everything outside <body>
        String body = extractBody(raw);

        // 2️⃣ Split into top-level tag tokens  e.g. <h1>...</h1>  <p>...</p>  <table>...</table>
        List<String[]> tokens = tokenizeTopLevelTags(body);  // [tag, fullHtml]

        List<Map<String, Object>> result   = new ArrayList<>();
        Map<String, Object>      curBlock  = null;
        Map<String, String>      curSection = null;

        for (String[] token : tokens) {
            String tag     = token[0];   // "h1" / "h2" / "p" / "table" …
            String fullHtml = token[1];  // complete outer html of that element

            if (tag.equals("h1")) {
                if (curBlock != null) result.add(curBlock);
                curBlock = new LinkedHashMap<>();
                curBlock.put("block", stripTags(fullHtml).trim());
                curBlock.put("sections", new ArrayList<Map<String, String>>());
                curSection = null;
                continue;
            }

            if (tag.equals("h2")) {
                if (curBlock == null) continue;
                curSection = new LinkedHashMap<>();
                curSection.put("title", cleanLeafSpans(getInnerHtml(fullHtml, "h2")));
                curSection.put("html", "");
                getSections(curBlock).add(curSection);
                continue;
            }

            // Any other tag — content
            if (curBlock != null && curSection == null) {
                curSection = new LinkedHashMap<>();
                curSection.put("title", "");
                curSection.put("html", "");
                getSections(curBlock).add(curSection);
            }

            if (curSection != null) {
                curSection.put("html", curSection.get("html") + fullHtml);
            }
        }

        if (curBlock != null) result.add(curBlock);
        return result;
    }

    // ─── extract <body>…</body> content ───────────────────────────────────────
    private String extractBody(String html) {
        Matcher m = Pattern.compile("(?is)<body[^>]*>(.*?)</body>").matcher(html);
        return m.find() ? m.group(1) : html;
    }

    // ─── split body into top-level tag tokens ────────────────────────────────
    // Returns list of [tagName, outerHtml]
    private List<String[]> tokenizeTopLevelTags(String body) {
        List<String[]> tokens = new ArrayList<>();
        // matches  <tagName ...> ... </tagName>   (non-greedy, case-insensitive)
        Pattern p = Pattern.compile("(?is)<(h1|h2|h3|h4|h5|h6|p|ul|ol|table|div|hr|blockquote)(\\s[^>]*)?>.*?</\\1>");
        Matcher m = p.matcher(body);
        while (m.find()) {
            tokens.add(new String[]{ m.group(1).toLowerCase(), m.group(0) });
        }
        return tokens;
    }

    // ─── get inner html between opening and closing tag ──────────────────────
    private String getInnerHtml(String outerHtml, String tag) {
        Matcher m = Pattern.compile("(?is)<" + tag + "[^>]*>(.*?)</" + tag + ">").matcher(outerHtml);
        return m.find() ? m.group(1) : outerHtml;
    }

    // ─── strip all html tags, keep text only ─────────────────────────────────
    private String stripTags(String html) {
        return html.replaceAll("<[^>]+>", "");
    }

    // ─── remove Google Docs duplicate-aggregate spans ─────────────────────────
    // Keeps only leaf spans (no child <span>) and deduplicates adjacent identical text.
    // Google Docs wraps styled runs in an outer span that re-contains inner spans + plain-text copy.
    private String cleanLeafSpans(String html) {
        Pattern leafPat = Pattern.compile("(?is)<span([^>]*)>(?!\\s*<span)([^<]*(?:<(?!/?span)[^<]*)*)</span>");
        Matcher m = leafPat.matcher(html);
        StringBuilder result = new StringBuilder();
        String lastText = null;
        while (m.find()) {
            String text = m.group(2).replaceAll("&nbsp;", " ").trim();
            if (text.isEmpty()) continue;
            if (text.equals(lastText)) continue;  // drop duplicate
            result.append("<span").append(m.group(1)).append(">").append(m.group(2)).append("</span>");
            lastText = text;
        }
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> getSections(Map<String, Object> block) {
        return (List<Map<String, String>>) block.get("sections");
    }

    // ═══════════════════════════════════════
    // ✅ UPDATE SECTIONS
    // ═══════════════════════════════════════
    private void updateSections(Resource contentResource, List<Map<String, Object>> blocks)
            throws RepositoryException, PersistenceException {

        Map<String, List<Map<String, String>>> blockMap = new LinkedHashMap<>();
        for (Map<String, Object> block : blocks) {
            String name = (String) block.get("block");
            if (name.endsWith(":")) name = name.substring(0, name.length() - 1);
            blockMap.put(name, getSections(block));
        }

        Resource root = contentResource.getChild("root");
        if (root == null) return;

        for (Resource section : root.getChildren()) {

            ValueMap vm = section.getValueMap();
            String sectionName = vm.get("name", String.class);
            if (sectionName == null) continue;

            List<Map<String, String>> sections = blockMap.get(sectionName);
            if (sections == null || sections.isEmpty()) continue;

            String title = sections.get(0).get("title");
            String html  = sections.get(0).get("html");

            switch (sectionName) {
                case "DematSectionTitle":
                    updateDematSection(section, title);
                    break;
                case "DematSectionDescription":
                    updateDematSectionDescription(section, html);
                    break;
                case "DematAccount":
                    updateDematAccount(section, title, html);
                    break;
                case "TradingAccount":
                    updateTradingAccount(section, title, html);
                    break;
                case "DemateTradingDifference":
                    updateDemateTradingDifference(section, title, html);
                    break;
                case "DemateTradingDifferenceDescription":
                    updateDemateTradingDifferenceDescription(section, html);
                    break;
                case "DematTradingProcessExplained":
                    updateDematTradingProcessExplained(section, title, html);
                    break;
                case "DematTradingProcessDescription":
                    updateDematTradingProcessDescription(section, html);
                    break;
                case "DeliveryIntradayTrades":
                    updateDeliveryIntradayTrades(section, title, html);
                    break;
                case "DeliveryIntradayTradesDescription":
                    updateDeliveryIntradayTradesDescription(section, html);
                    break;
                case "RegulatoryFramework":
                    updateTitleWithHtml(section, title, html);
                    break;
                case "CostAndMaintenance":
                    updateTitleWithHtml(section, title, html);
                    break;
                case "NsdlCdslDifference":
                    updateNsdlCdslDifference(section, title, html);
                    break;
                case "NsdlCdslDifferenceDescription":
                    updateDematSectionDescription(section, html);
                    break;
                case "BenefitsOfDematTrading":
                    updateBenefitsOfDematTrading(section, title, html);
                    break;
                case "FAQs":
                    updateFAQs(section, title, html);
                    break;
                case "Disclaimer":
                    updateDisclaimer(section, html);
                    break;
                default:
                    break;
            }
        }
    }

    // ═══════════════════════════════════════
    // ✅ SECTION UPDATERS
    // ═══════════════════════════════════════
    private void updateDematSection(Resource section, String title) {
        Iterator<Resource> it = section.getChildren().iterator();
        if (!it.hasNext()) return;
        ModifiableValueMap props = it.next().adaptTo(ModifiableValueMap.class);
        if (props != null) props.put("text", buildH1(title));
    }

    // Converts clean span-based title into <h1> with <strong> for bold spans (<b> inside span).
    private String buildH1(String title) {
        StringBuilder content = new StringBuilder();
        Matcher spanMatcher = Pattern.compile("(?is)<span[^>]*>(.*?)</span>").matcher(title);
        while (spanMatcher.find()) {
            String spanInner = spanMatcher.group(1);
            String text = stripTags(spanInner).replaceAll("&nbsp;", " ").trim();
            if (text.isEmpty()) continue;
            if (Pattern.compile("(?is)<b>").matcher(spanInner).find()) {
                content.append(" <strong>").append(text).append("</strong>");
            } else {
                content.append(" ").append(text);
            }
        }
        return "<h1>" + content.toString().trim() + "</h1>";
    }

    private void updateDematSectionDescription(Resource section, String html) {
        Iterator<Resource> it = section.getChildren().iterator();
        if (!it.hasNext()) return;
        ModifiableValueMap props = it.next().adaptTo(ModifiableValueMap.class);
        if (props != null) props.put("text", html);
    }

    private void updateDematAccount(Resource section, String title, String html)
            throws RepositoryException, PersistenceException {

        List<Resource> children = new ArrayList<>();
        section.getChildren().forEach(children::add);

        // First child → title
        if (!children.isEmpty()) {
            ModifiableValueMap props = children.get(0).adaptTo(ModifiableValueMap.class);
            if (props != null) props.put("text", title);
        }

        if (children.size() <= 1) return;

        // Second child → inner paragraph nodes
        Resource secondChild = children.get(1);
        List<Resource> innerList = new ArrayList<>();
        secondChild.getChildren().forEach(innerList::add);

        List<String> paragraphs = extractParagraphs(html);

        // Delete extra nodes if there are more inner nodes than paragraphs
        if (innerList.size() > paragraphs.size()) {
            for (int i = innerList.size() - 1; i >= paragraphs.size(); i--) {
                secondChild.getResourceResolver().delete(innerList.get(i));
                innerList.remove(i);
            }
        }

        // Create additional nodes if there are fewer inner nodes than paragraphs
        if (innerList.size() < paragraphs.size()) {
            for (int i = innerList.size(); i < paragraphs.size(); i++) {
                Resource newChild = secondChild.getChild("item_" + (i + 1));
                if (newChild == null) {
                    newChild = secondChild.getResourceResolver().create(
                        secondChild,
                        "item_" + (i + 1),
                        new HashMap<>()
                    );
                    innerList.add(newChild);
                }
            }
        }

        // Update all inner nodes with paragraph content
        for (int i = 0; i < paragraphs.size(); i++) {
            if (i >= innerList.size()) break;
            ModifiableValueMap props = innerList.get(i).adaptTo(ModifiableValueMap.class);
            if (props != null) props.put("content_text", paragraphs.get(i));
        }
    }

    private void updateTradingAccount(Resource section, String title, String html)
            throws RepositoryException, PersistenceException {

        List<Resource> children = new ArrayList<>();
        section.getChildren().forEach(children::add);

        if (children.isEmpty()) return;

        // Single node → put h2 title + all html content into one text field
        Resource firstChild = children.get(0);
        ModifiableValueMap props = firstChild.adaptTo(ModifiableValueMap.class);
        
        if (props != null) {
            String content = "<h2>" + title + "</h2>" + html;
            props.put("text", content);
        }
    }

    private void updateDemateTradingDifference(Resource section, String title, String html)
            throws RepositoryException, PersistenceException {

        List<Resource> children = new ArrayList<>();
        section.getChildren().forEach(children::add);

        if (children.isEmpty()) return;

        // Extract title
        String titleText = stripTags(title).trim();
        
        // Extract p tags BEFORE table and convert table to nested ol
        String[] extracted = extractParagraphsBeforeTable(html);
        String pContent = extracted[0];
        String tableContent = extracted[1];
        
        // ─────────────────────────────────────────────
        // 1️⃣ First child → title
        // ─────────────────────────────────────────────
        if (children.size() > 0) {
            ModifiableValueMap props = children.get(0).adaptTo(ModifiableValueMap.class);
            if (props != null) {
                props.put("text", titleText);
            }
        }
        
        // ─────────────────────────────────────────────
        // 2️⃣ Second child → text (description p tags only)
        // ─────────────────────────────────────────────
        if (children.size() > 1) {
            ModifiableValueMap props = children.get(1).adaptTo(ModifiableValueMap.class);
            if (props != null) {
                props.put("text", pContent);
            }
        }
        
        // ─────────────────────────────────────────────
        // 3️⃣ Third child (data_table) → nested ol structure
        // ─────────────────────────────────────────────
        if (children.size() > 2) {
            Resource dataTableNode = children.get(2);
            ModifiableValueMap props = dataTableNode.adaptTo(ModifiableValueMap.class);
            
            if (props != null) {
                props.put("text", tableContent);
            }
        }
    }

    private void updateDemateTradingDifferenceDescription(Resource section, String html) {
        Iterator<Resource> it = section.getChildren().iterator();
        if (!it.hasNext()) return;
        ModifiableValueMap props = it.next().adaptTo(ModifiableValueMap.class);
        if (props != null) props.put("text", html);
    }

    private void updateDematTradingProcessExplained(Resource section, String title, String html)
            throws RepositoryException, PersistenceException {

        List<Resource> children = new ArrayList<>();
        section.getChildren().forEach(children::add);

        if (children.isEmpty()) return;

        // ─────────────────────────────────────────────
        // 1️⃣ First child → title node
        // ─────────────────────────────────────────────
        String titleText = stripTags(title).trim();
        if (children.size() > 0) {
            ModifiableValueMap props = children.get(0).adaptTo(ModifiableValueMap.class);
            if (props != null) {
                props.put("text", titleText);
            }
        }

        if (children.size() <= 1) return;

        // ─────────────────────────────────────────────
        // 2️⃣ Second child (block) → extract ol li items
        // ─────────────────────────────────────────────
        Resource blockNode = children.get(1);
        List<Resource> blockChildren = new ArrayList<>();
        blockNode.getChildren().forEach(blockChildren::add);

        // Extract ol li items
        List<String> listItems = extractOlListItems(html);

        // Delete extra nodes if there are more than list items
        if (blockChildren.size() > listItems.size()) {
            for (int i = blockChildren.size() - 1; i >= listItems.size(); i--) {
                blockNode.getResourceResolver().delete(blockChildren.get(i));
                blockChildren.remove(i);
            }
        }

        // Create new nodes if there are fewer than list items
        if (blockChildren.size() < listItems.size()) {
            for (int i = blockChildren.size(); i < listItems.size(); i++) {
                Resource newChild = blockNode.getResourceResolver().create(
                    blockNode,
                    "item_" + (i + 1),
                    new HashMap<>()
                );
                blockChildren.add(newChild);
            }
        }

        // Update all block children with list item content and step count
        for (int i = 0; i < listItems.size(); i++) {
            ModifiableValueMap props = blockChildren.get(i).adaptTo(ModifiableValueMap.class);
            if (props != null) {
                props.put("description", listItems.get(i));
                props.put("step_count", i + 1);  // 1, 2, 3, ...
            }
        }
    }

    private void updateDematTradingProcessDescription(Resource section, String html) {
        Iterator<Resource> it = section.getChildren().iterator();
        if (!it.hasNext()) return;
        ModifiableValueMap props = it.next().adaptTo(ModifiableValueMap.class);
        if (props != null) props.put("text", html);
    }

    private void updateDeliveryIntradayTrades(Resource section, String title, String html)
            throws RepositoryException, PersistenceException {

        List<Resource> children = new ArrayList<>();
        section.getChildren().forEach(children::add);

        // ── Extract first <p> as intro description ────────────────────────────
        String firstP = "";
        Matcher firstPMatcher = Pattern.compile("(?is)<p[^>]*>(.*?)</p>").matcher(html);
        if (firstPMatcher.find()) {
            firstP = "<p>" + stripTags(firstPMatcher.group(1)).replaceAll("&nbsp;", " ").trim() + "</p>";
        }

        // ── First child → <h2>title</h2> + first <p> ─────────────────────────
        if (!children.isEmpty()) {
            String titleText = stripTags(title).trim();
            String combined = "<h2>" + titleText + "</h2>" + firstP;
            ModifiableValueMap props = children.get(0).adaptTo(ModifiableValueMap.class);
            if (props != null) props.put("text", combined);
        }

        if (children.size() <= 1) return;

        // ── Parse <h4>+<p> pairs into item content ────────────────────────────
        List<String> pairs = extractH4ParagraphPairs(html);

        // ── Second child → inner children matching pair count ─────────────────
        Resource secondChild = children.get(1);
        List<Resource> innerList = new ArrayList<>();
        secondChild.getChildren().forEach(innerList::add);

        // Delete extra nodes
        if (innerList.size() > pairs.size()) {
            for (int i = innerList.size() - 1; i >= pairs.size(); i--) {
                secondChild.getResourceResolver().delete(innerList.get(i));
                innerList.remove(i);
            }
        }

        // Create missing nodes
        if (innerList.size() < pairs.size()) {
            for (int i = innerList.size(); i < pairs.size(); i++) {
                Resource newChild = secondChild.getResourceResolver().create(
                    secondChild,
                    "item_" + (i + 1),
                    new HashMap<>()
                );
                innerList.add(newChild);
            }
        }

        // Update each inner node with its h4+p pair content
        for (int i = 0; i < pairs.size(); i++) {
            if (i >= innerList.size()) break;
            ModifiableValueMap props = innerList.get(i).adaptTo(ModifiableValueMap.class);
            if (props != null) props.put("text", pairs.get(i));
        }
    }

    // Parses h4+p pairs from html (skips the first <p> which goes to first child).
    // Each pair → "<p><strong>h4text</strong></p><p>ptext</p>"
    private List<String> extractH4ParagraphPairs(String html) {
        List<String> pairs = new ArrayList<>();
        Pattern pat = Pattern.compile("(?is)<(h4|p)(\\s[^>]*)?>.*?</\\1>");
        Matcher m = pat.matcher(html);

        List<String[]> tokens = new ArrayList<>();
        boolean skippedFirstP = false;
        while (m.find()) {
            String tag = m.group(1).toLowerCase();
            if (tag.equals("p") && !skippedFirstP) {
                skippedFirstP = true;
                continue;
            }
            tokens.add(new String[]{ tag, m.group(0) });
        }

        int i = 0;
        while (i < tokens.size()) {
            if (tokens.get(i)[0].equals("h4")) {
                String h4Text = stripTags(tokens.get(i)[1]).trim();
                String pText = "";
                if (i + 1 < tokens.size() && tokens.get(i + 1)[0].equals("p")) {
                    pText = stripTags(tokens.get(i + 1)[1]).replaceAll("&nbsp;", " ").trim();
                    i += 2;
                } else {
                    i++;
                }
                pairs.add("<p><strong>" + h4Text + "</strong></p><p>" + pText + "</p>");
            } else {
                i++;
            }
        }
        return pairs;
    }

    private void updateDeliveryIntradayTradesDescription(Resource section, String html) {
        Iterator<Resource> it = section.getChildren().iterator();
        if (!it.hasNext()) return;
        ModifiableValueMap props = it.next().adaptTo(ModifiableValueMap.class);
        if (props != null) props.put("text", html);
    }

    private void updateTitleWithHtml(Resource section, String title, String html) {
        Iterator<Resource> it = section.getChildren().iterator();
        if (!it.hasNext()) return;
        ModifiableValueMap props = it.next().adaptTo(ModifiableValueMap.class);
        if (props != null) {
            String combined = "<h2>" + stripTags(title).trim() + "</h2>" + stripSpanTags(html);
            props.put("text", combined);
        }
    }

    private String stripSpanTags(String html) {
        return html.replaceAll("(?i)</?span[^>]*>", "");
    }

    private void updateDisclaimer(Resource section, String html) {
        Iterator<Resource> it = section.getChildren().iterator();
        if (!it.hasNext()) return;
        ModifiableValueMap props = it.next().adaptTo(ModifiableValueMap.class);
        if (props == null) return;

        // Strip spans, then extract first non-empty <p> content
        String cleaned = stripSpanTags(html).replaceAll("&nbsp;", " ");
        String pContent = "";
        Matcher pMatcher = Pattern.compile("(?is)<p[^>]*>(.*?)</p>").matcher(cleaned);
        while (pMatcher.find()) {
            String text = pMatcher.group(1).replaceAll("(?i)Disclaimer:\\s*", "").trim();
            if (!text.isEmpty()) {
                pContent = "<p>" + text + "</p>";
                break;
            }
        }

        props.put("text", "<h3><strong>Disclaimer</strong></h3>" + pContent);
    }

    private void updateBenefitsOfDematTrading(Resource section, String title, String html)
            throws RepositoryException, PersistenceException {

        List<Resource> children = new ArrayList<>();
        section.getChildren().forEach(children::add);

        // First child → <h2>title</h2> + first <p> if present
        if (!children.isEmpty()) {
            String titleText = "<h2>" + stripTags(title).trim() + "</h2>";
            Matcher firstPMatcher = Pattern.compile("(?is)<p[^>]*>(.*?)</p>").matcher(html);
            if (firstPMatcher.find()) {
                String pText = stripTags(firstPMatcher.group(1)).replaceAll("&nbsp;", " ").trim();
                if (!pText.isEmpty()) titleText += "<p>" + pText + "</p>";
            }
            ModifiableValueMap props = children.get(0).adaptTo(ModifiableValueMap.class);
            if (props != null) props.put("text", titleText);
        }

        if (children.size() <= 1) return;

        // Second child → inner nodes, one per <li> item
        Resource secondChild = children.get(1);
        List<Resource> innerList = new ArrayList<>();
        secondChild.getChildren().forEach(innerList::add);

        List<String> liItems = extractUlListItems(html);

        // Delete extra nodes
        if (innerList.size() > liItems.size()) {
            for (int i = innerList.size() - 1; i >= liItems.size(); i--) {
                secondChild.getResourceResolver().delete(innerList.get(i));
                innerList.remove(i);
            }
        }

        // Create missing nodes
        if (innerList.size() < liItems.size()) {
            for (int i = innerList.size(); i < liItems.size(); i++) {
                Resource newChild = secondChild.getResourceResolver().create(
                    secondChild,
                    "item_" + (i + 1),
                    new HashMap<>()
                );
                innerList.add(newChild);
            }
        }

        // Each inner node → content = <p>li text</p>
        for (int i = 0; i < liItems.size(); i++) {
            if (i >= innerList.size()) break;
            ModifiableValueMap props = innerList.get(i).adaptTo(ModifiableValueMap.class);
            if (props != null) props.put("content", "<p>" + liItems.get(i) + "</p>");
        }
    }

    private void updateFAQs(Resource section, String title, String html)
            throws RepositoryException, PersistenceException {

        List<Resource> children = new ArrayList<>();
        section.getChildren().forEach(children::add);

        // First child → title
        if (!children.isEmpty()) {
            ModifiableValueMap props = children.get(0).adaptTo(ModifiableValueMap.class);
            if (props != null) props.put("text", stripTags(title).trim());
        }

        if (children.size() <= 1) return;

        // Second child → inner accordion_item nodes, one per h4+p pair
        Resource secondChild = children.get(1);
        List<Resource> innerList = new ArrayList<>();
        secondChild.getChildren().forEach(innerList::add);

        List<String[]> faqPairs = extractFaqPairs(html);

        // Delete extra nodes
        if (innerList.size() > faqPairs.size()) {
            for (int i = innerList.size() - 1; i >= faqPairs.size(); i--) {
                secondChild.getResourceResolver().delete(innerList.get(i));
                innerList.remove(i);
            }
        }

        // Create missing nodes with mandatory metadata
        if (innerList.size() < faqPairs.size()) {
            for (int i = innerList.size(); i < faqPairs.size(); i++) {
                Map<String, Object> initProps = new HashMap<>();
                initProps.put("aueComponentId", "accordion-item");
                initProps.put("model", "accordion-item");
                initProps.put("modelFields", new String[]{"summary@text", "text@richtext"});
                initProps.put("name", "Accordion Item");
                initProps.put("sling:resourceType", "core/franklin/components/block/v1/block/item");
                Resource newChild = secondChild.getResourceResolver().create(
                    secondChild,
                    "item_" + (i + 1),
                    initProps
                );
                innerList.add(newChild);
            }
        }

        // Update each accordion item: summary = question, text = answer
        for (int i = 0; i < faqPairs.size(); i++) {
            if (i >= innerList.size()) break;
            ModifiableValueMap props = innerList.get(i).adaptTo(ModifiableValueMap.class);
            if (props != null) {
                props.put("summary", faqPairs.get(i)[0]);
                props.put("text", faqPairs.get(i)[1]);
            }
        }
    }

    // Extracts [question, answer] pairs: h4 → question, following non-empty p(s) → answer
    private List<String[]> extractFaqPairs(String html) {
        List<String[]> pairs = new ArrayList<>();
        Pattern pat = Pattern.compile("(?is)<(h4|p)(\\s[^>]*)?>.*?</\\1>");
        Matcher m = pat.matcher(html);

        List<String[]> tokens = new ArrayList<>();
        while (m.find()) {
            tokens.add(new String[]{ m.group(1).toLowerCase(), m.group(0) });
        }

        int i = 0;
        while (i < tokens.size()) {
            if (tokens.get(i)[0].equals("h4")) {
                String question = stripTags(tokens.get(i)[1]).trim();
                StringBuilder answer = new StringBuilder();
                i++;
                // Collect all following non-empty <p> tags as the answer
                while (i < tokens.size() && tokens.get(i)[0].equals("p")) {
                    String pText = stripTags(tokens.get(i)[1]).replaceAll("&nbsp;", " ").trim();
                    if (!pText.isEmpty()) {
                        answer.append("<p>").append(pText).append("</p>");
                    }
                    i++;
                }
                if (!question.isEmpty()) {
                    pairs.add(new String[]{ question, answer.toString() });
                }
            } else {
                i++;
            }
        }
        return pairs;
    }

    private void updateNsdlCdslDifference(Resource section, String title, String html) {
        List<Resource> children = new ArrayList<>();
        section.getChildren().forEach(children::add);

        // First child → title
        if (!children.isEmpty()) {
            ModifiableValueMap props = children.get(0).adaptTo(ModifiableValueMap.class);
            if (props != null) props.put("text", stripTags(title).trim());
        }

        // Second child → converted table (reuse extractParagraphsBeforeTable)
        if (children.size() > 1) {
            String tableContent = extractParagraphsBeforeTable(html)[1];
            ModifiableValueMap props = children.get(1).adaptTo(ModifiableValueMap.class);
            if (props != null) props.put("text", tableContent);
        }
    }

    // ═══════════════════════════════════════
    // ✅ HELPERS
    // ═══════════════════════════════════════
    private String getValidatedPageName(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws IOException {
        String pageName = request.getParameter("name");
        if (pageName == null || pageName.trim().isEmpty()) {
            response.getWriter().write("❌ Provide name: ?name=abc");
            return null;
        }
        pageName = pageName.toLowerCase().replaceAll("[^a-z]", "");
        if (pageName.isEmpty()) pageName = "page";
        return pageName;
    }

    private boolean validatePaths(Session session, String sourcePath, String destPath,
                                  SlingHttpServletResponse response) throws Exception {
        if (!session.nodeExists(sourcePath)) {
            response.getWriter().write("❌ Source page not found");
            return false;
        }
        if (session.nodeExists(destPath)) {
            response.getWriter().write("❌ Page already exists: " + destPath);
            return false;
        }
        return true;
    }

    private void copyPage(Session session, String sourcePath, String destPath) throws Exception {
        session.getWorkspace().copy(sourcePath, destPath);
        session.save();
    }

    private Page getPage(ResourceResolver resolver, String path, SlingHttpServletResponse response)
            throws IOException {
        PageManager pageManager = resolver.adaptTo(PageManager.class);
        Page page = pageManager.getPage(path);
        if (page == null) response.getWriter().write("❌ Unable to fetch new page");
        return page;
    }

    private void updatePageProperties(Resource contentResource) {
        ModifiableValueMap props = contentResource.adaptTo(ModifiableValueMap.class);
        if (props != null) {
            props.put("jcr:title", "Created via Servlet");
            props.put("description", "Auto generated page");
        }
    }

    private List<String> extractParagraphs(String html) {
        List<String> paragraphs = new ArrayList<>();
        Matcher m = Pattern.compile("(?is)<p[^>]*>(.*?)</p>").matcher(html);
        while (m.find()) {
            paragraphs.add("<p>" + m.group(1) + "</p>");
        }
        return paragraphs;
    }

    private String[] extractParagraphsBeforeTable(String html) {

        // ═══════════════════════════════════════
        // ✅ STEP 1: Extract <p> tags before <table>
        // ═══════════════════════════════════════
        String introHtml = "";

        int tableIndex = html.toLowerCase().indexOf("<table");
        if (tableIndex > 0) {
            String beforeTable = html.substring(0, tableIndex);
            // collect all non-empty <p> tags before table
            StringBuilder pTags = new StringBuilder();
            Matcher pMatcher = Pattern.compile("(?is)<p[^>]*>(.*?)</p>").matcher(beforeTable);
            while (pMatcher.find()) {
                String content = stripTags(pMatcher.group(1)).trim();
                if (!content.isEmpty()) {
                    pTags.append("<p>").append(pMatcher.group(1)).append("</p>");
                }
            }
            introHtml = pTags.toString();
        }

        // ═══════════════════════════════════════
        // ✅ STEP 2: Extract <table> and convert to nested <ol>
        // ═══════════════════════════════════════
        String convertedTable = "";

        Matcher tableMatcher = Pattern.compile("(?is)<table[^>]*>(.*?)</table>").matcher(html);
        if (tableMatcher.find()) {
            String tableContent = tableMatcher.group(1);

            // Extract all <tr> rows (from thead + tbody)
            List<List<String>> rows = new ArrayList<>();
            Matcher rowMatcher = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>").matcher(tableContent);

            while (rowMatcher.find()) {
                String rowHtml = rowMatcher.group(1);
                List<String> cells = new ArrayList<>();

                // Extract <td> or <th> cells
                Matcher cellMatcher = Pattern.compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>").matcher(rowHtml);
                while (cellMatcher.find()) {
                    // strip inner tags, keep text only
                    String cellText = stripTags(cellMatcher.group(1)).trim();
                    cells.add(cellText);
                }

                if (!cells.isEmpty()) {
                    rows.add(cells);
                }
            }

            // ✅ STEP 3: Transpose rows → columns
            // rows.get(0) = header row  ["Feature", "Demat Account", "Trading Account"]
            // rows.get(1..n) = data rows
            if (!rows.isEmpty()) {
                int colCount = rows.get(0).size();
                List<String> headers = rows.get(0);       // first row = column headers
                List<List<String>> dataRows = rows.subList(1, rows.size());

                StringBuilder ol = new StringBuilder("<ol>\n");

                for (int col = 0; col < colCount; col++) {
                    ol.append(" <li><h3>").append(headers.get(col)).append("</h3>\n");
                    ol.append("  <ol>\n");

                    for (List<String> row : dataRows) {
                        if (col < row.size()) {
                            ol.append("   <li>").append(row.get(col)).append("</li>\n");
                        }
                    }

                    ol.append("  </ol></li>\n");
                }

                ol.append("</ol>");
                convertedTable = ol.toString();
            }
        }

        // ✅ Return [introHtml, convertedTable]
        return new String[]{ introHtml, convertedTable };
    }

    private String convertTableToNestedList(String html) {
        // Extract table rows
        List<List<String>> tableData = new ArrayList<>();
        
        // Extract thead rows
        Matcher theadMatcher = Pattern.compile("(?is)<thead>(.*?)</thead>").matcher(html);
        if (theadMatcher.find()) {
            String thead = theadMatcher.group(1);
            List<String> headerRow = extractTableRow(thead);
            if (!headerRow.isEmpty()) {
                tableData.add(headerRow);
            }
        }
        
        // Extract tbody rows
        Matcher tbodyMatcher = Pattern.compile("(?is)<tbody>(.*?)</tbody>").matcher(html);
        if (tbodyMatcher.find()) {
            String tbody = tbodyMatcher.group(1);
            Matcher rowMatcher = Pattern.compile("(?is)<tr>(.*?)</tr>").matcher(tbody);
            while (rowMatcher.find()) {
                List<String> row = extractTableRow(rowMatcher.group(1));
                if (!row.isEmpty()) {
                    tableData.add(row);
                }
            }
        }
        
        // Convert to nested ol format
        if (tableData.isEmpty()) return "";
        
        StringBuilder ol = new StringBuilder("<ol>\n");
        List<String> headers = tableData.get(0);
        
        // For each column (header)
        for (int col = 0; col < headers.size(); col++) {
            String header = stripTags(headers.get(col)).trim();
            ol.append("  <li><h3>").append(header).append("</h3>\n");
            ol.append("    <ol>\n");
            
            // Add all rows from this column
            for (int row = 1; row < tableData.size(); row++) {
                List<String> currentRow = tableData.get(row);
                if (col < currentRow.size()) {
                    String cellContent = stripTags(currentRow.get(col)).trim();
                    ol.append("      <li>").append(cellContent).append("</li>\n");
                }
            }
            
            ol.append("    </ol>\n");
            ol.append("  </li>\n");
        }
        
        ol.append("</ol>\n");
        return ol.toString();
    }

    private List<String> extractTableRow(String rowHtml) {
        List<String> cells = new ArrayList<>();
        Matcher cellMatcher = Pattern.compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>").matcher(rowHtml);
        while (cellMatcher.find()) {
            cells.add(cellMatcher.group(1));
        }
        return cells;
    }

    private List<List<String>> extractTableData(String html) {
        List<List<String>> tableData = new ArrayList<>();
        
        // Extract thead rows
        Matcher theadMatcher = Pattern.compile("(?is)<thead>(.*?)</thead>").matcher(html);
        if (theadMatcher.find()) {
            String thead = theadMatcher.group(1);
            List<String> headerRow = extractTableRow(thead);
            if (!headerRow.isEmpty()) {
                tableData.add(headerRow);
            }
        }
        
        // Extract tbody rows
        Matcher tbodyMatcher = Pattern.compile("(?is)<tbody>(.*?)</tbody>").matcher(html);
        if (tbodyMatcher.find()) {
            String tbody = tbodyMatcher.group(1);
            Matcher rowMatcher = Pattern.compile("(?is)<tr>(.*?)</tr>").matcher(tbody);
            while (rowMatcher.find()) {
                List<String> row = extractTableRow(rowMatcher.group(1));
                if (!row.isEmpty()) {
                    tableData.add(row);
                }
            }
        }
        
        return tableData;
    }

    private List<String> convertTableToListItems(List<List<String>> tableData) {
        List<String> listItems = new ArrayList<>();
        
        if (tableData.isEmpty()) return listItems;
        
        List<String> headers = tableData.get(0);
        
        // For each column (header)
        for (int col = 0; col < headers.size(); col++) {
            StringBuilder item = new StringBuilder();
            String header = stripTags(headers.get(col)).trim();
            
            item.append("<h3>").append(header).append("</h3>");
            item.append("<ol>");
            
            // Add all rows from this column
            for (int row = 1; row < tableData.size(); row++) {
                List<String> currentRow = tableData.get(row);
                if (col < currentRow.size()) {
                    String cellContent = stripTags(currentRow.get(col)).trim();
                    item.append("<li>").append(cellContent).append("</li>");
                }
            }
            
            item.append("</ol>");
            listItems.add(item.toString());
        }
        
        return listItems;
    }

    private String convertTableToNestedOl(List<List<String>> tableData) {
        if (tableData.isEmpty()) return "";
        
        StringBuilder ol = new StringBuilder("<ol>\n");
        List<String> headers = tableData.get(0);
        
        // For each column (header)
        for (int col = 0; col < headers.size(); col++) {
            String header = stripTags(headers.get(col)).trim();
            ol.append(" <li><h3>").append(header).append("</h3>\n");
            ol.append("  <ol>\n");
            
            // Add all rows from this column
            for (int row = 1; row < tableData.size(); row++) {
                List<String> currentRow = tableData.get(row);
                if (col < currentRow.size()) {
                    String cellContent = stripTags(currentRow.get(col)).trim();
                    ol.append("   <li>").append(cellContent).append("</li>\n");
                }
            }
            
            ol.append("  </ol></li>\n");
        }
        
        ol.append("</ol>");
        return ol.toString();
    }

    private List<String> extractUlListItems(String html) {
        List<String> items = new ArrayList<>();
        Matcher ulMatcher = Pattern.compile("(?is)<ul[^>]*>(.*?)</ul>").matcher(html);
        if (ulMatcher.find()) {
            String ulContent = ulMatcher.group(1);
            Matcher liMatcher = Pattern.compile("(?is)<li[^>]*>(.*?)</li>").matcher(ulContent);
            while (liMatcher.find()) {
                String text = stripTags(liMatcher.group(1)).trim();
                if (!text.isEmpty()) items.add(text);
            }
        }
        return items;
    }

    private List<String> extractOlListItems(String html) {
        List<String> items = new ArrayList<>();
        Matcher olMatcher = Pattern.compile("(?is)<ol[^>]*>(.*?)</ol>").matcher(html);
        
        if (olMatcher.find()) {
            String olContent = olMatcher.group(1);
            Matcher liMatcher = Pattern.compile("(?is)<li[^>]*>(.*?)</li>").matcher(olContent);
            
            while (liMatcher.find()) {
                String itemContent = stripTags(liMatcher.group(1)).trim();
                if (!itemContent.isEmpty()) {
                    items.add(itemContent);
                }
            }
        }
        
        return items;
    }
}