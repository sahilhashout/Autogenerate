package com.mysite.core.services.impl;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.mysite.core.services.GoogleDocPageCreationService;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component(service = GoogleDocPageCreationService.class)
public class GoogleDocPageCreationServiceImpl implements GoogleDocPageCreationService {

    private Set<String> boldClasses = new HashSet<>();

    private void extractBoldClasses(String html) {
        boldClasses.clear();
        Matcher styleMatcher = Pattern.compile("(?is)<style[^>]*>(.*?)</style>").matcher(html);
        if (!styleMatcher.find()) return;

        String styleText = styleMatcher.group(1);
        Matcher classMatcher = Pattern.compile("\\.(c\\d+)[^{]*\\{([^}]*)\\}").matcher(styleText);
        while (classMatcher.find()) {
            String className = classMatcher.group(1);
            String rules = classMatcher.group(2);
            if (rules.matches("(?i).*font-weight\\s*:\\s*(700|bold).*")) {
                boldClasses.add(className);
            }
        }
    }

    @Override
    public String createPage(ResourceResolver resolver, String fileId) throws Exception {
        String validatedFileId = requireFileId(fileId);

        Session session = resolver.adaptTo(Session.class);
        if (session == null) {
            throw new IllegalStateException("Unable to adapt resource resolver to JCR session");
        }

        String docHtml = fetchGoogleDocHtml(validatedFileId);
        List<Map<String, Object>> blocks = parseDocToBlocks(docHtml);
        Map<String, String> metadata = extractMetadata(blocks);

        String validatedPageName = validatePageName(metadata.get("PageName"));
        String effectiveDestParent = requireMetadataValue(metadata, "PageLocation");
        String effectiveSourcePath = requireMetadataValue(metadata, "TemplateLocation");
        String destPath = buildDestinationPath(effectiveDestParent, validatedPageName);

        ensureSourcePathExists(session, effectiveSourcePath);

        Page page = getPage(resolver, destPath);
        if (page == null) {
            copyPage(session, effectiveSourcePath, destPath);
            page = getPage(resolver, destPath);
        }

        if (page == null) {
            throw new IllegalStateException("Unable to fetch new page at " + destPath);
        }

        Resource contentResource = page.getContentResource();
        if (contentResource == null) {
            throw new IllegalStateException("jcr:content not found for " + destPath);
        }

        updatePageProperties(contentResource, metadata);
        updateSections(contentResource, blocks);

        resolver.commit();
        return destPath;
    }

    private String requireFileId(String fileId) {
        if (fileId == null || fileId.trim().isEmpty()) {
            throw new IllegalArgumentException("Provide fileId");
        }
        return fileId.trim();
    }

    private String validatePageName(String pageName) {
        if (pageName == null || pageName.trim().isEmpty()) {
            throw new IllegalArgumentException("Provide name");
        }
        String normalized = pageName.toLowerCase().replaceAll("[^a-z]", "");
        return normalized.isEmpty() ? "page" : normalized;
    }

