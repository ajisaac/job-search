package co.aisaac.webapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the jobs in a page captured by the browser extension. Takes what's easy to get, three ways:
 * <ol>
 *   <li>schema.org JobPosting data (json-ld), which most single job pages include: title, company, description.</li>
 *   <li>links to job pages, e.g. a search results list: the link's text is the title, company if the card shows it.</li>
 *   <li>the description of the job open in a side pane, on LinkedIn and Indeed search pages.</li>
 * </ol>
 * Page layouts change, so everything here is best effort. The raw pages are kept (see CaptureController)
 * so they can be parsed again when this improves.
 */
public class PageParser {

    /**
     * jobs: every job found, one per url.
     * descriptions: url -> description html, for jobs whose description is on the page.
     */
    public record Result(List<Job> jobs, Map<String, String> descriptions) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern LINKEDIN_VIEW = Pattern.compile("/jobs/view/(?:[^/?#]*-)?(\\d+)");
    private static final Pattern NUMBERED_JOB = Pattern.compile("^/jobs/(\\d+)");
    private static final Pattern GREENHOUSE_JOB = Pattern.compile("^/([^/]+)/jobs/(\\d+)");
    private static final Pattern UUID_JOB = Pattern.compile("^/([^/]+)/([0-9a-f]{8}-[0-9a-f-]{27})");

    // Where a job card might show its company. Tried in order inside the card around a job link.
    private static final List<String> COMPANY_SELECTORS = List.of(
            "[data-testid=company-name]",
            ".artdeco-entity-lockup__subtitle",
            "[data-test=emp-name]",
            "[class*=company-name]",
            "[class*=companyName]",
            "[class*=CompanyName]"
    );

    public static Result parse(String pageUrl, String html) {
        Document doc = Jsoup.parse(html, pageUrl);

        Map<String, Job> jobs = new LinkedHashMap<>();
        Map<String, String> descriptions = new LinkedHashMap<>();

        // links first, then json-ld, so the fuller json-ld version of a job wins
        for (Job job : fromLinks(doc)) {
            jobs.putIfAbsent(job.url, job);
        }
        for (Job job : fromJsonLd(doc, pageUrl)) {
            jobs.put(job.url, job);
            if (!job.description.isBlank()) {
                descriptions.put(job.url, job.description);
            }
        }

        Job open = fromSidePane(doc, pageUrl);
        if (open != null) {
            jobs.putIfAbsent(open.url, open);
            descriptions.put(open.url, open.description);
        }

        return new Result(new ArrayList<>(jobs.values()), descriptions);
    }

    // ---------- 1. json-ld JobPosting ----------

    private static List<Job> fromJsonLd(Document doc, String pageUrl) {
        List<Job> jobs = new ArrayList<>();
        for (Element script : doc.select("script[type=application/ld+json]")) {
            try {
                collectJobPostings(MAPPER.readTree(script.data()), pageUrl, jobs);
            } catch (Exception e) {
                // not valid json, skip it
            }
        }
        return jobs;
    }

