package co.aisaac.scrapers.embedded_json.arcdev;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Scrapes Arc.dev. Its pages are built with Next.js, so each carries its data as JSON in a
 * {@code <script id="__NEXT_DATA__">}, under {@code props.pageProps}.
 * <p>
 * A listing page holds two lists of 30, newest first, and has no paging:
 * <ul>
 *   <li>{@code arcJobs}: posted on Arc itself. The company's name is hidden. Pages at /remote-jobs/details/{slug}-{key}</li>
 *   <li>{@code externalJobs}: collected from elsewhere (often LinkedIn), with the company's name and the original
 *   posting's url. Pages at /remote-jobs/j/{slug}-{key}</li>
 * </ul>
 * Only engineering jobs are kept ({@code jobRole} or {@code positionType}). The lists have no descriptions,
 * so each new job's own page is fetched for {@code pageProps.job.description}, which is plain text with
 * blank lines between paragraphs, sometimes with a few html tags.
 */
public class ScrapeArcDev {

	// The software development page adds more engineering jobs from elsewhere; the main page has more of Arc's own.
	private static final List<String> LISTINGS = List.of(
			"https://arc.dev/remote-jobs",
			"https://arc.dev/remote-jobs/software-development"
	);
	private static final String ROLE = "engineering";
	private static final String JOB_SITE = "arcdev";
	private static final int MAX_SKILLS = 12;

	private final Jobs store = new Jobs();
	private final ObjectMapper mapper = new ObjectMapper();

	public static void main(String[] args) {
		new ScrapeArcDev().run();
	}

	public void run() {
		// url -> the job from a listing, so a job on both listing pages is only fetched once
		Map<String, JsonNode> listed = new LinkedHashMap<>();

		for (int i = 0; i < LISTINGS.size(); i++) {
			if (i > 0) {
				Utils.sleepRandom(3);
			}
			Optional<JsonNode> props = pageProps(LISTINGS.get(i));
			if (props.isEmpty()) {
				continue;
			}
			for (JsonNode job : props.get().path("arcJobs")) {
				if (ROLE.equals(job.path("jobRole").asText())) {
					listed.putIfAbsent(url("details", job), job);
				}
			}
			for (JsonNode job : props.get().path("externalJobs")) {
				if (ROLE.equals(job.path("positionType").asText())) {
					listed.putIfAbsent(url("j", job), job);
				}
			}
		}

		int saved = 0;
		for (Map.Entry<String, JsonNode> entry : listed.entrySet()) {
			String url = entry.getKey();
			if (store.exists(url)) {
				continue;
			}

			Utils.sleepRandom(3);
			Optional<JsonNode> details = pageProps(url);
			if (details.isEmpty()) {
				continue;
			}
			JsonNode job = details.get().path("job");

			Job saving = new Job();
			saving.url = url;
			saving.title = job.path("title").asText(entry.getValue().path("title").asText("")).strip();
			saving.company = companyName(details.get(), entry.getValue());
			saving.description = summary(job) + paragraphs(job.path("description").asText(""));
			saving.job_site = JOB_SITE;
			saving.status = "new";
			saving.job_posting_date = LocalDate.now();

			if (!saving.title.isEmpty() && store.saveNew(saving)) {
				saved++;
				System.out.println("Saved " + saving.title + (saving.company.isEmpty() ? "" : " at " + saving.company));
			}
		}

		System.out.println(JOB_SITE + ": " + listed.size() + " found, " + saved + " new");
	}

	// "https://arc.dev/remote-jobs/details/fractional-cto-part-time-americas-q06txc6ocs"
	private static String url(String kind, JsonNode job) {
		return "https://arc.dev/remote-jobs/" + kind + "/"
				+ job.path("urlString").asText() + "-" + job.path("randomKey").asText();
	}

	// Arc's own jobs hide the company, so they have none.
	private static String companyName(JsonNode details, JsonNode listed) {
		String name = details.path("job").path("companyName").asText("");
		if (name.isBlank()) {
			name = details.path("company").path("name").asText("");
		}
		if (name.isBlank()) {
			name = listed.path("company").path("name").asText("");
		}
		return name.strip();
	}

	private static String summary(JsonNode job) {
		StringBuilder html = new StringBuilder();

		List<String> locations = texts(job.path("requiredLocations"));
		if (locations.isEmpty()) {
			locations = texts(job.path("requiredCountries"));
		}
		line(html, "Location", String.join(", ", locations));

		line(html, "Type", job.path("jobType").asText(job.path("contractType").asText("")));

		String level = job.path("experienceLevel").asText("");
		line(html, "Level", level.isEmpty() ? String.join(", ", texts(job.path("experienceLevels"))) : level);

		line(html, "Pay", pay(job));
		if (job.path("availableHoursPerWeek").asInt(0) > 0) {
			line(html, "Hours", job.path("availableHoursPerWeek").asInt() + " a week");
		}

		List<String> skills = new ArrayList<>();
		for (JsonNode category : job.path("categories")) {
			if (skills.size() < MAX_SKILLS) {
				skills.add(category.path("name").asText());
			}
		}
		line(html, "Skills", String.join(", ", skills));

		String original = job.path("url").asText("");
		if (original.startsWith("http")) {
			html.append("<p><strong>Original posting:</strong> <a href=\"").append(Entities.escape(original)).append("\">")
					.append(Entities.escape(original)).append("</a></p>\n");
		}
		return html.toString();
	}

	private static String pay(JsonNode job) {
		long minYear = job.path("minAnnualSalary").asLong(0);
		long maxYear = job.path("maxAnnualSalary").asLong(0);
		if (minYear > 0 || maxYear > 0) {
			return range(minYear, maxYear) + " a year";
		}
		long minHour = job.path("minHourlyRate").asLong(0);
		long maxHour = job.path("maxHourlyRate").asLong(0);
		if (minHour > 0 || maxHour > 0) {
			return range(minHour, maxHour) + " an hour";
		}
		return "";
	}

	private static String range(long min, long max) {
		if (min > 0 && max > min) {
			return "$" + min + " - $" + max;
		}
		return "$" + Math.max(min, max);
	}

	// Blank lines separate paragraphs, single line breaks stay line breaks. Any html tags already there are kept.
	private static String paragraphs(String text) {
		StringBuilder html = new StringBuilder();
		for (String paragraph : text.strip().split("\\s*\\n\\s*\\n\\s*")) {
			if (!paragraph.isBlank()) {
				html.append("<p>").append(paragraph.strip().replaceAll("\\s*\\n\\s*", "<br>\n")).append("</p>\n");
			}
		}
		return html.toString();
	}

	private static List<String> texts(JsonNode array) {
		List<String> values = new ArrayList<>();
		array.forEach(value -> values.add(value.asText()));
		return values;
	}

	private static void line(StringBuilder html, String label, String value) {
		if (value != null && !value.isBlank()) {
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