    private String requireMetadataValue(Map<String, String> metadata, String key) {
        String value = metadata.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing metadata value: " + key);
        }
        return value.trim();
    }

    private String buildDestinationPath(String parentPath, String pageName) {
        String normalizedParent = parentPath.trim();
        if (normalizedParent.endsWith("/")) {
            normalizedParent = normalizedParent.substring(0, normalizedParent.length() - 1);
        }
        return normalizedParent + "/" + pageName;
    }

    private String fetchGoogleDocHtml(String fileId) throws IOException {
        String urlStr = "https://docs.google.com/document/d/" + fileId + "/export?format=html";
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(true);
        try (InputStream is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<Map<String, Object>> parseDocToBlocks(String raw) {
        extractBoldClasses(raw);
        String body = extractBody(raw);
        List<String[]> tokens = tokenizeTopLevelTags(body);

        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Object> curBlock = null;
        Map<String, String> curSection = null;

        for (String[] token : tokens) {
            String tag = token[0];
            String fullHtml = token[1];

            if ("h1".equals(tag)) {
                if (curBlock != null) {
                    result.add(curBlock);
                }
                curBlock = new LinkedHashMap<>();
                curBlock.put("block", stripTags(fullHtml).trim());
                curBlock.put("sections", new ArrayList<Map<String, String>>());
                curSection = null;
                continue;
            }

            if ("h2".equals(tag)) {
                if (curBlock == null) {
                    continue;
                }
                curSection = new LinkedHashMap<>();
                curSection.put("title", cleanLeafSpans(getInnerHtml(fullHtml, "h2")));
                curSection.put("html", "");
                getSections(curBlock).add(curSection);
                continue;
            }

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

        if (curBlock != null) {
            result.add(curBlock);
        }

        return result;
    }

    private String extractBody(String html) {
        Matcher m = Pattern.compile("(?is)<body[^>]*>(.*?)</body>").matcher(html);
        return m.find() ? m.group(1) : html;
    }

    private List<String[]> tokenizeTopLevelTags(String body) {
        List<String[]> tokens = new ArrayList<>();
        Pattern p = Pattern.compile("(?is)<(h1|h2|h3|h4|h5|h6|p|ul|ol|table|div|hr|blockquote)(\\s[^>]*)?>.*?</\\1>");
        Matcher m = p.matcher(body);
        while (m.find()) {
            tokens.add(new String[] { m.group(1).toLowerCase(), m.group(0) });
        }
        return tokens;
    }

    private String getInnerHtml(String outerHtml, String tag) {
        Matcher m = Pattern.compile("(?is)<" + tag + "[^>]*>(.*?)</" + tag + ">").matcher(outerHtml);
        return m.find() ? m.group(1) : outerHtml;
    }

    private String stripTags(String html) {
        return html.replaceAll("<[^>]+>", "");
    }

    private String cleanLeafSpans(String html) {
        Pattern leafPat = Pattern.compile("(?is)<span([^>]*)>(?!\\s*<span)([^<]*(?:<(?!/?span)[^<]*)*)</span>");
        Matcher m = leafPat.matcher(html);
        StringBuilder result = new StringBuilder();
        String lastText = null;

        while (m.find()) {
            String attrs = m.group(1);
            String inner = m.group(2);
            String text = inner.replaceAll("&nbsp;", " ").trim();

            if (text.isEmpty() || text.equals(lastText)) {
                continue;
            }

            boolean isBold = false;
            Matcher classMatcher = Pattern.compile("class=\"([^\"]+)\"").matcher(attrs);
            if (classMatcher.find()) {
                for (String cls : classMatcher.group(1).split("\\s+")) {
                    if (boldClasses.contains(cls)) {
                        isBold = true;
                        break;
                    }
                }
            }

            if (isBold) {
                result.append("<strong>").append(text).append("</strong>");
            } else {
                result.append("<span").append(attrs).append(">").append(inner).append("</span>");
            }

            lastText = text;
        }
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> getSections(Map<String, Object> block) {
        return (List<Map<String, String>>) block.get("sections");
    }

    private Map<String, String> extractMetadata(List<Map<String, Object>> blocks) {
        Map<String, String> metadata = new LinkedHashMap<>();

        for (Map<String, Object> block : blocks) {
            String blockName = (String) block.get("block");
            if (blockName == null || !blockName.replace(":", "").trim().equalsIgnoreCase("Metadata")) {
                continue;
            }

            for (Map<String, String> section : getSections(block)) {
                metadata.putAll(extractMetadataFromHtml(section.get("html")));
            }
            break;
        }

        return metadata;
    }

    private Map<String, String> extractMetadataFromHtml(String html) {
        Map<String, String> metadata = new LinkedHashMap<>();
        Matcher rowMatcher = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>").matcher(html);

        while (rowMatcher.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cellMatcher = Pattern.compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>").matcher(rowMatcher.group(1));

            while (cellMatcher.find()) {
                String cellText = stripTags(cellMatcher.group(1)).replace('\u00A0', ' ').trim();
                cells.add(cellText);
            }

            if (cells.size() < 2) {
                continue;
            }

            String key = cells.get(0);
            String value = cells.get(1);
            if (key.isEmpty() || key.equalsIgnoreCase("Metadata")) {
                continue;
            }

            metadata.put(key, value);
        }
        return metadata;
    }

    private void updateSections(Resource contentResource, List<Map<String, Object>> blocks)
            throws RepositoryException, PersistenceException {

        Map<String, List<Map<String, String>>> blockMap = new LinkedHashMap<>();
        for (Map<String, Object> block : blocks) {
            String name = (String) block.get("block");
            if (name.endsWith(":")) {
                name = name.substring(0, name.length() - 1);
            }
            blockMap.put(name, getSections(block));
        }

        Resource root = contentResource.getChild("root");
        if (root == null) {
            return;
        }

        for (Resource section : root.getChildren()) {
            ValueMap vm = section.getValueMap();
            String sectionName = vm.get("name", String.class);
            if (sectionName == null) {
                continue;
            }

            List<Map<String, String>> sections = blockMap.get(sectionName);
            if (sections == null || sections.isEmpty()) {
                continue;
            }

            String title = sections.get(0).get("title");
            String html = sections.get(0).get("html");

            switch (sectionName) {
                case "TopTitle":
                    updateDematSection(section, title);
                    break;
                case "TopDescription":
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
                case "Faqs":
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

    private void updateDematSection(Resource section, String title) {
        Iterator<Resource> it = section.getChildren().iterator();
        if (!it.hasNext()) {
            return;
        }
        ModifiableValueMap props = it.next().adaptTo(ModifiableValueMap.class);
        if (props != null) {
            props.put("text", buildH1(title));
        }
    }

    private String buildH1(String title) {
        StringBuilder content = new StringBuilder();
        Pattern pat = Pattern.compile("(?is)(<strong>.*?</strong>|<span[^>]*>.*?</span>|[^<]+)");
        Matcher m = pat.matcher(title);

        while (m.find()) {
            String part = m.group(0);
            if (part.toLowerCase().startsWith("<strong>")) {
                String text = stripTags(part).replaceAll("&nbsp;", " ").trim();
                if (!text.isEmpty()) {
                    content.append(" <strong>").append(text).append("</strong>");
                }
            } else {
                String text = stripTags(part).replaceAll("&nbsp;", " ").trim();
                if (!text.isEmpty()) {
                    content.append(" ").append(text);
                }
            }
        }

        return "<h1>" + content.toString().trim() + "</h1>";
    }

    private void updateDematSectionDescription(Resource section, String html) {
        Iterator<Resource> it = section.getChildren().iterator();
        if (!it.hasNext()) {
            return;
        }
        ModifiableValueMap props = it.next().adaptTo(ModifiableValueMap.class);
        if (props != null) {
            props.put("text", html);
        }
    }

    private void updateDematAccount(Resource section, String title, String html)
            throws RepositoryException, PersistenceException {
        List<Resource> children = new ArrayList<>();
        section.getChildren().forEach(children::add);

        if (!children.isEmpty()) {
            ModifiableValueMap props = children.get(0).adaptTo(ModifiableValueMap.class);
            if (props != null) {
                props.put("text", title);
            }
        }

        if (children.size() <= 1) {
            return;
        }

        Resource secondChild = children.get(1);
        List<Resource> innerList = new ArrayList<>();
        secondChild.getChildren().forEach(innerList::add);
        List<String> listItems = extractDematAccountItems(html);

        if (innerList.size() > listItems.size()) {
            for (int i = innerList.size() - 1; i >= listItems.size(); i--) {
                secondChild.getResourceResolver().delete(innerList.get(i));
                innerList.remove(i);
            }
        }

        if (innerList.size() < listItems.size()) {
            for (int i = innerList.size(); i < listItems.size(); i++) {
                Resource newChild = secondChild.getChild("item_" + (i + 1));
                if (newChild == null) {
                    newChild = copyPreviousChild(secondChild, innerList, "item_" + (i + 1));
                }
                innerList.add(newChild);
            }
        }

        for (int i = 0; i < listItems.size() && i < innerList.size(); i++) {
            ModifiableValueMap props = innerList.get(i).adaptTo(ModifiableValueMap.class);
            if (props != null) {
                props.put("content_text", listItems.get(i));
            }
        }
    }

    private void updateTradingAccount(Resource section, String title, String html) {
        List<Resource> children = new ArrayList<>();
        section.getChildren().forEach(children::add);
        if (children.isEmpty()) {
            return;
        }

        ModifiableValueMap props = children.get(0).adaptTo(ModifiableValueMap.class);
        if (props != null) {
            props.put("text", "<h2>" + title + "</h2>" + html);
        }
    }

    private void updateDemateTradingDifference(Resource section, String title, String html) {
        List<Resource> children = new ArrayList<>();
        section.getChildren().forEach(children::add);
        if (children.isEmpty()) {
            return;
        }

        String titleText = stripTags(title).trim();
        String[] extracted = extractParagraphsBeforeTable(html);
        String pContent = extracted[0];
        String tableContent = extracted[1];

        if (children.size() > 0) {
            ModifiableValueMap props = children.get(0).adaptTo(ModifiableValueMap.class);
            if (props != null) {
                props.put("text", titleText);
            }
        }
        if (children.size() > 1) {
            ModifiableValueMap props = children.get(1).adaptTo(ModifiableValueMap.class);
            if (props != null) {
                props.put("text", pContent);
            }
        }
        if (children.size() > 2) {
            ModifiableValueMap props = children.get(2).adaptTo(ModifiableValueMap.class);
            if (props != null) {
                props.put("text", tableContent);
            }
        }
    }

    private void updateDemateTradingDifferenceDescription(Resource section, String html) {
        Iterator<Resource> it = section.getChildren().iterator();
        if (!it.hasNext()) {
            return;
        }
        ModifiableValueMap props = it.next().adaptTo(ModifiableValueMap.class);
        if (props != null) {
            props.put("text", html);
        }
    }

    private void updateDematTradingProcessExplained(Resource section, String title, String html)
            throws RepositoryException, PersistenceException {
        List<Resource> children = new ArrayList<>();
        section.getChildren().forEach(children::add);
        if (children.isEmpty()) {
            return;
        }

        String titleText = stripTags(title).trim();
        ModifiableValueMap titleProps = children.get(0).adaptTo(ModifiableValueMap.class);
        if (titleProps != null) {
            titleProps.put("text", titleText);
        }

        if (children.size() <= 1) {
            return;
        }

        Resource blockNode = children.get(1);
        List<Resource> blockChildren = new ArrayList<>();
        blockNode.getChildren().forEach(blockChildren::add);
        List<String> listItems = extractOlListItems(html);

        if (blockChildren.size() > listItems.size()) {
            for (int i = blockChildren.size() - 1; i >= listItems.size(); i--) {
                blockNode.getResourceResolver().delete(blockChildren.get(i));
                blockChildren.remove(i);
            }
        }

        if (blockChildren.size() < listItems.size()) {
            for (int i = blockChildren.size(); i < listItems.size(); i++) {
                Resource newChild = copyPreviousChild(blockNode, blockChildren, "item_" + (i + 1));
                blockChildren.add(newChild);
            }
        }

        for (int i = 0; i < listItems.size(); i++) {
            ModifiableValueMap props = blockChildren.get(i).adaptTo(ModifiableValueMap.class);
            if (props != null) {
                int stepNumber = i + 1;
                String stepLabel = String.format("Step %02d", stepNumber);
                props.put("description", listItems.get(i));
                props.put("step_count", stepNumber);
                props.put("steps-Text", stepLabel);
                props.put("steps-text", stepLabel);
            }
        }
    }

    private void updateDematTradingProcessDescription(Resource section, String html) {
        Iterator<Resource> it = section.getChildren().iterator();
        if (!it.hasNext()) {
            return;
        }
        ModifiableValueMap props = it.next().adaptTo(ModifiableValueMap.class);
        if (props != null) {
            props.put("text", html);
        }
    }

    private void updateDeliveryIntradayTrades(Resource section, String title, String html)
            throws RepositoryException, PersistenceException {
        List<Resource> children = new ArrayList<>();
        section.getChildren().forEach(children::add);

        String firstP = "";
        Matcher firstPMatcher = Pattern.compile("(?is)<p[^>]*>(.*?)</p>").matcher(html);
        if (firstPMatcher.find()) {
            firstP = "<p>" + stripTags(firstPMatcher.group(1)).replaceAll("&nbsp;", " ").trim() + "</p>";
        }

        if (!children.isEmpty()) {
            String titleText = stripTags(title).trim();
            String combined = "<h2>" + titleText + "</h2>" + firstP;
            ModifiableValueMap props = children.get(0).adaptTo(ModifiableValueMap.class);
            if (props != null) {
                props.put("text", combined);
            }
        }

        if (children.size() <= 1) {
            return;
        }

        List<String> pairs = extractH4ParagraphPairs(html);
        Resource secondChild = children.get(1);
        List<Resource> innerList = new ArrayList<>();
        secondChild.getChildren().forEach(innerList::add);

        if (innerList.size() > pairs.size()) {
            for (int i = innerList.size() - 1; i >= pairs.size(); i--) {
                secondChild.getResourceResolver().delete(innerList.get(i));
                innerList.remove(i);
            }
        }

        if (innerList.size() < pairs.size()) {
            for (int i = innerList.size(); i < pairs.size(); i++) {
                Resource newChild = copyPreviousChild(secondChild, innerList, "item_" + (i + 1));
                innerList.add(newChild);
            }
        }

        for (int i = 0; i < pairs.size() && i < innerList.size(); i++) {
            ModifiableValueMap props = innerList.get(i).adaptTo(ModifiableValueMap.class);
            if (props != null) {
                props.put("text", pairs.get(i));
            }
        }
    }

    private List<String> extractH4ParagraphPairs(String html) {
        List<String> pairs = new ArrayList<>();
        Pattern pat = Pattern.compile("(?is)<(h4|p)(\\s[^>]*)?>.*?</\\1>");
        Matcher m = pat.matcher(html);
        List<String[]> tokens = new ArrayList<>();
        boolean skippedFirstP = false;

        while (m.find()) {
            String tag = m.group(1).toLowerCase();
            if ("p".equals(tag) && !skippedFirstP) {
                skippedFirstP = true;
                continue;
            }
            tokens.add(new String[] { tag, m.group(0) });
        }

        int i = 0;
        while (i < tokens.size()) {
            if ("h4".equals(tokens.get(i)[0])) {
                String h4Text = stripTags(tokens.get(i)[1]).trim();
                String pText = "";
                if (i + 1 < tokens.size() && "p".equals(tokens.get(i + 1)[0])) {
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
        if (!it.hasNext()) {
            return;
        }
        ModifiableValueMap props = it.next().adaptTo(ModifiableValueMap.class);
        if (props != null) {
            props.put("text", html);
        }
    }

    private void updateTitleWithHtml(Resource section, String title, String html) {
        Iterator<Resource> it = section.getChildren().iterator();
        if (!it.hasNext()) {
            return;
        }
        ModifiableValueMap props = it.next().adaptTo(ModifiableValueMap.class);
        if (props != null) {
            props.put("text", "<h2>" + stripTags(title).trim() + "</h2>" + stripSpanTags(html));
        }
    }

    private String stripSpanTags(String html) {
        return html.replaceAll("(?i)</?span[^>]*>", "");
    }

    private void updateDisclaimer(Resource section, String html) {
        Iterator<Resource> it = section.getChildren().iterator();
        if (!it.hasNext()) {
            return;
        }
        ModifiableValueMap props = it.next().adaptTo(ModifiableValueMap.class);
        if (props == null) {
            return;
        }

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

        if (!children.isEmpty()) {
            String titleText = "<h2>" + stripTags(title).trim() + "</h2>";
            Matcher firstPMatcher = Pattern.compile("(?is)<p[^>]*>(.*?)</p>").matcher(html);
            if (firstPMatcher.find()) {
                String pText = stripTags(firstPMatcher.group(1)).replaceAll("&nbsp;", " ").trim();
                if (!pText.isEmpty()) {
                    titleText += "<p>" + pText + "</p>";
                }
            }
            ModifiableValueMap props = children.get(0).adaptTo(ModifiableValueMap.class);
            if (props != null) {
                props.put("text", titleText);
            }
        }

        if (children.size() <= 1) {
            return;
        }

        Resource secondChild = children.get(1);
        List<Resource> innerList = new ArrayList<>();
        secondChild.getChildren().forEach(innerList::add);
        List<String> liItems = extractUlListItems(html);

        if (innerList.size() > liItems.size()) {
            for (int i = innerList.size() - 1; i >= liItems.size(); i--) {
                secondChild.getResourceResolver().delete(innerList.get(i));
                innerList.remove(i);
            }
        }

        if (innerList.size() < liItems.size()) {
            for (int i = innerList.size(); i < liItems.size(); i++) {
                Resource newChild = copyPreviousChild(secondChild, innerList, "item_" + (i + 1));
                innerList.add(newChild);
            }
        }

        for (int i = 0; i < liItems.size() && i < innerList.size(); i++) {
            ModifiableValueMap props = innerList.get(i).adaptTo(ModifiableValueMap.class);
            if (props != null) {
                props.put("content", "<p>" + liItems.get(i) + "</p>");
            }
        }
    }

    private void updateFAQs(Resource section, String title, String html)
            throws RepositoryException, PersistenceException {
        List<Resource> children = new ArrayList<>();

        section.getChildren().forEach(children::add);

        if (!children.isEmpty()) {
            ModifiableValueMap props = children.get(0).adaptTo(ModifiableValueMap.class);
            if (props != null) {
                props.put("text", "<h1>" + stripTags(title).trim() + "</h1>");
            }
        }

        if (children.size() <= 1) {
            return;
        }

        Resource secondChild = children.get(1);
        List<Resource> innerList = new ArrayList<>();
        secondChild.getChildren().forEach(innerList::add);
        List<String[]> faqPairs = extractFaqPairs(html);

        if (innerList.size() > faqPairs.size()) {
            for (int i = innerList.size() - 1; i >= faqPairs.size(); i--) {
                secondChild.getResourceResolver().delete(innerList.get(i));
                innerList.remove(i);
            }
        }

        if (innerList.size() < faqPairs.size()) {
            for (int i = innerList.size(); i < faqPairs.size(); i++) {
                Resource newChild = copyPreviousChild(secondChild, innerList, "item_" + (i + 1));
                innerList.add(newChild);
            }
        }

        for (int i = 0; i < faqPairs.size() && i < innerList.size(); i++) {
            ModifiableValueMap props = innerList.get(i).adaptTo(ModifiableValueMap.class);
            if (props != null) {
                props.put("summary", faqPairs.get(i)[0]);
                props.put("text", faqPairs.get(i)[1]);
            }
        }
    }

    private List<String[]> extractFaqPairs(String html) {
        List<String[]> tablePairs = extractFaqPairsFromTable(html);
        if (!tablePairs.isEmpty()) {
            return tablePairs;
        }

        List<String[]> pairs = new ArrayList<>();
        Pattern pat = Pattern.compile("(?is)<(h4|p)(\\s[^>]*)?>.*?</\\1>");
        Matcher m = pat.matcher(html);
        List<String[]> tokens = new ArrayList<>();

        while (m.find()) {
            tokens.add(new String[] { m.group(1).toLowerCase(), m.group(0) });
        }

        int i = 0;
        while (i < tokens.size()) {
            if ("h4".equals(tokens.get(i)[0])) {
                String question = stripTags(tokens.get(i)[1]).trim();
                StringBuilder answer = new StringBuilder();
                i++;
                while (i < tokens.size() && "p".equals(tokens.get(i)[0])) {
                    String pText = stripTags(tokens.get(i)[1]).replaceAll("&nbsp;", " ").trim();
                    if (!pText.isEmpty()) {
                        answer.append("<p>").append(pText).append("</p>");
                    }
                    i++;
                }
                if (!question.isEmpty()) {
                    pairs.add(new String[] { question, answer.toString() });
                }
            } else {
                i++;
            }
        }
        return pairs;
    }

    private List<String[]> extractFaqPairsFromTable(String html) {
        List<String[]> pairs = new ArrayList<>();
        List<String> rowTexts = new ArrayList<>();

        Matcher rowMatcher = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>").matcher(html);
        while (rowMatcher.find()) {
            String rowHtml = rowMatcher.group(1);
            Matcher cellMatcher = Pattern.compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>").matcher(rowHtml);
            StringBuilder rowText = new StringBuilder();

            while (cellMatcher.find()) {
                String cellText = stripTags(cellMatcher.group(1)).replace("&nbsp;", " ");
                cellText = cellText.replace('\u00A0', ' ').trim();
                if (!cellText.isEmpty()) {
                    if (rowText.length() > 0) {
                        rowText.append(' ');
                    }
                    rowText.append(cellText);
                }
            }

            if (rowText.length() > 0) {
                rowTexts.add(rowText.toString());
            }
        }

        for (int i = 0; i + 1 < rowTexts.size(); i += 2) {
            String question = rowTexts.get(i).trim();
            String answer = rowTexts.get(i + 1).trim();
            if (!question.isEmpty()) {
                pairs.add(new String[] { question, "<p>" + answer + "</p>" });
            }
        }

        return pairs;
    }

    private void updateNsdlCdslDifference(Resource section, String title, String html) {
        List<Resource> children = new ArrayList<>();
        section.getChildren().forEach(children::add);

        if (!children.isEmpty()) {
            ModifiableValueMap props = children.get(0).adaptTo(ModifiableValueMap.class);
            if (props != null) {
                props.put("text", stripTags(title).trim());
            }
        }

        if (children.size() > 1) {
            String tableContent = extractParagraphsBeforeTable(html)[1];
            ModifiableValueMap props = children.get(1).adaptTo(ModifiableValueMap.class);
            if (props != null) {
                props.put("text", tableContent);
            }
        }
    }

    private void ensureSourcePathExists(Session session, String sourcePath) throws RepositoryException {
        if (!session.nodeExists(sourcePath)) {
            throw new IllegalArgumentException("Source page not found");
        }
    }

    private Resource copyPreviousChild(Resource parent, List<Resource> existingChildren, String newName)
            throws PersistenceException, RepositoryException {
        if (existingChildren.isEmpty()) {
            throw new IllegalStateException("No template child available to copy under " + parent.getPath());
        }

        ResourceResolver resolver = parent.getResourceResolver();
        Resource previousChild = existingChildren.get(existingChildren.size() - 1);
        Session session = resolver.adaptTo(Session.class);
        if (session == null) {
            throw new IllegalStateException("Unable to adapt resource resolver to JCR session");
        }

        String targetPath = parent.getPath() + "/" + newName;
        session.getWorkspace().copy(previousChild.getPath(), targetPath);
        session.save();

        resolver.commit();
        resolver.refresh();

        Resource copiedChild = resolver.getResource(targetPath);
        if (copiedChild == null) {
            throw new IllegalStateException("Unable to fetch copied child at " + targetPath);
        }

        return copiedChild;
    }

    private void copyPage(Session session, String sourcePath, String destPath) throws RepositoryException {
        session.getWorkspace().copy(sourcePath, destPath);
        session.save();
    }

    private Page getPage(ResourceResolver resolver, String path) {
        PageManager pageManager = resolver.adaptTo(PageManager.class);
        return pageManager == null ? null : pageManager.getPage(path);
    }

    private void updatePageProperties(Resource contentResource, Map<String, String> metaData) {
        ModifiableValueMap props = contentResource.adaptTo(ModifiableValueMap.class);
        if (props != null) {
            props.put("jcr:title", "Created via Service");
            props.put("description", "Auto generated page");

            for(Map.Entry<String, String> entry: metaData.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();

                if(key == null || key.trim().isEmpty() || value == null || value.trim().isEmpty()) {
                    continue;
                }

                if(key.equalsIgnoreCase("PageName") || key.equalsIgnoreCase("PageLocation")
                        || key.equalsIgnoreCase("TemplateLocation")) {
                    continue;
                }

                String jcrKey = toCamelCase(key);
                props.put(jcrKey, value.trim());
            }
        }
    }

    private String toCamelCase(String key) {
        String[] words = key.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String word = words[i].replaceAll("[^a-zA-Z0-9]", "");
            if (word.isEmpty()) continue;
            if (i == 0) {
                sb.append(Character.toLowerCase(word.charAt(0)))
                        .append(word.substring(1));
            } else {
                sb.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1));
            }
        }
        return sb.toString();
    }

    private List<String> extractParagraphs(String html) {
        List<String> paragraphs = new ArrayList<>();
        Matcher m = Pattern.compile("(?is)<p[^>]*>(.*?)</p>").matcher(html);
        while (m.find()) {
            paragraphs.add("<p>" + m.group(1) + "</p>");
        }
        return paragraphs;
    }

    private List<String> extractDematAccountItems(String html) {
        List<String> items = new ArrayList<>();
        Matcher ulMatcher = Pattern.compile("(?is)<ul[^>]*>(.*?)</ul>").matcher(html);

        if (ulMatcher.find()) {
            Matcher liMatcher = Pattern.compile("(?is)<li[^>]*>(.*?)</li>").matcher(ulMatcher.group(1));
            while (liMatcher.find()) {
                String itemHtml = liMatcher.group(1).replace("&nbsp;", " ");
                itemHtml = itemHtml.replace('\u00A0', ' ');
                items.add("<p>" + itemHtml + "</p>");
            }
        }

        if (items.isEmpty()) {
            Matcher liMatcher = Pattern.compile("(?is)<li[^>]*>(.*?)</li>").matcher(html);
            while (liMatcher.find()) {
                String itemHtml = liMatcher.group(1).replace("&nbsp;", " ");
                itemHtml = itemHtml.replace('\u00A0', ' ');
                items.add("<p>" + itemHtml + "</p>");
            }
        }

        return items;
    }

    private String[] extractParagraphsBeforeTable(String html) {
        String introHtml = "";
        int tableIndex = html.toLowerCase().indexOf("<table");
        if (tableIndex > 0) {
            String beforeTable = html.substring(0, tableIndex);
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

        String convertedTable = "";
        Matcher tableMatcher = Pattern.compile("(?is)<table[^>]*>(.*?)</table>").matcher(html);
        if (tableMatcher.find()) {
            String tableContent = tableMatcher.group(1);
            List<List<String>> rows = new ArrayList<>();
            Matcher rowMatcher = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>").matcher(tableContent);

            while (rowMatcher.find()) {
                String rowHtml = rowMatcher.group(1);
                List<String> cells = new ArrayList<>();
                Matcher cellMatcher = Pattern.compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>").matcher(rowHtml);
                while (cellMatcher.find()) {
                    String cellText = stripTags(cellMatcher.group(1)).trim();
                    cells.add(cellText);
                }
                if (!cells.isEmpty()) {
                    rows.add(cells);
                }
            }

            if (!rows.isEmpty()) {
                int colCount = rows.get(0).size();
                List<String> headers = rows.get(0);
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

        return new String[] { introHtml, convertedTable };
    }

    private List<String> extractUlListItems(String html) {
        List<String> items = new ArrayList<>();
        Matcher ulMatcher = Pattern.compile("(?is)<ul[^>]*>(.*?)</ul>").matcher(html);
        if (ulMatcher.find()) {
            String ulContent = ulMatcher.group(1);
            Matcher liMatcher = Pattern.compile("(?is)<li[^>]*>(.*?)</li>").matcher(ulContent);
            while (liMatcher.find()) {
                String text = stripTags(liMatcher.group(1)).trim();
                if (!text.isEmpty()) {
                    items.add(text);
                }
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
