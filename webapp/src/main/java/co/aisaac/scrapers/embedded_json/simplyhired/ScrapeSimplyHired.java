package co.aisaac.scrapers.embedded_json.simplyhired;

import co.aisaac.scrapers.Http;
import co.aisaac.scrapers.Utils;
import co.aisaac.webapp.Job;
import co.aisaac.webapp.Jobs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Scrapes SimplyHired, Indeed's job search site. Its pages are built with Next.js, so each carries its data
 * as JSON in a {@code <script id="__NEXT_DATA__">}, under {@code props.pageProps}.
 * <p>
 * Searches remote software engineer jobs added in the last day ({@code t=1}; sorting by date doesn't
 * actually order them). A results page has 20 {@code jobs}, each with a short snippet, and
 * {@code pageCursors} for the next pages. Each new job's page, /job/{jobKey}, has the full
 * {@code jobDescriptionHtml}.
 * <p>
 * A day's search finds a few hundred jobs, so only the first {@link #MAX_PAGES} pages are read.
 * If several job pages in a row fail, SimplyHired is probably blocking, so the run stops there;
 * jobs not fetched are tried again next run.
 */
public class ScrapeSimplyHired {

	private static final String SEARCH_URL = "https://www.simplyhired.com/search?q=software+engineer&l=remote&t=1";
	private static final int MAX_PAGES = 5;
	private static final int MAX_FAILURES_IN_A_ROW = 3;
	private static final String JOB_SITE = "simplyhired";

	private final Jobs store = new Jobs();
	private final ObjectMapper mapper = new ObjectMapper();

	public static void main(String[] args) {
		new ScrapeSimplyHired().run();
	}

	public void run() {
		int found = 0;
		int saved = 0;
		int failuresInARow = 0;
		String pageUrl = SEARCH_URL;

		pages:
		for (int page = 1; page <= MAX_PAGES && pageUrl != null; page++) {
			if (page > 1) {
				Utils.sleepRandom(4);
			}
			Optional<JsonNode> results = pageProps(pageUrl);
			if (results.isEmpty()) {
				break;
			}

			for (JsonNode listed : results.get().path("jobs")) {
				String key = listed.path("jobKey").asText("");
				if (key.isEmpty()) {
					continue;
				}
				found++;

				String url = "https://www.simplyhired.com/job/" + key;
				if (store.exists(url)) {
					continue;
				}

				Utils.sleepRandom(4);
				Optional<JsonNode> details = pageProps(url);
				if (details.isEmpty() || details.get().path("jobDescriptionHtml").asText("").isBlank()) {
					if (++failuresInARow >= MAX_FAILURES_IN_A_ROW) {
						System.out.println(MAX_FAILURES_IN_A_ROW + " job pages in a row failed, stopping");
						break pages;
					}
					continue;
				}
				failuresInARow = 0;

				Job job = new Job();
				job.url = url;
				job.title = details.get().path("jobTitle").asText(listed.path("title").asText("")).strip();
				job.company = details.get().path("employerName").asText(listed.path("company").asText("")).strip();
				job.description = summary(listed, details.get()) + details.get().path("jobDescriptionHtml").asText("");
				job.job_site = JOB_SITE;
				job.status = "new";
				job.job_posting_date = LocalDate.now();

				if (!job.title.isEmpty() && store.saveNew(job)) {
					saved++;
					System.out.println("Saved " + job.title + " at " + job.company);
				}
			}

			String cursor = results.get().path("pageCursors").path(String.valueOf(page + 1)).asText("");
			pageUrl = cursor.isEmpty() ? null : SEARCH_URL + "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
		}

		System.out.println(JOB_SITE + ": " + found + " found, " + saved + " new");
	}

	private static String summary(JsonNode listed, JsonNode details) {
		StringBuilder html = new StringBuilder();
		line(html, "Location", details.path("formattedLocation").asText(listed.path("location").asText("")));
		line(html, "Workplace", join(listed.path("remoteAttributes")));
		line(html, "Pay", details.path("compensation").asText(listed.path("salaryInfo").asText("")));
		line(html, "Type", join(details.path("jobTypes")));
		line(html, "Experience", join(details.path("experience")));
		return html.toString();
	}

	private static String join(JsonNode array) {
		List<String> values = new ArrayList<>();
		array.forEach(value -> values.add(value.asText()));
		return String.join(", ", values);
	}

	private static void line(StringBuilder html, String label, String value) {
		if (value != null && !value.isBlank() && !"null".equals(value)) {
			html.append("<p><strong>").append(label).append(":</strong> ")
					.append(Entities.escape(value.strip())).append("</p>\n");
		}
	}

	// The page's props.pageProps, from its __NEXT_DATA__ script.
	private Optional<JsonNode> pageProps(String url) {
		return Http.get(url).flatMap(html -> {
			Element script = Jsoup.parse(html, url).getElementById("__NEXT_DATA__");
			if (script == null) {
				System.out.println("No __NEXT_DATA__ on " + url);
				return Optional.empty();
			}
			try {
				return Optional.of(mapper.readTree(script.data()).path("props").path("pageProps"));
			} catch (Exception e) {
				System.out.println("Couldn't read the __NEXT_DATA__ on " + url + ": " + e.getMessage());
				return Optional.empty();
			}
		});
	}
}
