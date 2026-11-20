package co.aisaac.webapp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Receives pages from the browser extension (browser-extension/ in the repo root).
 * Keeps each page as it was, then saves the jobs PageParser finds in it.
 */
public class CaptureController {

    private final Jobs jobs;
    private final Path pagesDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public CaptureController(Jobs jobs, Path pagesDir) {
        this.jobs = jobs;
        this.pagesDir = pagesDir;
    }

    // POST /api/pages  {"url": "...", "title": "...", "html": "..."}
    // Answers {"found": 25, "saved": 3, "described": 1}
    public void capture(Context ctx) throws IOException {
        Map<String, String> page = mapper.readValue(ctx.body(), new TypeReference<>() {
        });
        String url = page.get("url");
        String html = page.get("html");
        if (url == null || url.isBlank() || html == null) {
            ctx.status(HttpStatus.BAD_REQUEST).result("Send url and html.");
            return;
        }

        savePage(url, html);

        PageParser.Result result = PageParser.parse(url, html);

        int saved = 0;
        for (Job job : result.jobs()) {
            if (jobs.saveNew(job)) saved++;
        }

        // jobs found earlier in a list, without a description, get one when it shows up
        int described = 0;
        for (Map.Entry<String, String> entry : result.descriptions().entrySet()) {
            if (jobs.fillDescription(entry.getKey(), entry.getValue())) described++;
        }

        Map<String, Integer> answer = new LinkedHashMap<>();
        answer.put("found", result.jobs().size());
        answer.put("saved", saved);
        answer.put("described", described);
        ctx.contentType("application/json").result(mapper.writeValueAsString(answer));
    }

    // pages/linkedin.com/jobs-search_3f9a1c.html, the latest copy of each url
    private void savePage(String url, String html) throws IOException {
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "unknown" : uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");

        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (path.length() > 60) path = path.substring(0, 60);
        if (path.isEmpty()) path = "home";

        Path file = pagesDir.resolve(host).resolve(path + "_" + shortHash(url) + ".html");
        Files.createDirectories(file.getParent());
        Files.writeString(file, html, StandardCharsets.UTF_8);
    }

    private static String shortHash(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
