package co.aisaac.scrapers.public_apis.hackernews;

import co.aisaac.scrapers.Http;
import co.aisaac.webapp.Job;
import co.aisaac.webapp.Jobs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;

import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Scrapes the latest Hacker News "Ask HN: Who is hiring?" thread through the Algolia HN API.
 * <p>
 * Finds the newest thread posted by the whoishiring account, then fetches it with all its comments in one request.
 * Each top-level comment is one job. By convention its first line is "Company | Role | Location | ...",
 * so the first part is the company and the rest is the title. The url is the comment on Hacker News.
 * <p>
 * Run it again during the month to pick up comments added since.
 */
public class ScrapeHackerNews {

	private static final String THREADS_URL =
			"https://hn.algolia.com/api/v1/search_by_date?tags=story,author_whoishiring&hitsPerPage=10";
	private static final String THREAD_TITLE = "Ask HN: Who is hiring?";

	// Same job_site the old scraper used, so jobs it saved and these share a folder and a filter.
	private static final String JOB_SITE = "hacker_news";

	private static final int MAX_TITLE_LENGTH = 150;

	// Posts edited to say the roles are filled.
	private static final Pattern CLOSED = Pattern.compile(
			"no longer (hiring|taking applications|accepting applications)|positions? (has|have) been filled",
			Pattern.CASE_INSENSITIVE);

	private final Jobs store = new Jobs();
	private final ObjectMapper mapper = new ObjectMapper();

	public static void main(String[] args) {
		new ScrapeHackerNews().run();
	}

	public void run() {
		Optional<JsonNode> thread = latestThreadId().flatMap(id -> readJson("https://hn.algolia.com/api/v1/items/" + id));
		if (thread.isEmpty()) {
			System.out.println(JOB_SITE + ": couldn't find or fetch the latest \"" + THREAD_TITLE + "\" thread");
			return;
		}
		System.out.println("Thread: " + thread.get().path("title").asText());

		int found = 0;
		int saved = 0;

		for (JsonNode comment : thread.get().path("children")) {
			String html = comment.path("text").asText("");
			// Deleted comments have no text or author.
			if (html.isBlank() || comment.path("author").isNull()) {
				continue;
			}
			found++;

			Job job = toJob(comment.path("id").asLong(), html);
			if (job == null) {
				continue;
			}
			if (store.saveNew(job)) {
				saved++;
				System.out.println("Saved " + job.title + " at " + job.company);
			}
		}

		System.out.println(JOB_SITE + ": " + found + " found, " + saved + " new");
	}

	// The whoishiring account also posts "Who wants to be hired?" and "Freelancer?" threads, so match the title.
	private Optional<Long> latestThreadId() {
		return readJson(THREADS_URL).flatMap(response -> {
			for (JsonNode hit : response.path("hits")) {
				if (hit.path("title").asText("").startsWith(THREAD_TITLE)) {
					return Optional.of(hit.path("objectID").asLong());
				}
			}
			return Optional.empty();
		});
	}

	private static Job toJob(long id, String html) {
		// The first line is everything before the first paragraph break.
		int p = html.indexOf("<p>");
		String firstLine = Jsoup.parseBodyFragment(p >= 0 ? html.substring(0, p) : html).text().trim();
		if (firstLine.isEmpty() || CLOSED.matcher(Jsoup.parseBodyFragment(html).text()).find()) {
			return null;
		}

		String company = "";
		String title = firstLine;
		int bar = firstLine.indexOf('|');
		if (bar > 0) {
			company = firstLine.substring(0, bar).trim();
			title = firstLine.substring(bar + 1).trim();
		}
		if (title.isEmpty()) {
			title = firstLine;
		}
		if (title.length() > MAX_TITLE_LENGTH) {
			title = title.substring(0, MAX_TITLE_LENGTH).trim() + "…";
		}

		Job job = new Job();
		job.url = "https://news.ycombinator.com/item?id=" + id;
		job.title = title;
		job.company = company;
		job.description = html;
		job.job_site = JOB_SITE;
		job.status = "new";
		job.job_posting_date = LocalDate.now();
		return job;
	}

	private Optional<JsonNode> readJson(String url) {
		return Http.get(url).flatMap(body -> {
			try {
				return Optional.of(mapper.readTree(body));
			} catch (Exception e) {
				System.out.println("Couldn't read " + url + ": " + e.getMessage());
				return Optional.empty();
			}
		});
	}
}
