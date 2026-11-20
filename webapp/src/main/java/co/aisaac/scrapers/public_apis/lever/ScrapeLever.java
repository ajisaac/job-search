package co.aisaac.scrapers.public_apis.lever;

import co.aisaac.scrapers.CompanyBoards;
import co.aisaac.scrapers.CompanyBoards.Company;
import co.aisaac.scrapers.Http;
import co.aisaac.scrapers.Utils;
import co.aisaac.webapp.Job;
import co.aisaac.webapp.Jobs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.nodes.Entities;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Scrapes Lever job boards, one GET per company from companies/lever.txt, full descriptions included.
 * <p>
 * Lever splits a description into an intro ({@code description}), headed lists ({@code lists}: "What you'll do"
 * and so on, each a run of {@code <li>}s) and a closing part ({@code additional}); they're joined back together here.
 * The API doesn't give the company's name, so it's the display name from the file, or the url name like
 * the browser extension uses. The saved url, jobs.lever.co/{company}/{id}, is the same one the extension saves.
 */
public class ScrapeLever {

	private static final String JOB_SITE = "lever";

	private final Jobs store = new Jobs();
	private final ObjectMapper mapper = new ObjectMapper();

	public static void main(String[] args) {
		new ScrapeLever().run();
	}

	public void run() {
		List<Company> companies = CompanyBoards.read(JOB_SITE);
		int found = 0;
		int saved = 0;

		for (int i = 0; i < companies.size(); i++) {
			if (i > 0) {
				Utils.sleepRandom(3);
			}
			Company company = companies.get(i);

			Optional<JsonNode> postings = readJson("https://api.lever.co/v0/postings/" + company.slug() + "?mode=json");
			if (postings.isEmpty() || !postings.get().isArray()) {
				continue;
			}

			int technical = 0;
			int savedHere = 0;

			for (JsonNode posting : postings.get()) {
				String title = posting.path("text").asText("").strip();
				if (!CompanyBoards.isTechTitle(title)) {
					continue;
				}
				technical++;

				Job job = new Job();
				job.url = posting.path("hostedUrl").asText("").strip();
				job.title = title;
				job.company = company.nameOr("");
				job.description = summary(posting) + description(posting);
				job.job_site = JOB_SITE;
				job.status = "new";
				job.job_posting_date = LocalDate.now();

				if (!job.url.isEmpty() && store.saveNew(job)) {
					savedHere++;
				}
			}

			CompanyBoards.report(company.slug(), postings.get().size(), technical, savedHere);
			found += technical;
			saved += savedHere;
		}

		System.out.println(JOB_SITE + ": " + companies.size() + " companies, " + found + " found, " + saved + " new");
	}

	private static String summary(JsonNode posting) {
		JsonNode categories = posting.path("categories");
		String salary = "";
		JsonNode range = posting.path("salaryRange");
		if (range.path("min").asLong(0) > 0) {
			salary = range.path("min").asLong() + " - " + range.path("max").asLong() + " "
					+ range.path("currency").asText("") + " " + range.path("interval").asText("").replace('-', ' ');
		}

		return CompanyBoards.line("Location", categories.path("location").asText(""))
				+ CompanyBoards.line("Workplace", posting.path("workplaceType").asText(""))
				+ CompanyBoards.line("Type", categories.path("commitment").asText(""))
				+ CompanyBoards.line("Team", categories.path("team").asText(""))
				+ CompanyBoards.line("Salary", salary.strip());
	}

	private static String description(JsonNode posting) {
		StringBuilder html = new StringBuilder(posting.path("description").asText(""));
		for (JsonNode list : posting.path("lists")) {
			html.append("\n<h3>").append(Entities.escape(list.path("text").asText(""))).append("</h3>\n")
					.append("<ul>").append(list.path("content").asText("")).append("</ul>\n");
		}
		html.append(posting.path("additional").asText(""));
		return html.toString();
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
