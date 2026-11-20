package co.aisaac.webapp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Filtering for the main page. Plain functions, no state.
 * Jobs never hands out null fields, so nothing here checks for them.
 */
public class JobFilter {

    /**
     * Adds company statuses to every job, filters by the main page filter, and sorts by company.
     */
    public static List<Job> apply(List<Job> all, MainFilter filter) {
        augmentCompanies(all);

        return filterJobs(filter, all, true, true).stream()
                .sorted((o1, o2) -> o1.company.compareToIgnoreCase(o2.company))
                .toList();
    }

    // How many jobs each status tab would show, with the current site and search.
    public static Map<String, Long> countByStatus(List<Job> all, MainFilter filter) {
        return filterJobs(filter, all, false, true).stream()
                .collect(Collectors.groupingBy(job -> job.status, Collectors.counting()));
    }

    // How many jobs each site chip would show, with the current status and search.
    public static Map<String, Long> countBySite(List<Job> all, MainFilter filter) {
        return filterJobs(filter, all, true, false).stream()
                .collect(Collectors.groupingBy(job -> job.job_site.toLowerCase(Locale.ROOT), Collectors.counting()));
    }

    private static List<Job> filterJobs(MainFilter mainFilter, List<Job> all, boolean byStatus, boolean bySite) {
        List<Job> filtered = all;

        // filter status
        if (byStatus) {
            String status = mainFilter.getStatus();
            filtered = filtered.stream()
                    .filter(job -> job.status.trim().equalsIgnoreCase(status.trim()))
                    .toList();
        }

        // filter job site
        String jobSite = mainFilter.getJobSite();
        if (bySite && !jobSite.equalsIgnoreCase("all")) {
            filtered = filtered.stream()
                    .filter(job -> job.job_site.trim().equalsIgnoreCase(jobSite.trim()))
                    .toList();
        }

        // filter company
        List<String> companies = clean(split(mainFilter.getCompanySearch()));
        if (!companies.isEmpty()) {
            filtered = filtered.stream()
                    .filter(job -> containsAny(clean(job.company), companies))
                    .toList();
        }

        // filter search terms, in the description text (not its html) or the title
        List<String> searchTerms = clean(split(mainFilter.getSearchTerms()));
        if (!searchTerms.isEmpty()) {
            filtered = filtered.stream()
                    .filter(job -> containsAny(clean(job.descriptionText), searchTerms)
                            || containsAny(clean(job.title), searchTerms))
                    .toList();
        }

        // filter title
        List<String> titleSearchTerms = clean(split(mainFilter.getTitleSearch()));
        if (!titleSearchTerms.isEmpty()) {
            filtered = filtered.stream()
                    .filter(job -> containsAny(clean(job.title), titleSearchTerms))
                    .toList();
        }

        return filtered;
    }

    // add the statuses of all of a company's jobs to each of its jobs
    private static void augmentCompanies(List<Job> jobs) {
        Map<String, Set<String>> comps = new HashMap<>();

        for (Job job : jobs) {
            comps.computeIfAbsent(job.company, c -> new HashSet<>()).add(job.status);
        }

        for (Job job : jobs) {
            job.companyStatuses = comps.get(job.company).stream().toList();
        }
    }

    /**
     * Wraps every match of the filter's search terms in <span class="highlight">.
     * Only text is changed, never tags or attributes, so the html stays valid.
     */
    public static String highlight(String html, MainFilter filter) {
        List<String> searchTerms = clean(split(filter.getSearchTerms()));
        if (searchTerms.isEmpty()) {
            return html;
        }

        Pattern pattern = Pattern.compile(
                searchTerms.stream().map(Pattern::quote).collect(Collectors.joining("|")),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

        Document doc = Jsoup.parseBodyFragment(html);

        // collect first, since we change the document while going through them
        List<TextNode> textNodes = new ArrayList<>();
        for (Element element : doc.body().getAllElements()) {
            textNodes.addAll(element.textNodes());
        }

        for (TextNode node : textNodes) {
            String text = node.getWholeText();
            Matcher matcher = pattern.matcher(text);
            if (!matcher.find()) {
                continue;
            }

            StringBuilder highlighted = new StringBuilder();
            int last = 0;
            do {
                highlighted.append(Entities.escape(text.substring(last, matcher.start())));
                highlighted.append("<span class=\"highlight\">")
                        .append(Entities.escape(matcher.group()))
                        .append("</span>");
                last = matcher.end();
            } while (matcher.find());
            highlighted.append(Entities.escape(text.substring(last)));

            node.before(highlighted.toString());
            node.remove();
        }

        return doc.body().html();
    }

    private static boolean containsAny(String s, List<String> terms) {
        for (String term : terms) {
            if (s.contains(term)) {
                return true;
            }
        }
        return false;
    }

    // "java, kotlin,," -> ["java", " kotlin"]
    private static List<String> split(String commaSeparated) {
        return Arrays.stream(commaSeparated.split(","))
                .filter(s -> !s.isBlank())
                .toList();
    }

    private static List<String> clean(List<String> list) {
        return list.stream().map(JobFilter::clean).toList();
    }

    private static String clean(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }
}
