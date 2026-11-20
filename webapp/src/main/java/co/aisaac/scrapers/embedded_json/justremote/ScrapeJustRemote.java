package co.aisaac.scrapers.embedded_json.justremote;

import co.aisaac.scrapers.Http;
import co.aisaac.scrapers.Utils;
import co.aisaac.webapp.Job;
import co.aisaac.webapp.Jobs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Scrapes JustRemote. Its pages carry their data as JSON in a script that sets {@code window.__PRELOADED_STATE__}.
 * <p>
 * Any listing page holds the same ~80 newest jobs from every category, in {@code jobsState.entity.all},
 * without descriptions. Only {@link #CATEGORIES} are kept. Each new job's page has its details in
 * {@code singleJobState.{category}}, in parts (about the role, who they're looking for, what they offer,
 * about the company) that are joined here, plus an apply link to the original posting, often on
 * Greenhouse or Ashby. A job whose original posting is already saved is skipped.
 */
public class ScrapeJustRemote {

	private static final String LISTING_URL = "https://justremote.co/remote-developer-jobs";
	private static final Set<String> CATEGORIES = Set.of("developer", "devopsandsysadmin");
	private static final String JOB_SITE = "justremote";

	private final Jobs store = new Jobs();
	private final ObjectMapper mapper = new ObjectMapper();

	public static void main(String[] args) {
		new ScrapeJustRemote().run();
	}

	public void run() {
		Optional<JsonNode> listing = state(LISTING_URL);
		if (listing.isEmpty()) {
			System.out.println(JOB_SITE + ": couldn't read the listing");
			return;
		}

		int found = 0;
		int saved = 0;

		for (JsonNode listed : listing.get().path("jobsState").path("entity").path("all")) {
			String category = listed.path("category").asText("");
			String href = listed.path("href").asText("");
			if (!CATEGORIES.contains(category) || href.isEmpty() || !listed.path("is_active").asBoolean(true)) {
				continue;
			}
			found++;

			String url = "https://justremote.co/" + href;
			if (store.exists(url)) {
				continue;
			}

			Utils.sleepRandom(3);
			Optional<JsonNode> page = state(url);
			if (page.isEmpty()) {
				continue;
			}
			JsonNode job = page.get().path("singleJobState").path(category);
			if (!href.equals(job.path("href").asText())) {
				System.out.println("No details for " + url);
				continue;
			}

			String original = applyLink(job.path("apply").asText(""));
			if (!original.isEmpty() && store.exists(original)) {
				continue;
			}

			Job saving = new Job();
			saving.url = url;
			saving.title = job.path("title").asText(listed.path("title").asText("")).strip();
			saving.company = job.path("company_name").asText(listed.path("company_name").asText("")).strip();
			saving.description = summary(job, original) + description(job);
			saving.job_site = JOB_SITE;
			saving.status = "new";
			saving.job_posting_date = LocalDate.now();

			if (!saving.title.isEmpty() && store.saveNew(saving)) {
				saved++;
				System.out.println("Saved " + saving.title + " at " + saving.company);
			}
		}

		System.out.println(JOB_SITE + ": " + found + " found, " + saved + " new");
	}

	private static String summary(JsonNode job, String original) {
		List<String> countries = new ArrayList<>();
		job.path("country_list").forEach(country -> countries.add(country.path("label").asText()));

		StringBuilder html = new StringBuilder();
		line(html, "Location", String.join(", ", countries));
		line(html, "Workplace", job.path("remote_type").asText(""));
		line(html, "Type", job.path("job_type").asText(""));
		if (!original.isEmpty()) {
			html.append("<p><strong>Original posting:</strong> <a href=\"").append(Entities.escape(original)).append("\">")
					.append(Entities.escape(original)).append("</a></p>\n");
		}
		return html.toString();
	}

	private static String description(JsonNode job) {
		StringBuilder html = new StringBuilder();
		section(html, "About the role", job.path("about_role").asText(""));
		section(html, "Who they're looking for", job.path("who_looking_for").asText(""));
		section(html, "What they offer", job.path("our_offer").asText(""));
		section(html, "About the company", job.path("about_company").asText(""));
		return html.toString();
	}

	private static void section(StringBuilder html, String heading, String content) {
		if (!Jsoup.parseBodyFragment(content).text().isBlank()) {
			html.append("<h3>").append(heading).append("</h3>\n").append(content).append("\n");
		}
	}

	// The apply field is a paragraph holding one link to the original posting.
	private static String applyLink(String applyHtml) {
		Element link = Jsoup.parseBodyFragment(applyHtml).selectFirst("a[href^=http]");
		return link == null ? "" : link.attr("href").strip();
	}

	private static void line(StringBuilder html, String label, String value) {
		if (value != null && !value.isBlank()) {
			html.append("<p><strong>").append(label).append(":</strong> ")
					.append(Entities.escape(value.strip())).append("</p>\n");
		}
	}

	// The JSON a page assigns to window.__PRELOADED_STATE__.
	private Optional<JsonNode> state(String url) {
		return Http.get(url).flatMap(html -> {
			for (Element script : Jsoup.parse(html, url).select("script")) {
				String js = script.data();
				int start = js.indexOf("window.__PRELOADED_STATE__");
				if (start < 0) {
					continue;
				}
				int brace = js.indexOf('{', start);
				int end = js.lastIndexOf('}');
				if (brace < 0 || end < brace) {
					break;
				}
				try {
					return Optional.of(mapper.readTree(js.substring(brace, end + 1)));
				} catch (Exception e) {
					System.out.println("Couldn't read the page state on " + url + ": " + e.getMessage());
					return Optional.empty();
				}
			}
			System.out.println("No page state on " + url);
			return Optional.empty();
		});
	}
}