    private static void collectJobPostings(JsonNode node, String pageUrl, List<Job> jobs) {
        if (node.isArray()) {
            node.forEach(child -> collectJobPostings(child, pageUrl, jobs));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        if (node.has("@graph")) {
            collectJobPostings(node.get("@graph"), pageUrl, jobs);
        }
        if (!isType(node, "JobPosting")) {
            return;
        }

        String title = node.path("title").asText("").trim();
        if (title.isEmpty()) {
            return;
        }

        JsonNode org = node.path("hiringOrganization");
        String company = org.isObject() ? org.path("name").asText("") : org.asText("");

        String description = node.path("description").asText("");
        if (description.contains("&lt;")) {
            description = Parser.unescapeEntities(description, false);
        }

        String url = jobUrl(node.path("url").asText(""));
        if (url == null) url = jobUrl(pageUrl);
        if (url == null) url = withoutFragment(pageUrl);

        jobs.add(newJob(title, company.trim(), url, description));
    }

    private static boolean isType(JsonNode node, String type) {
        JsonNode t = node.path("@type");
        if (t.isArray()) {
            for (JsonNode each : t) {
                if (type.equals(each.asText())) return true;
            }
            return false;
        }
        return type.equals(t.asText());
    }

    // ---------- 2. links to job pages ----------

    private static List<Job> fromLinks(Document doc) {
        Map<String, Job> jobs = new LinkedHashMap<>();

        for (Element link : doc.select("a[href]")) {
            String url = jobUrl(link.absUrl("href"));
            if (url == null) {
                continue;
            }

            String title = linkTitle(link);
            Job existing = jobs.get(url);
            if (existing == null) {
                // a job's first link may be its logo, with no text; keep looking for a title
                jobs.put(url, newJob(title, company(link, url), url, ""));
            } else {
                if (existing.title.length() < 4 && title.length() >= 4) existing.title = title;
                if (existing.company.isEmpty()) existing.company = company(link, url);
            }
        }

        return jobs.values().stream().filter(job -> job.title.length() >= 4).toList();
    }

    private static String linkTitle(Element link) {
        String label = link.attr("aria-label").trim();
        String text = (label.isEmpty() ? link.text() : label).replaceAll("\\s+", " ").trim();

        // LinkedIn repeats the title for screen readers: "Engineer Engineer", "Engineer with verification"
        text = text.replaceFirst("(?i) with verification$", "");
        int half = text.length() / 2;
        if (text.length() % 2 == 1 && text.substring(0, half).equals(text.substring(half + 1))) {
            text = text.substring(0, half);
        }

        return text.length() > 200 ? "" : text;
    }

    private static String company(Element link, String url) {
        // greenhouse, lever and ashby have the company in the url
        String fromUrl = companyFromUrl(url);
        if (!fromUrl.isEmpty()) {
            return fromUrl;
        }

        Element card = link.closest("li, article, [data-jk], [data-job-id], [data-occludable-job-id]");
        if (card == null) {
            return "";
        }
        for (String selector : COMPANY_SELECTORS) {
            Element element = card.selectFirst(selector);
            if (element != null && !element.text().isBlank()) {
                return element.text().replaceAll("\\s+", " ").trim();
            }
        }
        return "";
    }

    // ---------- 3. the job open in a side pane ----------

    // LinkedIn and Indeed search pages show the selected job's description next to the results.
    private static Job fromSidePane(Document doc, String pageUrl) {
        URI uri = uri(pageUrl);
        if (uri == null) {
            return null;
        }
        String host = host(uri);

        String url = null;
        Element pane = null;

        if (host.endsWith("linkedin.com")) {
            String id = param(uri, "currentJobId");
            if (id != null) {
                url = "https://www.linkedin.com/jobs/view/" + id + "/";
            } else {
                url = jobUrl(pageUrl);
            }
            pane = doc.selectFirst("#job-details");
        } else if (host.endsWith("indeed.com")) {
            String jk = param(uri, "vjk");
            if (jk == null) jk = param(uri, "jk");
            if (jk != null) {
                url = "https://" + host + "/viewjob?jk=" + jk;
            }
            pane = doc.selectFirst("#jobDescriptionText");
        }

        if (url == null || pane == null || pane.text().isBlank()) {
            return null;
        }

        Element heading = doc.selectFirst("h1");
        String title = heading == null ? "" : heading.text().trim();
        return newJob(title, "", url, pane.html());
    }

    // ---------- urls ----------

    /**
     * The one url to keep for a job page, without tracking parameters, so the same job found on
     * different pages matches up. Null if the url isn't a job page we know.
     */
    static String jobUrl(String url) {
        URI uri = uri(url);
        if (uri == null) {
            return null;
        }
        String host = host(uri);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        Matcher m;

        if (host.endsWith("linkedin.com")) {
            m = LINKEDIN_VIEW.matcher(path);
            return m.find() ? "https://www.linkedin.com/jobs/view/" + m.group(1) + "/" : null;
        }
        if (host.endsWith("indeed.com")) {
            String jk = param(uri, "jk");
            boolean jobLink = path.startsWith("/viewjob") || path.startsWith("/rc/clk")
                    || path.startsWith("/pagead/clk") || path.startsWith("/m/viewjob");
            return jobLink && jk != null ? "https://" + host + "/viewjob?jk=" + jk : null;
        }
        if (host.endsWith("wellfound.com")) {
            m = NUMBERED_JOB.matcher(path);
            return m.find() ? "https://wellfound.com/jobs/" + m.group(1) : null;
        }
        if (host.endsWith("workatastartup.com")) {
            m = NUMBERED_JOB.matcher(path);
            return m.find() ? "https://www.workatastartup.com/jobs/" + m.group(1) : null;
        }
        if (host.contains("glassdoor.")) {
            String jl = param(uri, "jl");
            return path.startsWith("/job-listing/") ? "https://" + host + path + (jl == null ? "" : "?jl=" + jl) : null;
        }
        if (host.endsWith("greenhouse.io")) {
            m = GREENHOUSE_JOB.matcher(path);
            return m.find() ? "https://" + host + "/" + m.group(1) + "/jobs/" + m.group(2) : null;
        }
        if (host.equals("jobs.lever.co") || host.equals("jobs.ashbyhq.com")) {
            m = UUID_JOB.matcher(path);
            return m.find() ? "https://" + host + "/" + m.group(1) + "/" + m.group(2) : null;
        }
        return null;
    }

    private static String companyFromUrl(String url) {
        URI uri = uri(url);
        if (uri == null) {
            return "";
        }
        String host = host(uri);
        if (host.endsWith("greenhouse.io") || host.equals("jobs.lever.co") || host.equals("jobs.ashbyhq.com")) {
            String[] parts = uri.getPath().split("/");
            return parts.length > 1 ? parts[1] : "";
        }
        return "";
    }

    /**
     * The jobs folder name for a site: "linkedin", "indeed", "greenhouse" and so on.
     */
    public static String siteName(String url) {
        URI uri = uri(url);
        String host = uri == null ? "" : host(uri);

        if (host.endsWith("linkedin.com")) return "linkedin";
        if (host.endsWith("indeed.com")) return "indeed";
        if (host.endsWith("wellfound.com")) return "wellfound";
        if (host.contains("glassdoor.")) return "glassdoor";
        if (host.endsWith("workatastartup.com")) return "work_at_a_startup";
        if (host.endsWith("welcometothejungle.com") || host.endsWith("otta.com")) return "welcome_to_the_jungle";
        if (host.endsWith("remote.co")) return "remote_co";
        if (host.endsWith("greenhouse.io")) return "greenhouse";
        if (host.equals("jobs.lever.co")) return "lever";
        if (host.equals("jobs.ashbyhq.com")) return "ashby";
        return host.isEmpty() ? "unknown" : host.replaceFirst("^www\\.", "").replaceAll("[^a-z0-9]+", "_");
    }

    private static URI uri(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(url.trim());
            return uri.getHost() == null ? null : uri;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String host(URI uri) {
        return uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static String param(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    private static String withoutFragment(String url) {
        int hash = url.indexOf('#');
        return hash < 0 ? url : url.substring(0, hash);
    }

    private static Job newJob(String title, String company, String url, String description) {
        Job job = new Job();
        job.title = title;
        job.company = company;
        job.url = url;
        job.description = description;
        job.status = "new";
        job.job_site = siteName(url);
        job.job_posting_date = LocalDate.now();
        return job;
    }
}
